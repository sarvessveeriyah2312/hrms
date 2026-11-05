package com.hrms.claims;

import com.hrms.files.FileStore;
import com.hrms.notification.Notifications;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import com.hrms.workflow.ApprovalFlows;
import com.hrms.workflow.WorkflowService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense claims. Each claim is one expense: a receipt (amount typed in) or mileage (distance × the type's rate).
 * Claims go through the approval workflow; approved claims are marked paid by HR. Views of other people follow
 * the scope of claims:view.
 */
@Service
public class ClaimsService {

    public record ClaimType(UUID id, String code, String name, String tone, String kind, BigDecimal maxPerClaim,
                            BigDecimal maxPerMonth, BigDecimal ratePerKm, boolean receiptRequired, int approvalLevels,
                            boolean hrApproval, boolean active) {
        boolean mileage() {
            return "MILEAGE".equals(kind);
        }
    }

    public record ClaimInput(UUID claimTypeId, LocalDate expenseDate, String description, String merchant, BigDecimal amount,
                             BigDecimal distanceKm, String routeFrom, String routeTo, UUID receiptId) {
    }

    public record Receipt(UUID id, String name, String contentType, int sizeBytes) {
    }

    public record Claim(UUID id, UUID employeeId, String employeeName, String employeeNo, UUID claimTypeId, String typeName,
                        String tone, String kind, LocalDate expenseDate, String description, String merchant, BigDecimal amount,
                        String currency, BigDecimal distanceKm, String routeFrom, String routeTo, Receipt receipt, String status,
                        Instant createdAt, Instant decidedAt, UUID workflowId, WorkflowService.PendingWith pendingWith,
                        boolean canCancel, boolean canEdit, String returnedComment, Instant paidAt, String paidBy,
                        String paymentRef) {
    }

    public record ClaimDetail(Claim claim, List<WorkflowService.TimelineStep> timeline) {
    }

    /** Spending against a type's monthly limit. {@code left} is null when the type has no monthly limit. */
    public record Usage(UUID claimTypeId, String name, String tone, BigDecimal maxPerMonth, BigDecimal approved,
                        BigDecimal pending, BigDecimal left) {
    }

    /** Receipts older than this can't be claimed (most policies say 30–90 days). */
    static final int MAX_AGE_DAYS = 90;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");
    static final BigDecimal MAX_KM = new BigDecimal("5000");

    private final JdbcClient jdbc;
    private final WorkflowService workflow;
    private final FileStore files;
    private final Notifications notifications;
    private final ApprovalFlows flows;

    ClaimsService(JdbcClient jdbc, WorkflowService workflow, FileStore files, Notifications notifications, ApprovalFlows flows) {
        this.jdbc = jdbc;
        this.workflow = workflow;
        this.files = files;
        this.notifications = notifications;
        this.flows = flows;
    }

    // ---------- types ----------

    public List<ClaimType> types(boolean includeInactive) {
        return jdbc.sql("""
                select id, code, name, tone, kind, max_per_claim, max_per_month, rate_per_km, receipt_required,
                       approval_levels, hr_approval, active
                from claim_type where active or :all order by name""")
            .param("all", includeInactive).query(ClaimType.class).list();
    }

    ClaimType type(UUID id) {
        return jdbc.sql("""
                select id, code, name, tone, kind, max_per_claim, max_per_month, rate_per_km, receipt_required,
                       approval_levels, hr_approval, active
                from claim_type where id = :id""")
            .param("id", id).query(ClaimType.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_CLAIM_TYPE", "That claim type doesn't exist."));
    }

    /** The employee's currency, from their legal entity. */
    String currencyOf(UUID employeeId) {
        return jdbc.sql("select l.currency from employee e join legal_entity l on l.id = e.entity_id where e.id = :id")
            .param("id", employeeId).query(String.class).optional().map(String::trim).orElse("MYR");
    }

    /** "RM 46.80", "S$ 12.00"; plain code for other currencies. */
    static String money(BigDecimal amount, String currency) {
        String symbol = switch (currency == null ? "" : currency.trim()) {
            case "MYR" -> "RM ";
            case "SGD" -> "S$";
            case "HKD" -> "HK$";
            case "AUD" -> "A$";
            case "NZD" -> "NZ$";
            case "INR" -> "₹";
            default -> currency + " ";
        };
        return symbol + new DecimalFormat("#,##0.00").format(amount);
    }

    // ---------- the rules a claim must pass ----------

    private record Checked(ClaimType type, BigDecimal amount, BigDecimal km, String currency) {
    }

    private Checked check(UUID employeeId, ClaimInput in, UUID editing, UUID uploader) {
        if (in.claimTypeId() == null || in.expenseDate() == null || blank(in.description())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Choose a claim type, the date and say what it was for.");
        }
        ClaimType type = type(in.claimTypeId());
        if (!type.active()) throw new ApiException(HttpStatus.BAD_REQUEST, "CLAIM_TYPE_INACTIVE", type.name() + " can't be claimed any more.");
        LocalDate date = in.expenseDate();
        if (date.isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLAIM_IN_FUTURE", "The expense date can't be in the future.");
        }
        if (date.isBefore(LocalDate.now().minusDays(MAX_AGE_DAYS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLAIM_TOO_OLD",
                "Expenses can be claimed up to " + MAX_AGE_DAYS + " days after the date. Ask HR about older ones.");
        }
        if (in.description().trim().length() > 300) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DESCRIPTION_TOO_LONG", "Keep the description under 300 characters.");
        }

        BigDecimal amount;
        BigDecimal km = null;
        if (type.mileage()) {
            if (in.distanceKm() == null || in.distanceKm().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DISTANCE_REQUIRED", "Enter the distance driven, in km.");
            }
            if (in.distanceKm().compareTo(MAX_KM) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DISTANCE_TOO_LONG", "That's more than " + MAX_KM + " km. Split it into separate trips.");
            }
            km = in.distanceKm().setScale(1, RoundingMode.HALF_UP);
            amount = km.multiply(type.ratePerKm()).setScale(2, RoundingMode.HALF_UP);
        } else {
            if (in.amount() == null || in.amount().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_REQUIRED", "Enter the amount on the receipt.");
            }
            if (in.amount().scale() > 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_PRECISION", "Use at most two decimal places.");
            }
            if (in.amount().compareTo(MAX_AMOUNT) >= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_TOO_LARGE", "That amount is too large for one claim.");
            }
            amount = in.amount().setScale(2, RoundingMode.HALF_UP);
        }
        String currency = currencyOf(employeeId);
        if (amount.signum() <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_REQUIRED", "The amount works out to zero.");

        if (type.maxPerClaim() != null && amount.compareTo(type.maxPerClaim()) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CLAIM_OVER_LIMIT",
                type.name() + " is limited to " + money(type.maxPerClaim(), currency) + " per claim, and this is "
                    + money(amount, currency) + ".")
                .with("limit", type.maxPerClaim()).with("amount", amount);
        }
        checkReceipt(type, in.receiptId(), editing, uploader);

        // One claim at a time per person, so two quick submissions can't both pass the monthly limit.
        jdbc.sql("select id from employee where id = :id for update").param("id", employeeId).query(UUID.class).single();
        if (type.maxPerMonth() != null) {
            BigDecimal spent = spentInMonth(employeeId, type.id(), YearMonth.from(date), editing);
            BigDecimal left = type.maxPerMonth().subtract(spent).max(BigDecimal.ZERO);
            if (amount.compareTo(left) > 0) {
                String month = YearMonth.from(date).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CLAIM_MONTH_LIMIT",
                    "You have " + money(left, currency) + " of " + type.name().toLowerCase() + " left for " + month
                        + " (limit " + money(type.maxPerMonth(), currency) + "), and this is " + money(amount, currency) + ".")
                    .with("left", left).with("amount", amount);
            }
        }
        return new Checked(type, amount, km, currency);
    }

    private BigDecimal spentInMonth(UUID employeeId, UUID typeId, YearMonth month, UUID exclude) {
        return jdbc.sql("""
                select coalesce(sum(amount), 0) from claim
                where employee_id = :emp and claim_type_id = :type and status in ('PENDING', 'APPROVED')
                  and expense_date between :from and :to and id <> :exclude""")
            .param("emp", employeeId).param("type", typeId).param("from", month.atDay(1)).param("to", month.atEndOfMonth())
            .param("exclude", exclude == null ? new UUID(0, 0) : exclude).query(BigDecimal.class).single();
    }

    private void checkReceipt(ClaimType type, UUID receiptId, UUID editing, UUID uploader) {
        if (receiptId == null) {
            if (!type.mileage() && type.receiptRequired()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RECEIPT_REQUIRED", type.name() + " needs a photo or PDF of the receipt.");
            }
            return;
        }
        var meta = files.meta(receiptId).orElseThrow(() ->
            new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FILE", "That file doesn't exist. Upload it again."));
        if (!meta.ownerUserId().equals(uploader)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_FILE", "You can only attach files you uploaded.");
        }
        boolean used = jdbc.sql("select exists (select 1 from claim where receipt_id = :file and id <> :editing)")
            .param("file", receiptId).param("editing", editing == null ? new UUID(0, 0) : editing)
            .query(Boolean.class).single();
        if (used) throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE", "That receipt is already on another claim.");
    }

    // ---------- submitting, editing, withdrawing ----------

    /** The approval plan: the first matching configured workflow (by amount), else the claim type's own chain. */
    WorkflowService.Plan planFor(UUID employeeId, ClaimType type, BigDecimal amount) {
        UUID department = jdbc.sql("select department_id from employee where id = :id").param("id", employeeId)
            .query(UUID.class).optional().orElse(null);
        return flows.match(ClaimApprovals.SUBJECT, type.id(), department, amount)
            .orElseGet(() -> new WorkflowService.ChainSpec(type.approvalLevels(), type.hrApproval()).toPlan());
    }

    /** {@code fromClaimType}: no configured workflow matched, so the claim type's own chain applies. */
    record ApprovalPreview(String workflow, boolean fromClaimType, List<WorkflowService.PlannedStep> steps) {
    }

    /** Which workflow a claim would use and who would approve each step. Nothing is saved. */
    ApprovalPreview previewApproval(UUID employeeId, UUID claimTypeId, BigDecimal amount) {
        ClaimType type = type(claimTypeId);
        WorkflowService.Plan plan = planFor(employeeId, type, amount);
        return new ApprovalPreview(plan.flowName() != null ? plan.flowName() : type.name() + " (its own approval setting)",
            plan.flowId() == null, workflow.preview(plan, employeeId));
    }

    @Transactional
    public Claim submit(UUID employeeId, ClaimInput in) {
        UUID user = userOf(employeeId);
        Checked c = check(employeeId, in, null, user);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into claim (id, tenant_id, employee_id, claim_type_id, expense_date, description, merchant, amount, currency,
                                   distance_km, route_from, route_to, receipt_id, status)
                values (:id, :tenant, :emp, :type, :date, :desc, :merchant, :amount, :currency, :km, :from, :to, :receipt, 'PENDING')""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("type", c.type().id())
            .param("date", in.expenseDate()).param("desc", in.description().trim()).param("merchant", c.type().mileage() ? null : trim(in.merchant()))
            .param("amount", c.amount()).param("currency", c.currency()).param("km", c.km())
            .param("from", c.type().mileage() ? trim(in.routeFrom()) : null).param("to", c.type().mileage() ? trim(in.routeTo()) : null)
            .param("receipt", in.receiptId()).update();
        startApproval(id, employeeId, c.type(), c.amount());
        return find(id).orElseThrow();
    }

    /** A claim sent back for changes: the employee edits it and it starts a fresh approval chain. */
    @Transactional
    public Claim resubmit(UUID employeeId, UUID claimId, ClaimInput in) {
        Claim existing = find(claimId).orElseThrow(ClaimsService::notFound);
        if (!existing.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_CLAIM", "You can only change your own claims.");
        }
        if (!"RETURNED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_RETURNED", "Only a claim sent back for changes can be edited.");
        }
        ClaimInput effective = in.receiptId() == null && existing.receipt() != null && in.claimTypeId() != null
            && !type(in.claimTypeId()).mileage()
            ? new ClaimInput(in.claimTypeId(), in.expenseDate(), in.description(), in.merchant(), in.amount(), in.distanceKm(),
                in.routeFrom(), in.routeTo(), existing.receipt().id())
            : in;
        Checked c = check(employeeId, effective, claimId, userOf(employeeId));
        jdbc.sql("""
                update claim set claim_type_id = :type, expense_date = :date, description = :desc, merchant = :merchant,
                       amount = :amount, currency = :currency, distance_km = :km, route_from = :from, route_to = :to,
                       receipt_id = :receipt, status = 'PENDING', returned_comment = null, decided_at = null,
                       version = version + 1
                where id = :id""")
            .param("type", c.type().id()).param("date", effective.expenseDate()).param("desc", effective.description().trim())
            .param("merchant", c.type().mileage() ? null : trim(effective.merchant())).param("amount", c.amount())
            .param("currency", c.currency()).param("km", c.km())
            .param("from", c.type().mileage() ? trim(effective.routeFrom()) : null)
            .param("to", c.type().mileage() ? trim(effective.routeTo()) : null)
            .param("receipt", effective.receiptId()).param("id", claimId).update();
        startApproval(claimId, employeeId, c.type(), c.amount());
        return find(claimId).orElseThrow();
    }

    private void startApproval(UUID claimId, UUID employeeId, ClaimType type, BigDecimal amount) {
        UUID flow = workflow.start(ClaimApprovals.SUBJECT, claimId, employeeId, planFor(employeeId, type, amount));
        jdbc.sql("update claim set workflow_id = :flow where id = :id").param("flow", flow).param("id", claimId).update();
    }

    /** The employee withdraws a claim that is waiting or was sent back. */
    @Transactional
    public void cancel(UUID employeeId, UUID claimId) {
        Claim c = find(claimId).orElseThrow(ClaimsService::notFound);
        if (!c.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_CLAIM", "You can only withdraw your own claims.");
        }
        if (!c.canCancel()) throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL", "This claim has already been decided.");
        if ("PENDING".equals(c.status())) {
            workflow.cancel(c.workflowId(), employeeId); // marks the claim CANCELLED through ClaimApprovals
        } else {
            jdbc.sql("update claim set status = 'CANCELLED', decided_at = now(), version = version + 1 where id = :id")
                .param("id", claimId).update();
        }
    }

    /** HR marks approved claims as paid (by payroll or bank transfer). Each employee is told. */
    @Transactional
    public int markPaid(UUID actorUser, Collection<UUID> claimIds, String reference) {
        if (claimIds == null || claimIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Choose the claims to mark as paid.");
        }
        String ref = trim(reference);
        if (ref != null && ref.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REFERENCE_TOO_LONG", "Keep the payment reference under 100 characters.");
        }
        List<Claim> paid = new ArrayList<>();
        for (UUID id : claimIds) {
            Claim c = find(id).orElseThrow(ClaimsService::notFound);
            if (!"APPROVED".equals(c.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_APPROVED", "Only approved claims can be paid. " + c.description() + " is not approved.");
            }
            if (c.paidAt() != null) continue;
            jdbc.sql("""
                    update claim set paid_at = now(), paid_by = :by, payment_ref = :ref, version = version + 1
                    where id = :id and status = 'APPROVED' and paid_at is null""")
                .param("by", actorUser).param("ref", ref).param("id", id).update();
            paid.add(c);
        }
        for (Claim c : paid) {
            notifications.toEmployee(c.employeeId(), new Notifications.Message(Notifications.Kind.CLAIM_PAID,
                "Your " + lowerFirst(c.typeName()) + " claim of " + money(c.amount(), c.currency()) + " was paid",
                c.description() + (ref == null ? "" : " · Ref " + ref), "/app/claims?claim=" + c.id(), "green"));
        }
        return paid.size();
    }

    // ---------- reading ----------

    private static final String SELECT = """
        select c.id, c.employee_id, e.full_name as employee_name, e.employee_no, c.claim_type_id, t.name as type_name, t.tone,
               t.kind, c.expense_date, c.description, c.merchant, c.amount, c.currency, c.distance_km, c.route_from, c.route_to,
               c.receipt_id, f.name as receipt_name, f.content_type as receipt_type, f.size_bytes as receipt_size,
               c.status, c.created_at, c.decided_at, c.workflow_id, c.returned_comment, c.paid_at, pb.full_name as paid_by,
               c.payment_ref
        from claim c
        join claim_type t on t.id = c.claim_type_id
        join employee e on e.id = c.employee_id
        left join file_object f on f.id = c.receipt_id
        left join employee pb on pb.user_id = c.paid_by
        """;

    private record Row(UUID id, UUID employeeId, String employeeName, String employeeNo, UUID claimTypeId, String typeName,
                       String tone, String kind, LocalDate expenseDate, String description, String merchant, BigDecimal amount,
                       String currency, BigDecimal distanceKm, String routeFrom, String routeTo, UUID receiptId,
                       String receiptName, String receiptType, Integer receiptSize, String status, Instant createdAt,
                       Instant decidedAt, UUID workflowId, String returnedComment, Instant paidAt, String paidBy, String paymentRef) {
    }

    private Claim toClaim(Row r) {
        var pending = "PENDING".equals(r.status()) && r.workflowId() != null ? workflow.pendingWith(r.workflowId()).orElse(null) : null;
        Receipt receipt = r.receiptId() == null ? null
            : new Receipt(r.receiptId(), r.receiptName(), r.receiptType(), r.receiptSize() == null ? 0 : r.receiptSize());
        boolean open = "PENDING".equals(r.status()) || "RETURNED".equals(r.status());
        return new Claim(r.id(), r.employeeId(), r.employeeName(), r.employeeNo(), r.claimTypeId(), r.typeName(), r.tone(),
            r.kind(), r.expenseDate(), r.description(), r.merchant(), r.amount(), r.currency().trim(), r.distanceKm(),
            r.routeFrom(), r.routeTo(), receipt, r.status(), r.createdAt(), r.decidedAt(), r.workflowId(), pending, open,
            "RETURNED".equals(r.status()), r.returnedComment(), r.paidAt(), r.paidBy(), r.paymentRef());
    }

    Optional<Claim> find(UUID id) {
        return jdbc.sql(SELECT + "where c.id = :id").param("id", id).query(Row.class).optional().map(this::toClaim);
    }

    List<Claim> mine(UUID employeeId, int year) {
        return jdbc.sql(SELECT + "where c.employee_id = :emp and extract(year from c.expense_date) = :year order by c.expense_date desc, c.created_at desc")
            .param("emp", employeeId).param("year", year).query(Row.class).list().stream().map(this::toClaim).toList();
    }

    /**
     * Other people's claims within the caller's claims:view scope. {@code status}: PENDING, APPROVED, UNPAID
     * (approved, not paid yet), PAID, or null for every status except withdrawn ones.
     */
    List<Claim> others(UUID me, DataScope scope, String status, LocalDate from, LocalDate to) {
        String filter = switch (status == null ? "" : status) {
            case "PENDING" -> "c.status = 'PENDING'";
            case "APPROVED" -> "c.status = 'APPROVED'";
            case "UNPAID" -> "c.status = 'APPROVED' and c.paid_at is null";
            case "PAID" -> "c.paid_at is not null";
            case "REJECTED" -> "c.status = 'REJECTED'";
            default -> "c.status <> 'CANCELLED'";
        };
        return jdbc.sql(SELECT + """
                where %s and c.employee_id <> coalesce(:me, '00000000-0000-0000-0000-000000000000'::uuid)
                  and %s and c.expense_date between :from and :to
                order by c.expense_date desc, e.full_name limit 500""".formatted(EmployeeScope.predicate(scope, "e"), filter))
            .param("me", me).param("from", from).param("to", to).query(Row.class).list().stream().map(this::toClaim).toList();
    }

    /** Approved claims with an expense date in the month (for the payroll export), everyone in the company. */
    List<Claim> approvedIn(YearMonth month) {
        return jdbc.sql(SELECT + """
                where c.status = 'APPROVED' and c.expense_date between :from and :to
                order by e.full_name, c.expense_date""")
            .param("from", month.atDay(1)).param("to", month.atEndOfMonth()).query(Row.class).list().stream().map(this::toClaim).toList();
    }

    boolean canSee(UUID me, DataScope scope, UUID employeeId) {
        if (employeeId.equals(me)) return true;
        return jdbc.sql("select exists (select 1 from employee e where e.id = :target and %s)".formatted(EmployeeScope.predicate(scope, "e")))
            .param("target", employeeId).param("me", me).query(Boolean.class).single();
    }

    ClaimDetail detail(UUID id) {
        Claim c = find(id).orElseThrow(ClaimsService::notFound);
        return new ClaimDetail(c, c.workflowId() == null ? List.of() : workflow.timeline(c.workflowId()));
    }

    /** Spending this month against each active type's monthly limit. */
    List<Usage> usage(UUID employeeId, YearMonth month) {
        record Spent(UUID claimTypeId, BigDecimal approved, BigDecimal pending) {
        }
        List<Spent> spent = jdbc.sql("""
                select claim_type_id,
                       coalesce(sum(amount) filter (where status = 'APPROVED'), 0) as approved,
                       coalesce(sum(amount) filter (where status = 'PENDING'), 0) as pending
                from claim where employee_id = :emp and expense_date between :from and :to
                group by claim_type_id""")
            .param("emp", employeeId).param("from", month.atDay(1)).param("to", month.atEndOfMonth()).query(Spent.class).list();
        return types(false).stream().map(t -> {
            Spent s = spent.stream().filter(x -> x.claimTypeId().equals(t.id())).findFirst()
                .orElse(new Spent(t.id(), BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal left = t.maxPerMonth() == null ? null : t.maxPerMonth().subtract(s.approved()).subtract(s.pending()).max(BigDecimal.ZERO);
            return new Usage(t.id(), t.name(), t.tone(), t.maxPerMonth(), s.approved(), s.pending(), left);
        }).toList();
    }

    // ---------- helpers ----------

    private UUID userOf(UUID employeeId) {
        return jdbc.sql("select user_id from employee where id = :id").param("id", employeeId).query(UUID.class).optional().orElse(null);
    }

    static String lowerFirst(String s) {
        return s == null || s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND", "That claim doesn't exist.");
    }
}
