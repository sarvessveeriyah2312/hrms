package com.hrms.payroll;

import com.hrms.attendance.PayrollFeed;
import com.hrms.employee.EmployeeIdentity;
import com.hrms.files.FileStore;
import com.hrms.leave.LeaveService;
import com.hrms.notification.Notifications;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payslips and tax forms. HR uploads a PDF per person (matched by employee number in the file name) or generates
 * payslips from pay lines; they stay drafts, seen only by HR, until published, when each person is notified. People open
 * their own published documents (recorded as read receipts) and can download a password-protected copy to share.
 */
@Service
class PayrollDocuments {

    static final String PAYSLIP = "PAYSLIP";
    static final String TAX_FORM = "TAX_FORM";
    static final Set<String> FORM_CODES = Set.of("EA", "IR8A", "IR56B", "FORM16", "OTHER");
    /** The usual yearly form per country. */
    static final Map<String, String> FORM_BY_COUNTRY = Map.of("MY", "EA", "SG", "IR8A", "HK", "IR56B", "IN", "FORM16");
    static final int MAX_ITEMS = 500;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    record UploadItem(UUID fileId, UUID employeeId) {
    }

    record UploadInput(String kind, String period, String formCode, List<UploadItem> items) {
    }

    record Failed(UUID fileId, String name, String message) {
    }

    record UploadResult(List<Doc> saved, List<Failed> failed) {
    }

    /** template: a layout code (PayslipTemplates); empty uses the company's default. */
    record GenerateInput(String period, String csv, String template) {
    }

    record PublishInput(String kind, String period, String formCode, List<UUID> ids) {
    }

    /** period: "2026-10" for payslips, "2025" for tax forms. read: the employee has opened it. */
    record Doc(UUID id, UUID employeeId, String employeeName, String employeeNo, String department, String kind, String period,
               String formCode, String fileName, int sizeBytes, String source, String currency, BigDecimal gross,
               BigDecimal deductions, BigDecimal net, BigDecimal employerCost, String status, Instant publishedAt,
               Instant firstViewedAt, Instant lastViewedAt, int viewCount, Instant createdAt, String template) {
    }

    record Line(String kind, String category, String label, BigDecimal amount, BigDecimal quantity, BigDecimal rate) {
    }

    record Detail(Doc doc, List<Line> lines) {
    }

    record PeriodSummary(String period, String formCode, int total, int drafts, int published, int read) {
    }

    record Person(UUID employeeId, String employeeNo, String employeeName, String department) {
    }

    /** missing: people employed during the period with no document for it. */
    record PeriodView(String kind, String period, String formCode, List<Doc> documents, List<Person> missing) {
    }

    private record Row(UUID id, UUID employeeId, String employeeName, String employeeNo, String department, String kind,
                       LocalDate period, String formCode, UUID fileId, String source, String currency, BigDecimal gross,
                       BigDecimal deductions, BigDecimal net, BigDecimal employerCost, String status, Instant publishedAt,
                       Instant firstViewedAt, Instant lastViewedAt, int viewCount, Instant createdAt, String template) {
    }

    private static final String SELECT = """
            select p.id, p.employee_id, e.full_name as employee_name, e.employee_no, d.name as department, p.kind, p.period,
                   p.form_code, p.file_id, p.source, p.currency, p.gross, p.deductions, p.net, p.employer_cost, p.status,
                   p.published_at, p.first_viewed_at, p.last_viewed_at, p.view_count, p.created_at, p.template
            from payroll_document p
            join employee e on e.id = p.employee_id
            left join department d on d.id = e.department_id
            """;

    private final JdbcClient jdbc;
    private final FileStore files;
    private final Notifications notifications;
    private final AuditLog audit;

    private final PayslipSettings templates;
    private final PayrollFeed attendanceFeed;
    private final LeaveService leave;
    private final EmployeeIdentity identity;

    PayrollDocuments(JdbcClient jdbc, FileStore files, Notifications notifications, AuditLog audit, PayslipSettings templates,
                     PayrollFeed attendanceFeed, LeaveService leave, EmployeeIdentity identity) {
        this.jdbc = jdbc;
        this.files = files;
        this.notifications = notifications;
        this.audit = audit;
        this.templates = templates;
        this.attendanceFeed = attendanceFeed;
        this.leave = leave;
        this.identity = identity;
    }

    // ---------- employees: their own ----------

    @Transactional(readOnly = true)
    List<Doc> mine(UUID employeeId) {
        return jdbc.sql(SELECT + "where p.employee_id = :emp and p.status = 'PUBLISHED' order by p.period desc, p.kind, p.form_code")
            .param("emp", employeeId).query(Row.class).list().stream().map(this::toDoc).toList();
    }

    /** The owner of a published document, or HR (payroll:manage). Owners opening it count as a read receipt. */
    @Transactional
    Detail detail(UUID id, UUID me, boolean hr) {
        Row r = visible(id, me, hr);
        if (r.employeeId().equals(me)) markRead(id);
        return new Detail(find(id).orElseThrow(), lines(id));
    }

    @Transactional
    FileStore.StoredFile file(UUID id, UUID me, boolean hr) {
        Row r = visible(id, me, hr);
        if (r.employeeId().equals(me)) markRead(id);
        return files.read(r.fileId()).orElseThrow(PayrollDocuments::notFound);
    }

    /** Only the owner: a copy that asks for the password they chose. */
    @Transactional
    byte[] protectedCopy(UUID id, UUID me, String password) {
        Row r = visible(id, me, false);
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw bad("BAD_PASSWORD", "Use a password of 6 to 64 characters.");
        }
        FileStore.StoredFile f = files.read(r.fileId()).orElseThrow(PayrollDocuments::notFound);
        if (!"application/pdf".equals(f.meta().contentType())) throw bad("NOT_PDF", "Only PDFs can be protected with a password.");
        markRead(id);
        return PayslipPdf.protect(f.data(), password);
    }

    String fileName(UUID id) {
        return find(id).map(d -> d.fileName() == null ? "document.pdf" : d.fileName()).orElse("document.pdf");
    }

    private Row visible(UUID id, UUID me, boolean hr) {
        Row r = row(id).orElseThrow(PayrollDocuments::notFound);
        boolean owner = r.employeeId().equals(me) && "PUBLISHED".equals(r.status());
        if (!owner && !hr) throw notFound();
        return r;
    }

    private void markRead(UUID id) {
        jdbc.sql("""
                update payroll_document set first_viewed_at = coalesce(first_viewed_at, now()), last_viewed_at = now(),
                       view_count = view_count + 1
                where id = :id""").param("id", id).update();
    }

    // ---------- HR: periods ----------

    @Transactional(readOnly = true)
    List<PeriodSummary> periods(String kind, int year) {
        String k = kind(kind);
        LocalDate from = PAYSLIP.equals(k) ? LocalDate.of(year, 1, 1) : LocalDate.of(year - 9, 1, 1);
        LocalDate to = PAYSLIP.equals(k) ? LocalDate.of(year, 12, 1) : LocalDate.of(year, 1, 1);
        record Agg(LocalDate period, String formCode, int total, int drafts, int published, int read) {
        }
        return jdbc.sql("""
                select period, form_code, count(*) as total, count(*) filter (where status = 'DRAFT') as drafts,
                       count(*) filter (where status = 'PUBLISHED') as published,
                       count(*) filter (where status = 'PUBLISHED' and first_viewed_at is not null) as read
                from payroll_document where kind = :kind and period between :from and :to
                group by period, form_code order by period desc, form_code""")
            .param("kind", k).param("from", from).param("to", to).query(Agg.class).list().stream()
            .map(a -> new PeriodSummary(periodText(k, a.period()), a.formCode(), a.total(), a.drafts(), a.published(), a.read()))
            .toList();
    }

    @Transactional(readOnly = true)
    PeriodView period(String kind, String period, String formCode) {
        String k = kind(kind);
        LocalDate p = period(k, period);
        String form = form(k, formCode);
        List<Doc> docs = jdbc.sql(SELECT + "where p.kind = :kind and p.period = :period and coalesce(p.form_code, '') = :form order by e.full_name")
            .param("kind", k).param("period", p).param("form", form == null ? "" : form).query(Row.class).list().stream()
            .map(this::toDoc).toList();
        LocalDate start = p;
        LocalDate end = PAYSLIP.equals(k) ? YearMonth.from(p).atEndOfMonth() : LocalDate.of(p.getYear(), 12, 31);
        List<Person> missing = jdbc.sql("""
                select e.id as employee_id, e.employee_no, e.full_name as employee_name, d.name as department
                from employee e left join department d on d.id = e.department_id
                where e.join_date <= :end and (e.last_day is null or e.last_day >= :start)
                  and (e.status = 'ACTIVE' or e.last_day is not null)
                  and not exists (select 1 from payroll_document x where x.employee_id = e.id and x.kind = :kind
                                  and x.period = :period and coalesce(x.form_code, '') = :form)
                order by e.full_name limit 500""")
            .param("end", end).param("start", start).param("kind", k).param("period", p).param("form", form == null ? "" : form)
            .query(Person.class).list();
        return new PeriodView(k, periodText(k, p), form, docs, missing);
    }

    // ---------- HR: uploading ----------

    @Transactional
    UploadResult upload(UUID user, UploadInput in) {
        if (in == null) throw bad("NOTHING_UPLOADED", "Choose the files to upload.");
        String k = kind(in.kind());
        LocalDate p = period(k, in.period());
        String form = form(k, in.formCode());
        List<UploadItem> items = in.items() == null ? List.of() : in.items();
        if (items.isEmpty()) throw bad("NOTHING_UPLOADED", "Choose the files to upload.");
        if (items.size() > MAX_ITEMS) throw bad("TOO_MANY", "Upload up to " + MAX_ITEMS + " files at a time.");
        List<Person> people = everyone();
        List<Doc> saved = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        for (UploadItem item : items) {
            FileStore.FileMeta meta = item.fileId() == null ? null : files.meta(item.fileId()).filter(m -> m.ownerUserId().equals(user)).orElse(null);
            if (meta == null) {
                failed.add(new Failed(item.fileId(), null, "That file wasn't uploaded by you."));
                continue;
            }
            try {
                if (!"application/pdf".equals(meta.contentType())) throw bad("NOT_PDF", "Upload PDFs only.");
                PayslipPdf.requireOpenable(files.read(meta.id()).orElseThrow().data());
                UUID employee = item.employeeId() != null ? requireEmployee(item.employeeId()) : match(meta.name(), people);
                UUID id = saveDraft(user, employee, k, p, form, meta.id(), "UPLOAD", null, null, null, null, null);
                jdbc.sql("delete from payroll_line where document_id = :id").param("id", id).update();
                saved.add(find(id).orElseThrow());
            } catch (ApiException e) {
                failed.add(new Failed(meta.id(), meta.name(), e.getMessage()));
            }
        }
        if (!saved.isEmpty()) {
            audit.record(user, "PAYROLL_DOCUMENTS_UPLOADED", "payroll_document", null,
                Map.of("kind", k, "period", periodText(k, p), "count", saved.size()));
        }
        return new UploadResult(saved, failed);
    }

    /** Finds the person whose employee number appears in the file name (the longest match wins). */
    static UUID match(String fileName, List<Person> people) {
        String name = fileName == null ? "" : fileName.toUpperCase(Locale.ROOT).replaceAll("\\.PDF$", "");
        Person best = null;
        boolean tie = false;
        for (Person p : people) {
            String no = p.employeeNo().toUpperCase(Locale.ROOT);
            if (!containsToken(name, no)) continue;
            if (best == null || no.length() > best.employeeNo().length()) {
                best = p;
                tie = false;
            } else if (no.length() == best.employeeNo().length()) {
                tie = true;
            }
        }
        if (best == null) throw bad("NO_MATCH", "No employee number found in the file name. Name files like E0012.pdf or E0012-2026-10.pdf.");
        if (tie) throw bad("AMBIGUOUS", "The file name matches more than one employee number.");
        return best.employeeId();
    }

    /** The number appears on its own, not inside a longer run of letters and digits (E001 doesn't match E0012). */
    private static boolean containsToken(String haystack, String needle) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) return false;
            int end = i + needle.length();
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(haystack.charAt(i - 1));
            boolean rightOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    // ---------- HR: generating payslips ----------

    @Transactional
    UploadResult generate(UUID user, GenerateInput in) {
        if (in == null) throw bad("CSV_REQUIRED", "Choose the pay lines file (CSV).");
        LocalDate p = period(PAYSLIP, in.period());
        YearMonth month = YearMonth.from(p);
        Map<String, List<PayslipPdf.Line>> byEmployee = PayLines.parse(in.csv());
        record Target(UUID id, String employeeNo, String fullName, String department, String jobTitle, String company,
                      String registrationNo, String currency) {
        }
        List<String> unknown = new ArrayList<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        for (String no : byEmployee.keySet()) {
            Optional<Target> t = jdbc.sql("""
                    select e.id, e.employee_no, e.full_name, d.name as department, e.job_title, l.name as company,
                           l.registration_no, l.currency
                    from employee e join legal_entity l on l.id = e.entity_id left join department d on d.id = e.department_id
                    where upper(e.employee_no) = upper(:no)""").param("no", no).query(Target.class).optional();
            if (t.isPresent()) targets.put(no, t.get());
            else unknown.add(no);
        }
        if (!unknown.isEmpty()) {
            throw bad("UNKNOWN_EMPLOYEES", "These employee numbers aren't in the company: " + String.join(", ", unknown.stream().limit(20).toList())
                + (unknown.size() > 20 ? " and " + (unknown.size() - 20) + " more" : "") + ". Nothing was generated.");
        }
        String template = templates.resolve(in.template());
        Map<UUID, PayrollFeed.Month> attendance = new LinkedHashMap<>();
        try {
            attendance(month).forEach(m -> attendance.put(m.employeeId(), m));
        } catch (RuntimeException e) {
            // Attendance figures are a nice-to-have on the payslip; without them the layout leaves them blank.
        }
        List<Doc> saved = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (var entry : byEmployee.entrySet()) {
            Target t = targets.get(entry.getKey());
            List<PayslipPdf.Line> lines = entry.getValue();
            try {
                EmployeeIdentity.Numbers ids = identity.numbers(t.id());
                PayslipTemplates.Slip slip = new PayslipTemplates.Slip(t.company(), t.registrationNo(), month, today, t.currency(),
                    t.employeeNo(), t.fullName(), ids.idNumber(), t.department(), t.jobTitle(), ids.epfNumber(), ids.socsoNumber(),
                    ids.taxNumber(), attendanceOf(attendance.get(t.id())), leaveOf(t.id(), month.getYear()), ytd(t.id(), p, lines), lines);
                byte[] pdf = PayslipTemplates.render(template, slip);
                FileStore.FileMeta meta = files.store(user, "Payslip " + t.employeeNo() + " " + month + ".pdf", pdf);
                BigDecimal gross = PayslipPdf.total(lines, "EARNING");
                BigDecimal deductions = PayslipPdf.total(lines, "DEDUCTION");
                UUID id = saveDraft(user, t.id(), PAYSLIP, p, null, meta.id(), "GENERATED", t.currency(), gross, deductions,
                    gross.subtract(deductions), PayslipPdf.total(lines, "EMPLOYER"));
                jdbc.sql("update payroll_document set template = :template where id = :id").param("template", template).param("id", id).update();
                jdbc.sql("delete from payroll_line where document_id = :id").param("id", id).update();
                int pos = 0;
                for (PayslipPdf.Line l : lines) {
                    jdbc.sql("""
                            insert into payroll_line (tenant_id, document_id, kind, category, label, amount, quantity, rate, position)
                            values (:tenant, :doc, :kind, :category, :label, :amount, :quantity, :rate, :pos)""")
                        .param("tenant", TenantContext.require()).param("doc", id).param("kind", l.kind()).param("category", l.category())
                        .param("label", l.label()).param("amount", l.amount()).param("quantity", l.quantity()).param("rate", l.rate())
                        .param("pos", pos++).update();
                }
                saved.add(find(id).orElseThrow());
            } catch (ApiException e) {
                failed.add(new Failed(null, t.employeeNo() + " " + t.fullName(), e.getMessage()));
            }
        }
        audit.record(user, "PAYSLIPS_GENERATED", "payroll_document", null,
            Map.of("period", month.toString(), "count", saved.size(), "template", template));
        return new UploadResult(saved, failed);
    }

    // ---------- HR: redrawing in another template ----------

    /** ids: only these (all of the month's generated payslips when empty). template: empty uses the company's default. */
    record RedrawInput(String period, String template, List<UUID> ids) {
    }

    /**
     * Draws a month's generated payslips again in a template, from the pay lines saved with them: same figures, new
     * layout. Published ones stay published (and keep their read receipts), so employees see the new layout straight
     * away. Uploaded PDFs can't be redrawn.
     */
    @Transactional
    UploadResult redraw(UUID user, RedrawInput in) {
        if (in == null) throw bad("PERIOD_REQUIRED", "Choose the month.");
        LocalDate p = period(PAYSLIP, in.period());
        YearMonth month = YearMonth.from(p);
        String template = templates.resolve(in.template());
        boolean some = in.ids() != null && !in.ids().isEmpty();
        record Target(UUID docId, UUID employeeId, String employeeNo, String fullName, String department, String jobTitle,
                      String company, String registrationNo, String currency) {
        }
        List<Target> targets = jdbc.sql("""
                select p.id as doc_id, e.id as employee_id, e.employee_no, e.full_name, d.name as department, e.job_title,
                       l.name as company, l.registration_no, coalesce(p.currency, l.currency) as currency
                from payroll_document p
                join employee e on e.id = p.employee_id
                join legal_entity l on l.id = e.entity_id
                left join department d on d.id = e.department_id
                where p.kind = 'PAYSLIP' and p.source = 'GENERATED' and p.period = :period and (:all or p.id in (:ids))
                order by e.full_name""")
            .param("period", p).param("all", !some).param("ids", some ? in.ids() : List.of(UUID.randomUUID()))
            .query(Target.class).list();
        if (targets.isEmpty()) throw bad("NOTHING_TO_REDRAW", "There are no generated payslips for that month. Uploaded PDFs keep their own layout.");
        Map<UUID, PayrollFeed.Month> attendance = new LinkedHashMap<>();
        try {
            attendance(month).forEach(m -> attendance.put(m.employeeId(), m));
        } catch (RuntimeException e) {
            // left blank on the payslip
        }
        LocalDate today = LocalDate.now();
        List<Doc> saved = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        for (Target t : targets) {
            try {
                List<PayslipPdf.Line> lines = lines(t.docId()).stream()
                    .map(l -> new PayslipPdf.Line(l.kind(), l.category(), l.label(), l.amount(), l.quantity(), l.rate())).toList();
                if (lines.isEmpty()) throw bad("NO_LINES", "This payslip has no saved pay lines to draw from.");
                EmployeeIdentity.Numbers ids = identity.numbers(t.employeeId());
                PayslipTemplates.Slip slip = new PayslipTemplates.Slip(t.company(), t.registrationNo(), month, today, t.currency(),
                    t.employeeNo(), t.fullName(), ids.idNumber(), t.department(), t.jobTitle(), ids.epfNumber(), ids.socsoNumber(),
                    ids.taxNumber(), attendanceOf(attendance.get(t.employeeId())), leaveOf(t.employeeId(), month.getYear()),
                    ytd(t.employeeId(), p, lines), lines);
                FileStore.FileMeta meta = files.store(user, "Payslip " + t.employeeNo() + " " + month + ".pdf", PayslipTemplates.render(template, slip));
                jdbc.sql("update payroll_document set file_id = :file, template = :template, version = version + 1 where id = :id")
                    .param("file", meta.id()).param("template", template).param("id", t.docId()).update();
                saved.add(find(t.docId()).orElseThrow());
            } catch (ApiException e) {
                failed.add(new Failed(null, t.employeeNo() + " " + t.fullName(), e.getMessage()));
            }
        }
        audit.record(user, "PAYSLIPS_REDRAWN", "payroll_document", null,
            Map.of("period", month.toString(), "count", saved.size(), "template", template));
        return new UploadResult(saved, failed);
    }

    private List<PayrollFeed.Month> attendance(YearMonth month) {
        return attendanceFeed.month(month).people();
    }

    static PayslipTemplates.Attendance attendanceOf(PayrollFeed.Month m) {
        if (m == null) return null;
        return new PayslipTemplates.Attendance(m.workingDays(), m.daysWorked(), m.earlyCount(), m.lateCount(), m.leaveDays(), m.absentDays());
    }

    /** The first two leave types that keep a balance (e.g. annual and medical leave): taken this year and what's left. */
    private List<PayslipTemplates.Leave> leaveOf(UUID employeeId, int year) {
        try {
            return leave.balances(employeeId, year).stream().filter(LeaveService.Balance::trackBalance).limit(2)
                .map(b -> new PayslipTemplates.Leave(b.name(), nz(b.entitled()).add(nz(b.carried())).add(nz(b.earned())), nz(b.used()),
                    nz(b.available())))
                .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Year-to-date: earlier generated payslips this year plus this one. */
    private PayslipTemplates.Ytd ytd(UUID employeeId, LocalDate period, List<PayslipPdf.Line> current) {
        record Sum(String kind, String category, String label, BigDecimal amount) {
            /** Lines saved before earnings had categories are classified by their item name. */
            String group() {
                return category != null || !"EARNING".equals(kind) ? category : PayLines.categoryOf(label);
            }
        }
        List<Sum> earlier = jdbc.sql("""
                select l.kind, l.category, l.label, l.amount
                from payroll_line l join payroll_document d on d.id = l.document_id
                where d.employee_id = :emp and d.kind = 'PAYSLIP' and d.period >= :from and d.period < :period""")
            .param("emp", employeeId).param("from", LocalDate.of(period.getYear(), 1, 1)).param("period", period)
            .query(Sum.class).list();
        java.util.function.BiFunction<String, String, BigDecimal> total = (kind, category) -> {
            BigDecimal before = earlier.stream().filter(x -> x.kind().equals(kind) && (category == null || category.equals(x.group())))
                .map(Sum::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal now = current.stream().filter(x -> x.kind().equals(kind) && (category == null || category.equals(x.category())))
                .map(PayslipPdf.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return before.add(now);
        };
        BigDecimal gross = total.apply("EARNING", null);
        BigDecimal deductions = total.apply("DEDUCTION", null);
        return new PayslipTemplates.Ytd(total.apply("EARNING", "BASIC"), total.apply("EARNING", "ALLOWANCE"), total.apply("EARNING", "OVERTIME"),
            gross, deductions, gross.subtract(deductions), total.apply("EMPLOYER", null));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Creates the draft, or replaces the file of an existing draft. A published one must be withdrawn first. */
    private UUID saveDraft(UUID user, UUID employee, String kind, LocalDate period, String form, UUID fileId, String source,
                           String currency, BigDecimal gross, BigDecimal deductions, BigDecimal net, BigDecimal employer) {
        record Existing(UUID id, String status) {
        }
        Optional<Existing> existing = jdbc.sql("""
                select id, status from payroll_document
                where employee_id = :emp and kind = :kind and period = :period and coalesce(form_code, '') = :form""")
            .param("emp", employee).param("kind", kind).param("period", period).param("form", form == null ? "" : form)
            .query(Existing.class).optional();
        if (existing.isPresent()) {
            if ("PUBLISHED".equals(existing.get().status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PUBLISHED",
                    "This person's document for that period is already published. Withdraw it first to replace it.");
            }
            jdbc.sql("""
                    update payroll_document set file_id = :file, source = :source, currency = :currency, gross = :gross,
                           deductions = :deductions, net = :net, employer_cost = :employer, created_by = :user, created_at = now(),
                           version = version + 1
                    where id = :id""")
                .param("file", fileId).param("source", source).param("currency", currency).param("gross", gross)
                .param("deductions", deductions).param("net", net).param("employer", employer).param("user", user)
                .param("id", existing.get().id()).update();
            return existing.get().id();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into payroll_document (id, tenant_id, employee_id, kind, period, form_code, file_id, source, currency, gross,
                                              deductions, net, employer_cost, created_by)
                values (:id, :tenant, :emp, :kind, :period, :form, :file, :source, :currency, :gross, :deductions, :net, :employer, :user)""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", employee).param("kind", kind).param("period", period)
            .param("form", form).param("file", fileId).param("source", source).param("currency", currency).param("gross", gross)
            .param("deductions", deductions).param("net", net).param("employer", employer).param("user", user).update();
        return id;
    }

    // ---------- HR: publishing ----------

    /** Publishes the period's drafts (or just the given ones) and tells each person. Returns how many were published. */
    @Transactional
    int publish(UUID user, PublishInput in) {
        if (in == null) throw bad("PERIOD_REQUIRED", "Choose the period to publish.");
        String k = kind(in.kind());
        LocalDate p = period(k, in.period());
        String form = form(k, in.formCode());
        boolean some = in.ids() != null && !in.ids().isEmpty();
        List<UUID> employees = jdbc.sql("""
                update payroll_document set status = 'PUBLISHED', published_at = now(), published_by = :user, version = version + 1
                where status = 'DRAFT' and kind = :kind and period = :period and coalesce(form_code, '') = :form
                  and (:all or id in (:ids))
                returning employee_id""")
            .param("user", user).param("kind", k).param("period", p).param("form", form == null ? "" : form)
            .param("all", !some).param("ids", some ? in.ids() : List.of(UUID.randomUUID()))
            .query(UUID.class).list();
        if (employees.isEmpty()) return 0;
        String label = PAYSLIP.equals(k) ? "Your payslip for " + YearMonth.from(p).format(MONTH) + " is ready"
            : "Your " + formName(form) + " for " + p.getYear() + " is ready";
        notifications.toEmployees(employees, new Notifications.Message(
            PAYSLIP.equals(k) ? Notifications.Kind.PAYSLIP_PUBLISHED : Notifications.Kind.TAX_FORM_PUBLISHED,
            label, "Open it from Payslips.", "/app/payslips", "green"));
        audit.record(user, "PAYROLL_DOCUMENTS_PUBLISHED", "payroll_document", null,
            Map.of("kind", k, "period", periodText(k, p), "count", employees.size()));
        return employees.size();
    }

    /** Takes a published document back to draft (e.g. to replace a wrong one). Its read receipts stay. */
    @Transactional
    void unpublish(UUID user, UUID id) {
        int n = jdbc.sql("update payroll_document set status = 'DRAFT', version = version + 1 where id = :id and status = 'PUBLISHED'")
            .param("id", id).update();
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "NOT_PUBLISHED", "Only a published document can be withdrawn.");
        audit.record(user, "PAYROLL_DOCUMENT_WITHDRAWN", "payroll_document", id, Map.of());
    }

    @Transactional
    void delete(UUID user, UUID id) {
        int n = jdbc.sql("delete from payroll_document where id = :id and status = 'DRAFT'").param("id", id).update();
        if (n == 0) {
            if (row(id).isEmpty()) throw notFound();
            throw new ApiException(HttpStatus.CONFLICT, "PUBLISHED", "Withdraw a published document before deleting it.");
        }
        audit.record(user, "PAYROLL_DOCUMENT_DELETED", "payroll_document", id, Map.of());
    }

    // ---------- helpers ----------

    private List<Person> everyone() {
        return jdbc.sql("""
                select e.id as employee_id, e.employee_no, e.full_name as employee_name, d.name as department
                from employee e left join department d on d.id = e.department_id""").query(Person.class).list();
    }

    private UUID requireEmployee(UUID id) {
        return jdbc.sql("select id from employee where id = :id").param("id", id).query(UUID.class).optional()
            .orElseThrow(() -> bad("NO_EMPLOYEE", "That person isn't in the company."));
    }

    private Optional<Row> row(UUID id) {
        return jdbc.sql(SELECT + "where p.id = :id").param("id", id).query(Row.class).optional();
    }

    Optional<Doc> find(UUID id) {
        return row(id).map(this::toDoc);
    }

    private List<Line> lines(UUID id) {
        return jdbc.sql("select kind, category, label, amount, quantity, rate from payroll_line where document_id = :id order by position")
            .param("id", id).query(Line.class).list().stream()
            .map(l -> l.category() != null || !"EARNING".equals(l.kind()) ? l
                : new Line(l.kind(), PayLines.categoryOf(l.label()), l.label(), l.amount(), l.quantity(), l.rate()))
            .toList();
    }

    private Doc toDoc(Row r) {
        FileStore.FileMeta meta = files.meta(r.fileId()).orElse(null);
        return new Doc(r.id(), r.employeeId(), r.employeeName(), r.employeeNo(), r.department(), r.kind(), periodText(r.kind(), r.period()),
            r.formCode(), meta == null ? null : meta.name(), meta == null ? 0 : meta.sizeBytes(), r.source(), r.currency(), r.gross(),
            r.deductions(), r.net(), r.employerCost(), r.status(), r.publishedAt(), r.firstViewedAt(), r.lastViewedAt(), r.viewCount(),
            r.createdAt(), r.template());
    }

    static String kind(String kind) {
        if (PAYSLIP.equals(kind) || TAX_FORM.equals(kind)) return kind;
        throw bad("BAD_KIND", "Choose payslips or tax forms.");
    }

    /** "2026-10" → 1 Oct 2026 for payslips; "2025" → 1 Jan 2025 for tax forms. */
    static LocalDate period(String kind, String value) {
        if (value == null || value.isBlank()) throw bad("PERIOD_REQUIRED", "Choose the period.");
        try {
            if (PAYSLIP.equals(kind)) return YearMonth.parse(value.trim()).atDay(1);
            int year = Integer.parseInt(value.trim());
            if (year < 2000 || year > 2100) throw bad("BAD_PERIOD", "Give the tax year, like 2025.");
            return LocalDate.of(year, 1, 1);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw bad("BAD_PERIOD", PAYSLIP.equals(kind) ? "Give the month as YYYY-MM." : "Give the tax year, like 2025.");
        }
    }

    static String periodText(String kind, LocalDate period) {
        return PAYSLIP.equals(kind) ? YearMonth.from(period).toString() : String.valueOf(period.getYear());
    }

    static String form(String kind, String formCode) {
        if (PAYSLIP.equals(kind)) return null;
        if (formCode == null || !FORM_CODES.contains(formCode)) throw bad("BAD_FORM", "Choose the tax form (EA, IR8A, IR56B, Form 16 or other).");
        return formCode;
    }

    static String formName(String formCode) {
        return switch (formCode == null ? "" : formCode) {
            case "EA" -> "EA form";
            case "IR8A" -> "IR8A";
            case "IR56B" -> "IR56B";
            case "FORM16" -> "Form 16";
            default -> "tax form";
        };
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYROLL_DOCUMENT_NOT_FOUND", "That document doesn't exist.");
    }

    static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
