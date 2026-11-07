package com.hrms.comms;

import com.hrms.files.FileStore;
import com.hrms.notification.Notifications;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Personal letters on the company letterhead (comms:letters): templates with {{placeholders}} (offer, confirmation,
 * increment…), filled in from the person's record plus anything HR types, drawn as a PDF and issued to the person, who
 * is notified and can open it from News → My letters. A company with no templates gets the standard three to start with.
 */
@Service
class Letters {

    static final Set<String> KINDS = Set.of("OFFER", "CONFIRMATION", "INCREMENT", "PROMOTION", "EXPERIENCE", "WARNING", "OTHER");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]{0,40})\\s*}}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** Filled in from the person's record; anything else in a template is typed in when issuing. */
    static final List<String> AUTO_FIELDS = List.of("name", "firstName", "employeeNo", "position", "department", "company",
        "joinDate", "confirmationDate", "lastDay", "date");

    record LetterheadView(String companyName, String address, String phone, String email, String website, String registrationNo,
                          FileStore.FileMeta logo, String signatoryName, String signatoryTitle, String footer) {
    }

    record LetterheadInput(String companyName, String address, String phone, String email, String website, String registrationNo,
                           UUID logoFileId, String signatoryName, String signatoryTitle, String footer) {
    }

    /** fields: the placeholders HR types in when issuing (the others are filled in automatically). */
    record Template(UUID id, String kind, String name, String subject, String body, boolean active, List<String> fields,
                    Instant updatedAt) {
    }

    record TemplateInput(String kind, String name, String subject, String body, Boolean active) {
    }

    record IssueInput(UUID templateId, UUID employeeId, Map<String, String> values) {
    }

    record Letter(UUID id, UUID employeeId, String employeeName, String employeeNo, String kind, String subject, String fileName,
                  String issuedBy, Instant issuedAt, Instant firstViewedAt, Instant withdrawnAt) {
    }

    private record TemplateRow(UUID id, String kind, String name, String subject, String body, boolean active, Instant updatedAt) {
    }

    private record Person(UUID id, String employeeNo, String fullName, String jobTitle, String department, String entity,
                          LocalDate joinDate, LocalDate confirmedOn, LocalDate lastDay) {
    }

    private static final String LETTER_SELECT = """
            select l.id, l.employee_id, e.full_name as employee_name, e.employee_no, l.kind, l.subject, f.name as file_name,
                   coalesce(ie.full_name, iu.email::text) as issued_by, l.issued_at, l.first_viewed_at, l.withdrawn_at
            from letter l
            join employee e on e.id = l.employee_id
            left join file_object f on f.id = l.file_id
            left join app_user iu on iu.id = l.issued_by
            left join employee ie on ie.user_id = l.issued_by
            """;

    private final JdbcClient jdbc;
    private final FileStore files;
    private final Notifications notifications;
    private final AuditLog audit;
    private final JsonMapper json;

    Letters(JdbcClient jdbc, FileStore files, Notifications notifications, AuditLog audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.files = files;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
    }

    // ---------- letterhead ----------

    @Transactional(readOnly = true)
    LetterheadView letterhead() {
        record Row(String companyName, String address, String phone, String email, String website, String registrationNo,
                   UUID logoFileId, String signatoryName, String signatoryTitle, String footer) {
        }
        Optional<Row> row = jdbc.sql("""
                select company_name, address, phone, email, website, registration_no, logo_file_id, signatory_name, signatory_title, footer
                from letterhead""").query(Row.class).optional();
        if (row.isEmpty()) {
            record Entity(String name, String registrationNo) {
            }
            Entity e = jdbc.sql("select name, registration_no from legal_entity order by created_at limit 1").query(Entity.class)
                .optional().orElse(new Entity(null, null));
            return new LetterheadView(e.name(), null, null, null, null, e.registrationNo(), null, null, null, null);
        }
        Row r = row.get();
        return new LetterheadView(r.companyName(), r.address(), r.phone(), r.email(), r.website(), r.registrationNo(),
            r.logoFileId() == null ? null : files.meta(r.logoFileId()).orElse(null), r.signatoryName(), r.signatoryTitle(), r.footer());
    }

    @Transactional
    LetterheadView saveLetterhead(UUID user, LetterheadInput in) {
        if (in == null) throw bad("LETTERHEAD_REQUIRED", "Fill in the letterhead.");
        UUID current = jdbc.sql("select logo_file_id from letterhead").query(UUID.class).optional().orElse(null);
        if (in.logoFileId() != null && !in.logoFileId().equals(current)) {
            FileStore.FileMeta logo = files.meta(in.logoFileId()).filter(m -> m.ownerUserId().equals(user))
                .orElseThrow(() -> bad("FILE_NOT_FOUND", "Upload the logo first."));
            if (!logo.contentType().startsWith("image/")) throw bad("LOGO_TYPE", "Use a PNG or JPG for the logo.");
        }
        jdbc.sql("""
                insert into letterhead (tenant_id, company_name, address, phone, email, website, registration_no, logo_file_id,
                                        signatory_name, signatory_title, footer, updated_by)
                values (:tenant, :company, :address, :phone, :email, :website, :reg, :logo, :sigName, :sigTitle, :footer, :user)
                on conflict (tenant_id) do update set company_name = excluded.company_name, address = excluded.address,
                       phone = excluded.phone, email = excluded.email, website = excluded.website, registration_no = excluded.registration_no,
                       logo_file_id = excluded.logo_file_id, signatory_name = excluded.signatory_name,
                       signatory_title = excluded.signatory_title, footer = excluded.footer, updated_at = now(), updated_by = excluded.updated_by""")
            .param("tenant", TenantContext.require()).param("company", limit(in.companyName(), 150)).param("address", limit(in.address(), 400))
            .param("phone", limit(in.phone(), 40)).param("email", limit(in.email(), 120)).param("website", limit(in.website(), 120))
            .param("reg", limit(in.registrationNo(), 60)).param("logo", in.logoFileId()).param("sigName", limit(in.signatoryName(), 120))
            .param("sigTitle", limit(in.signatoryTitle(), 120)).param("footer", limit(in.footer(), 200)).param("user", user).update();
        audit.record(user, "LETTERHEAD_UPDATED", "letterhead", null, Map.of());
        return letterhead();
    }

    // ---------- templates ----------

    @Transactional
    List<Template> templates() {
        int count = jdbc.sql("select count(*) from letter_template").query(Integer.class).single();
        if (count == 0) seedDefaults();
        return jdbc.sql("select id, kind, name, subject, body, active, updated_at from letter_template order by active desc, kind, name")
            .query(TemplateRow.class).list().stream().map(Letters::toTemplate).toList();
    }

    @Transactional
    Template createTemplate(UUID user, TemplateInput in) {
        TemplateInput c = checkTemplate(in);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into letter_template (id, tenant_id, kind, name, subject, body, active, updated_by)
                values (:id, :tenant, :kind, :name, :subject, :body, :active, :user)""")
            .param("id", id).param("tenant", TenantContext.require()).param("kind", c.kind()).param("name", c.name())
            .param("subject", c.subject()).param("body", c.body()).param("active", c.active()).param("user", user).update();
        audit.record(user, "LETTER_TEMPLATE_CREATED", "letter_template", id, Map.of("name", c.name()));
        return template(id);
    }

    @Transactional
    Template updateTemplate(UUID user, UUID id, TemplateInput in) {
        TemplateInput c = checkTemplate(in);
        int n = jdbc.sql("""
                update letter_template set kind = :kind, name = :name, subject = :subject, body = :body, active = :active,
                       updated_at = now(), updated_by = :user
                where id = :id""")
            .param("kind", c.kind()).param("name", c.name()).param("subject", c.subject()).param("body", c.body())
            .param("active", c.active()).param("user", user).param("id", id).update();
        if (n == 0) throw templateNotFound();
        audit.record(user, "LETTER_TEMPLATE_UPDATED", "letter_template", id, Map.of("name", c.name()));
        return template(id);
    }

    @Transactional
    void deleteTemplate(UUID user, UUID id) {
        int n = jdbc.sql("delete from letter_template where id = :id").param("id", id).update();
        if (n == 0) throw templateNotFound();
        audit.record(user, "LETTER_TEMPLATE_DELETED", "letter_template", id, Map.of());
    }

    private Template template(UUID id) {
        return jdbc.sql("select id, kind, name, subject, body, active, updated_at from letter_template where id = :id")
            .param("id", id).query(TemplateRow.class).optional().map(Letters::toTemplate).orElseThrow(Letters::templateNotFound);
    }

    // ---------- issuing ----------

    /** The letter as it would be issued, without saving it. */
    @Transactional(readOnly = true)
    byte[] preview(IssueInput in) {
        return draw(in).pdf();
    }

    @Transactional
    Letter issue(UUID user, IssueInput in) {
        Drawn d = draw(in);
        FileStore.FileMeta meta = files.store(user, d.template().name() + " - " + d.person().employeeNo() + ".pdf", d.pdf());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into letter (id, tenant_id, employee_id, template_id, kind, subject, file_id, field_values, issued_by)
                values (:id, :tenant, :emp, :template, :kind, :subject, :file, cast(:values as jsonb), :user)""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", d.person().id()).param("template", d.template().id())
            .param("kind", d.template().kind()).param("subject", d.subject()).param("file", meta.id())
            .param("values", json.writeValueAsString(d.values())).param("user", user).update();
        notifications.toEmployee(d.person().id(), new Notifications.Message(Notifications.Kind.LETTER_ISSUED,
            "You have a new letter: " + d.subject(), "Open it from News → My letters.", "/app/news?tab=letters", "purple"));
        audit.record(user, "LETTER_ISSUED", "letter", id, Map.of("employee", d.person().employeeNo(), "template", d.template().name()));
        return letter(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    List<Letter> mine(UUID me) {
        return jdbc.sql(LETTER_SELECT + "where l.employee_id = :me and l.withdrawn_at is null order by l.issued_at desc")
            .param("me", me).query(Letter.class).list();
    }

    @Transactional(readOnly = true)
    List<Letter> all() {
        return jdbc.sql(LETTER_SELECT + "order by l.issued_at desc limit 500").query(Letter.class).list();
    }

    /** The PDF, for its owner (counted as opened) or for HR. */
    @Transactional
    FileStore.StoredFile file(UUID me, UUID id, boolean hr) {
        record Row(UUID employeeId, UUID fileId, Instant withdrawnAt) {
        }
        Row r = jdbc.sql("select employee_id, file_id, withdrawn_at from letter where id = :id").param("id", id).query(Row.class)
            .optional().orElseThrow(Letters::letterNotFound);
        boolean owner = r.employeeId().equals(me) && r.withdrawnAt() == null;
        if (!owner && !hr) throw letterNotFound();
        if (owner) jdbc.sql("update letter set first_viewed_at = coalesce(first_viewed_at, now()) where id = :id").param("id", id).update();
        return files.read(r.fileId()).orElseThrow(Letters::letterNotFound);
    }

    @Transactional
    void withdraw(UUID user, UUID id) {
        int n = jdbc.sql("update letter set withdrawn_at = now() where id = :id and withdrawn_at is null").param("id", id).update();
        if (n == 0) throw letterNotFound();
        audit.record(user, "LETTER_WITHDRAWN", "letter", id, Map.of());
    }

    // ---------- drawing ----------

    private record Drawn(Template template, Person person, String subject, Map<String, String> values, byte[] pdf) {
    }

    private Drawn draw(IssueInput in) {
        if (in == null || in.templateId() == null) throw bad("TEMPLATE_REQUIRED", "Choose a template.");
        if (in.employeeId() == null) throw bad("EMPLOYEE_REQUIRED", "Choose who the letter is for.");
        Template t = template(in.templateId());
        Person p = jdbc.sql("""
                select e.id, e.employee_no, e.full_name, e.job_title, d.name as department, l.name as entity, e.join_date, e.confirmed_on,
                       e.last_day
                from employee e join legal_entity l on l.id = e.entity_id left join department d on d.id = e.department_id
                where e.id = :id""").param("id", in.employeeId()).query(Person.class).optional()
            .orElseThrow(() -> bad("NO_EMPLOYEE", "That person isn't in the company."));
        LetterheadView head = letterhead();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", p.fullName());
        values.put("firstName", p.fullName().split("\\s+")[0]);
        values.put("employeeNo", p.employeeNo());
        values.put("position", p.jobTitle() == null ? "" : p.jobTitle());
        values.put("department", p.department() == null ? "" : p.department());
        values.put("company", head.companyName() == null ? p.entity() : head.companyName());
        values.put("joinDate", p.joinDate() == null ? "" : p.joinDate().format(DATE));
        values.put("confirmationDate", p.confirmedOn() == null ? "" : p.confirmedOn().format(DATE));
        values.put("lastDay", p.lastDay() == null ? "" : p.lastDay().format(DATE));
        values.put("date", LocalDate.now().format(DATE));
        List<String> missing = new java.util.ArrayList<>();
        for (String field : t.fields()) {
            String v = in.values() == null ? null : in.values().get(field);
            if (v == null || v.isBlank()) missing.add(field);
            else if (v.length() > 500) throw bad("VALUE_TOO_LONG", "Keep " + field + " under 500 characters.");
            else values.put(field, v.strip());
        }
        if (!missing.isEmpty()) throw bad("VALUES_REQUIRED", "Fill in: " + String.join(", ", missing) + ".").with("fields", missing);
        String subject = fill(t.subject(), values);
        String body = fill(t.body(), values);
        byte[] logo = head.logo() == null ? null : files.read(head.logo().id()).map(FileStore.StoredFile::data).orElse(null);
        byte[] pdf = LetterPdf.render(new LetterPdf.Letterhead(head.companyName() == null ? p.entity() : head.companyName(), head.address(),
                head.phone(), head.email(), head.website(), head.registrationNo(), logo, head.signatoryName(), head.signatoryTitle(), head.footer()),
            new LetterPdf.Content(values.get("date"), List.of(p.fullName(), values.get("position"), values.get("department")), subject, body));
        return new Drawn(t, p, subject, values, pdf);
    }

    /** Replaces {{field}} with its value; unknown placeholders are left as they are. */
    static String fill(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The placeholders a template uses that aren't filled in automatically. */
    static List<String> fields(String... texts) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : texts) {
            Matcher m = PLACEHOLDER.matcher(t == null ? "" : t);
            while (m.find()) if (!AUTO_FIELDS.contains(m.group(1))) out.add(m.group(1));
        }
        return List.copyOf(out);
    }

    // ---------- helpers ----------

    private Optional<Letter> letter(UUID id) {
        return jdbc.sql(LETTER_SELECT + "where l.id = :id").param("id", id).query(Letter.class).optional();
    }

    private static Template toTemplate(TemplateRow r) {
        return new Template(r.id(), r.kind(), r.name(), r.subject(), r.body(), r.active(), fields(r.subject(), r.body()), r.updatedAt());
    }

    private static TemplateInput checkTemplate(TemplateInput in) {
        if (in == null) throw bad("TEMPLATE_REQUIRED", "Fill in the template.");
        String kind = in.kind() == null ? "OTHER" : in.kind();
        if (!KINDS.contains(kind)) throw bad("BAD_KIND", "Choose what kind of letter it is.");
        String name = limit(in.name(), 100);
        if (name == null) throw bad("NAME_REQUIRED", "Name the template.");
        String subject = limit(in.subject(), 200);
        if (subject == null) throw bad("SUBJECT_REQUIRED", "Give the letter a subject.");
        String body = in.body() == null ? "" : in.body().strip();
        if (body.isEmpty()) throw bad("BODY_REQUIRED", "Write the letter.");
        if (body.length() > 20_000) throw bad("BODY_TOO_LONG", "Keep the letter under 20,000 characters.");
        return new TemplateInput(kind, name, subject, body, !Boolean.FALSE.equals(in.active()));
    }

    private void seedDefaults() {
        String[][] defaults = {
            {"OFFER", "Offer letter", "Offer of employment as {{position}}", """
                Dear {{name}},

                We are pleased to offer you the position of {{position}} in the {{department}} department of {{company}}, starting on {{startDate}}.

                Your monthly basic salary will be {{salary}}. You will be on probation for {{probationMonths}} months from your start date, after which your performance will be reviewed for confirmation.

                Your other terms of employment, including working hours, leave and benefits, follow the company's policies as updated from time to time.

                Please confirm your acceptance by signing and returning a copy of this letter by {{replyBy}}.

                We look forward to welcoming you to the team."""},
            {"CONFIRMATION", "Confirmation of employment", "Confirmation of employment", """
                Dear {{name}},

                We are pleased to inform you that, following a review of your performance during your probation, your employment as {{position}} is confirmed with effect from {{confirmationDate}}.

                All other terms and conditions of your employment remain unchanged.

                Thank you for your contribution so far. We wish you continued success with {{company}}."""},
            {"INCREMENT", "Salary increment", "Salary increment", """
                Dear {{name}},

                In recognition of your performance and contribution, we are pleased to inform you that your monthly basic salary will be revised from {{currentSalary}} to {{newSalary}}, with effect from {{effectiveDate}}.

                All other terms and conditions of your employment remain unchanged.

                Thank you for your continued dedication to {{company}}."""},
        };
        for (String[] d : defaults) {
            jdbc.sql("insert into letter_template (id, tenant_id, kind, name, subject, body) values (:id, :tenant, :kind, :name, :subject, :body)")
                .param("id", UUID.randomUUID()).param("tenant", TenantContext.require()).param("kind", d[0]).param("name", d[1])
                .param("subject", d[2]).param("body", d[3].strip()).update();
        }
    }

    private static String limit(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        if (t.length() > max) throw bad("TOO_LONG", "Keep each field under " + max + " characters.");
        return t;
    }

    static ApiException templateNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "That template doesn't exist.");
    }

    static ApiException letterNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "LETTER_NOT_FOUND", "That letter doesn't exist.");
    }

    static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
