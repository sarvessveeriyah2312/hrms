package com.hrms.leave;

import com.hrms.files.FileStore;
import com.hrms.leave.LeaveDays.DayPart;
import com.hrms.notification.Notifications;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import com.hrms.workflow.ApprovalFlows;
import com.hrms.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave balances and requests. Employees apply for themselves (the employee comes from the token); HR can
 * record leave for someone else. Requests go through the approval workflow unless HR records them as
 * already approved. Views of other people follow the scope of leave:view.
 */
@Service
public class LeaveService {

    public record LeaveType(UUID id, String code, String name, String tone, boolean paid, boolean trackBalance,
                            BigDecimal defaultDays, boolean halfDayAllowed, int approvalLevels, boolean hrApproval,
                            boolean active, boolean proRate, BigDecimal carryMax, List<EntitlementPolicy.Tier> serviceTiers,
                            Integer carryExpiryMonth, boolean requiresAttachment) {
    }

    /**
     * {@code source}: POLICY (default, service tier or pro-rating) or MANUAL (set by HR for this person).
     * {@code basis} explains the entitled figure in words; {@code carriedSource} is MANUAL, YEAR_END or null.
     * {@code carried} is what still counts; {@code carriedExpired} what was lost on {@code carriedExpiresOn}.
     * {@code earned}: leave earned this year on top of the entitlement (e.g. time off for overtime) that still counts;
     * {@code earnedExpired}: earned leave that expired unused.
     */
    public record Balance(UUID leaveTypeId, String name, String tone, boolean trackBalance, BigDecimal entitled,
                          BigDecimal carried, BigDecimal used, BigDecimal pending, BigDecimal available,
                          String source, String basis, BigDecimal policyDays, String carriedSource, String note,
                          BigDecimal carriedExpired, LocalDate carriedExpiresOn, BigDecimal planned, BigDecimal earned,
                          BigDecimal earnedExpired) {
    }

    public record Preview(BigDecimal days, List<Holiday> holidays, List<Integer> workDays) {
    }

    public record Holiday(UUID id, LocalDate date, String name) {
    }

    public record ApplyInput(UUID leaveTypeId, LocalDate startDate, LocalDate endDate, String dayPart, String reason,
                             UUID attachmentId) {
    }

    /** HR recording leave for someone: who recorded it, and whether it skips approval. */
    record OnBehalf(UUID recordedByUser, boolean approveNow) {
    }

    public record Attachment(UUID id, String name, String contentType, int sizeBytes) {
    }

    public record Request(UUID id, UUID employeeId, String employeeName, UUID leaveTypeId, String typeName, String tone,
                          LocalDate startDate, LocalDate endDate, String dayPart, BigDecimal days, String reason,
                          String status, Instant createdAt, Instant decidedAt, UUID workflowId,
                          WorkflowService.PendingWith pendingWith, boolean canCancel, boolean canEdit,
                          Attachment attachment, String recordedBy, String returnedComment, String cancelReason) {
    }

    public record RequestDetail(Request request, List<WorkflowService.TimelineStep> timeline) {
    }

    /** How far back someone may apply (sick days are often entered after the fact). HR may go back further. */
    private static final int BACKDATE_DAYS = 30;
    private static final int HR_BACKDATE_DAYS = 366;

    private final JdbcClient jdbc;
    private final WorkflowService workflow;
    private final FileStore files;
    private final Notifications notifications;
    private final ApprovalFlows flows;

    LeaveService(JdbcClient jdbc, WorkflowService workflow, FileStore files, Notifications notifications, ApprovalFlows flows) {
        this.jdbc = jdbc;
        this.workflow = workflow;
        this.files = files;
        this.notifications = notifications;
        this.flows = flows;
    }

    /** The approval plan for a request: the first matching configured workflow, else the leave type's own chain. */
    WorkflowService.Plan planFor(UUID employeeId, LeaveType type, BigDecimal days) {
        UUID department = jdbc.sql("select department_id from employee where id = :id").param("id", employeeId)
            .query(UUID.class).optional().orElse(null);
        return flows.match(LeaveApprovals.SUBJECT, type.id(), department, days)
            .orElseGet(() -> new WorkflowService.ChainSpec(type.approvalLevels(), type.hrApproval()).toPlan());
    }

    record ApprovalPreview(String workflow, boolean fromLeaveType, List<WorkflowService.PlannedStep> steps) {
    }

    /** Which workflow a request would use and who would approve each step. Nothing is saved. */
    ApprovalPreview previewApproval(UUID employeeId, UUID leaveTypeId, BigDecimal days) {
        LeaveType type = type(leaveTypeId);
        WorkflowService.Plan plan = planFor(employeeId, type, days);
        return new ApprovalPreview(plan.flowName() != null ? plan.flowName() : type.name() + " (its own approval setting)",
            plan.flowId() == null, workflow.preview(plan, employeeId));
    }

    private String nameOfUser(UUID userId) {
        return jdbc.sql("select full_name from employee where user_id = :u").param("u", userId).query(String.class)
            .optional().orElse("HR");
    }

    /** "medical leave, 7–8 Dec" */
    private static String what(Request r) {
        return Character.toLowerCase(r.typeName().charAt(0)) + r.typeName().substring(1) + ", "
            + LeaveDays.range(r.startDate(), r.endDate(), DayPart.valueOf(r.dayPart()));
    }

    // ---------- types, holidays, working week ----------

    public List<LeaveType> types(boolean includeInactive) {
        record Row(UUID id, String code, String name, String tone, boolean paid, boolean trackBalance, BigDecimal defaultDays,
                   boolean halfDayAllowed, int approvalLevels, boolean hrApproval, boolean active, boolean proRate,
                   BigDecimal carryMax, Integer carryExpiryMonth, boolean requiresAttachment) {
        }
        record TierRow(UUID leaveTypeId, int minYears, BigDecimal days) {
        }
        List<TierRow> tiers = jdbc.sql("select leave_type_id, min_years, days from leave_service_tier order by min_years")
            .query(TierRow.class).list();
        return jdbc.sql("""
                select id, code, name, tone, paid, track_balance, default_days, half_day_allowed, approval_levels,
                       hr_approval, active, pro_rate, carry_max, carry_expiry_month, requires_attachment
                from leave_type where active or :all order by name""")
            .param("all", includeInactive).query(Row.class).list().stream()
            .map(r -> new LeaveType(r.id(), r.code(), r.name(), r.tone(), r.paid(), r.trackBalance(), r.defaultDays(),
                r.halfDayAllowed(), r.approvalLevels(), r.hrApproval(), r.active(), r.proRate(), r.carryMax(),
                tiers.stream().filter(t -> t.leaveTypeId().equals(r.id())).map(t -> new EntitlementPolicy.Tier(t.minYears(), t.days())).toList(),
                r.carryExpiryMonth(), r.requiresAttachment()))
            .toList();
    }

    LeaveType type(UUID id) {
        return types(true).stream().filter(t -> t.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_LEAVE_TYPE", "That leave type doesn't exist."));
    }

    List<Holiday> holidays(LocalDate from, LocalDate to) {
        return jdbc.sql("select id, date, name from public_holiday where date between :from and :to order by date")
            .param("from", from).param("to", to).query(Holiday.class).list();
    }

    private Set<LocalDate> holidaySet(LocalDate from, LocalDate to) {
        Set<LocalDate> set = new HashSet<>();
        holidays(from, to).forEach(h -> set.add(h.date()));
        return set;
    }

    /** The employee's working week, from their legal entity (Monday to Friday when not set). */
    Set<DayOfWeek> workDays(UUID employeeId) {
        // smallint[] comes back as Short[]; read the values as numbers.
        Object[] days = jdbc.sql("""
                select l.work_days from employee e join legal_entity l on l.id = e.entity_id where e.id = :emp""")
            .param("emp", employeeId)
            .query((rs, i) -> (Object[]) rs.getArray(1).getArray())
            .optional().orElse(null);
        if (days == null || days.length == 0) return LeaveDays.MONDAY_TO_FRIDAY;
        Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (Object d : days) set.add(DayOfWeek.of(((Number) d).intValue()));
        return set;
    }

    // ---------- balances ----------

    /** Balances for every active type, as of today. */
    public List<Balance> balances(UUID employeeId, int year) {
        return balances(employeeId, year, types(false), LocalDate.now(), null);
    }

    List<Balance> balances(UUID employeeId, int year, List<LeaveType> types) {
        return balances(employeeId, year, types, LocalDate.now(), null);
    }

    /**
     * {@code asOf} decides whether carried days have expired. {@code excludeRequest} leaves one request out of
     * the totals (used when re-checking a request that is being edited).
     */
    List<Balance> balances(UUID employeeId, int year, List<LeaveType> types, LocalDate asOf, UUID excludeRequest) {
        record Usage(UUID leaveTypeId, BigDecimal used, BigDecimal pending, BigDecimal planned) {
        }
        record Manual(UUID leaveTypeId, BigDecimal entitled, BigDecimal carried, String carriedSource, String note) {
        }
        record ByDate(UUID leaveTypeId, BigDecimal days) {
        }
        record Credit(UUID leaveTypeId, BigDecimal days, LocalDate expiresOn) {
        }
        LocalDate joinDate = jdbc.sql("select join_date from employee where id = :emp").param("emp", employeeId)
            .query(LocalDate.class).optional().orElse(null);
        UUID exclude = excludeRequest == null ? new UUID(0, 0) : excludeRequest;
        List<Usage> usage = jdbc.sql("""
                select leave_type_id,
                       coalesce(sum(days) filter (where status = 'APPROVED'), 0) as used,
                       coalesce(sum(days) filter (where status = 'PENDING'), 0) as pending,
                       coalesce(sum(days) filter (where status = 'DRAFT'), 0) as planned
                from leave_request where employee_id = :emp and extract(year from start_date) = :year and id <> :exclude
                group by leave_type_id""")
            .param("emp", employeeId).param("year", year).param("exclude", exclude).query(Usage.class).list();
        List<Credit> credits = jdbc.sql("""
                select leave_type_id, days, expires_on from leave_credit
                where employee_id = :emp and extract(year from earned_on) = :year""")
            .param("emp", employeeId).param("year", year).query(Credit.class).list();
        List<Manual> manual = jdbc.sql("""
                select leave_type_id, entitled, carried, carried_source, note from leave_entitlement
                where employee_id = :emp and year = :year""")
            .param("emp", employeeId).param("year", year).query(Manual.class).list();

        return types.stream()
            .sorted(java.util.Comparator.comparing((LeaveType t) -> !t.trackBalance()).thenComparing(LeaveType::name))
            .map(t -> {
                Usage u = usage.stream().filter(x -> x.leaveTypeId().equals(t.id())).findFirst()
                    .orElse(new Usage(t.id(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
                Manual m = manual.stream().filter(x -> x.leaveTypeId().equals(t.id())).findFirst().orElse(null);
                if (!t.trackBalance()) {
                    return new Balance(t.id(), t.name(), t.tone(), false, BigDecimal.ZERO, BigDecimal.ZERO, u.used(), u.pending(),
                        null, "POLICY", "No yearly limit", BigDecimal.ZERO, null, null, BigDecimal.ZERO, null, u.planned(),
                        BigDecimal.ZERO, BigDecimal.ZERO);
                }
                var policy = EntitlementPolicy.compute(t.defaultDays(), t.serviceTiers(), t.proRate(), joinDate, year);
                boolean overridden = m != null && m.entitled() != null;
                BigDecimal entitled = overridden ? m.entitled() : policy.days();
                BigDecimal carriedIn = m == null || m.carried() == null ? BigDecimal.ZERO : m.carried();

                BigDecimal usedByExpiry = BigDecimal.ZERO;
                if (t.carryExpiryMonth() != null && carriedIn.signum() > 0) {
                    LocalDate expiry = java.time.YearMonth.of(year, t.carryExpiryMonth()).atEndOfMonth();
                    usedByExpiry = jdbc.sql("""
                            select coalesce(sum(days), 0) from leave_request
                            where employee_id = :emp and leave_type_id = :type and status in ('APPROVED', 'PENDING')
                              and extract(year from start_date) = :year and start_date <= :expiry and id <> :exclude""")
                        .param("emp", employeeId).param("type", t.id()).param("year", year).param("expiry", expiry)
                        .param("exclude", exclude).query(BigDecimal.class).single();
                }
                var carried = EntitlementPolicy.carried(carriedIn, t.carryExpiryMonth(), year, usedByExpiry, asOf);
                // Earned leave (e.g. time off for overtime): what expired unused no longer counts.
                List<Credit> mine = credits.stream().filter(c -> c.leaveTypeId().equals(t.id())).toList();
                BigDecimal earnedTotal = mine.stream().map(Credit::days).reduce(BigDecimal.ZERO, BigDecimal::add);
                List<Credit> expired = mine.stream().filter(c -> c.expiresOn() != null && asOf.isAfter(c.expiresOn())).toList();
                BigDecimal usedByEarnedExpiry = BigDecimal.ZERO;
                if (!expired.isEmpty()) {
                    LocalDate latest = expired.stream().map(Credit::expiresOn).max(LocalDate::compareTo).orElseThrow();
                    usedByEarnedExpiry = jdbc.sql("""
                            select coalesce(sum(days), 0) from leave_request
                            where employee_id = :emp and leave_type_id = :type and status in ('APPROVED', 'PENDING')
                              and extract(year from start_date) = :year and start_date <= :expiry and id <> :exclude""")
                        .param("emp", employeeId).param("type", t.id()).param("year", year).param("expiry", latest)
                        .param("exclude", exclude).query(BigDecimal.class).single();
                }
                var earned = EntitlementPolicy.earned(earnedTotal,
                    expired.stream().map(Credit::days).reduce(BigDecimal.ZERO, BigDecimal::add), usedByEarnedExpiry, carried.usable());

                String basis = overridden ? "Set by HR" + (m.note() != null ? ": " + m.note() : "") : policy.basis();
                return new Balance(t.id(), t.name(), t.tone(), true, entitled, carried.usable(), u.used(), u.pending(),
                    entitled.add(carried.usable()).add(earned.usable()).subtract(u.used()).subtract(u.pending()),
                    overridden ? "MANUAL" : "POLICY", basis, policy.days(), m == null ? null : m.carriedSource(),
                    m == null ? null : m.note(), carried.expired(), carried.expiresOn(), u.planned(), earned.usable(), earned.expired());
            })
            .toList();
    }

    Preview preview(UUID employeeId, LocalDate start, LocalDate end, String dayPart) {
        DayPart part = parsePart(dayPart);
        Set<DayOfWeek> week = employeeId == null ? LeaveDays.MONDAY_TO_FRIDAY : workDays(employeeId);
        return new Preview(LeaveDays.count(start, end, part, week, holidaySet(start, end)), holidays(start, end),
            week.stream().map(DayOfWeek::getValue).sorted().toList());
    }

    // ---------- applying, editing, cancelling ----------

    private record Checked(LeaveType type, DayPart part, BigDecimal days) {
    }

    /** All the rules a request must pass, for a new request or an edited one ({@code editing} is left out of the totals). */
    private Checked check(UUID employeeId, ApplyInput in, UUID editing, boolean byHr, UUID uploader) {
        if (in.leaveTypeId() == null || in.startDate() == null || in.endDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Choose a leave type and the dates.");
        }
        LeaveType type = type(in.leaveTypeId());
        if (!type.active()) throw new ApiException(HttpStatus.BAD_REQUEST, "LEAVE_TYPE_INACTIVE", type.name() + " can't be applied for any more.");
        DayPart part = parsePart(in.dayPart());
        LocalDate start = in.startDate(), end = in.endDate();
        if (end.isBefore(start)) throw new ApiException(HttpStatus.BAD_REQUEST, "DATES_REVERSED", "The end date is before the start date.");
        if (start.getYear() != end.getYear()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEAVE_SPANS_YEARS", "Leave can't cross into a new year. Split it into two requests.");
        }
        int backdate = byHr ? HR_BACKDATE_DAYS : BACKDATE_DAYS;
        if (start.isBefore(LocalDate.now().minusDays(backdate))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEAVE_TOO_OLD", byHr
                ? "HR can record leave up to a year back."
                : "Leave can be entered up to " + BACKDATE_DAYS + " days back. Ask HR for older dates.");
        }
        if (part != DayPart.FULL && !start.equals(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HALF_DAY_ONE_DATE", "A half day must start and end on the same date.");
        }
        if (part != DayPart.FULL && !type.halfDayAllowed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HALF_DAY_NOT_ALLOWED", type.name() + " can only be taken in full days.");
        }
        BigDecimal days = LeaveDays.count(start, end, part, workDays(employeeId), holidaySet(start, end));
        if (days.signum() == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_WORKING_DAYS", "Those dates are all non-working days or public holidays, so there's nothing to take.");
        }
        checkAttachment(type, in.attachmentId(), editing, uploader);

        // One request at a time per person, so two quick submissions can't both pass the balance check.
        jdbc.sql("select id from employee where id = :id for update").param("id", employeeId).query(UUID.class).single();

        boolean overlaps = jdbc.sql("""
                select exists (select 1 from leave_request where employee_id = :emp and status in ('PENDING', 'APPROVED')
                               and id <> :editing and start_date <= :end and end_date >= :start
                               and (day_part = 'FULL' or :part = 'FULL' or day_part = :part))""")
            .param("emp", employeeId).param("editing", editing == null ? new UUID(0, 0) : editing)
            .param("start", start).param("end", end).param("part", part.name())
            .query(Boolean.class).single();
        if (overlaps) {
            throw new ApiException(HttpStatus.CONFLICT, "LEAVE_OVERLAP", byHr
                ? "They already have leave on some of these dates." : "You already have leave on some of these dates.");
        }

        if (type.trackBalance()) {
            // As of the start date, so carried days that will have expired by then aren't counted.
            LocalDate asOf = start.isAfter(LocalDate.now()) ? start : LocalDate.now();
            Balance balance = balances(employeeId, start.getYear(), List.of(type), asOf, editing).getFirst();
            if (balance.available().compareTo(days) < 0) {
                String who = byHr ? "They have " : "You have ";
                String expired = balance.carriedExpired().signum() > 0
                    ? " (" + LeaveDays.days(balance.carriedExpired()) + " carried over will have expired by then)" : "";
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LEAVE_INSUFFICIENT_BALANCE",
                    who + LeaveDays.days(balance.available().max(BigDecimal.ZERO)) + " of " + type.name().toLowerCase()
                        + " left" + expired + ", and this needs " + LeaveDays.days(days) + ".")
                    .with("available", balance.available()).with("requested", days);
            }
        }
        return new Checked(type, part, days);
    }

    private void checkAttachment(LeaveType type, UUID attachmentId, UUID editing, UUID uploader) {
        if (attachmentId == null) {
            if (type.requiresAttachment()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED",
                    type.name() + " needs a supporting document, such as a medical certificate.");
            }
            return;
        }
        var meta = files.meta(attachmentId).orElseThrow(() ->
            new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FILE", "That file doesn't exist. Upload it again."));
        if (!meta.ownerUserId().equals(uploader)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_FILE", "You can only attach files you uploaded.");
        }
        boolean used = jdbc.sql("select exists (select 1 from leave_request where attachment_id = :file and id <> :editing)")
            .param("file", attachmentId).param("editing", editing == null ? new UUID(0, 0) : editing)
            .query(Boolean.class).single();
        if (used) throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE", "That file is already attached to another request.");
    }

    /** An employee applying for themselves. */
    @Transactional
    public Request apply(UUID employeeId, ApplyInput in) {
        UUID user = jdbc.sql("select user_id from employee where id = :id").param("id", employeeId).query(UUID.class).optional().orElse(null);
        return apply(employeeId, in, user, null);
    }

    /** {@code uploaderUser} is who uploaded the attachment (the applicant, or HR when recording on behalf). */
    @Transactional
    Request apply(UUID employeeId, ApplyInput in, UUID uploaderUser, OnBehalf onBehalf) {
        boolean byHr = onBehalf != null;
        Checked c = check(employeeId, in, null, byHr, uploaderUser);
        boolean approveNow = byHr && onBehalf.approveNow();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into leave_request (id, tenant_id, employee_id, leave_type_id, start_date, end_date, day_part, days,
                                           reason, status, attachment_id, recorded_by, decided_at)
                values (:id, :tenant, :emp, :type, :start, :end, :part, :days, :reason, :status, :file, :by,
                        case when :approved then now() end)""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("type", c.type().id())
            .param("start", in.startDate()).param("end", in.endDate()).param("part", c.part().name()).param("days", c.days())
            .param("reason", blankToNull(in.reason())).param("status", approveNow ? "APPROVED" : "PENDING")
            .param("file", in.attachmentId()).param("by", byHr ? onBehalf.recordedByUser() : null).param("approved", approveNow)
            .update();
        if (!approveNow) startApproval(id, employeeId, c.type(), c.days());
        Request created = find(id).orElseThrow();
        if (byHr) {
            notifications.toEmployee(employeeId, new Notifications.Message(Notifications.Kind.LEAVE_RECORDED,
                nameOfUser(onBehalf.recordedByUser()) + " recorded " + what(created) + " for you",
                LeaveDays.days(created.days()) + (approveNow ? " · approved" : " · waiting for approval"),
                "/app/leave?request=" + id, created.tone()));
        }
        return created;
    }

    private void startApproval(UUID requestId, UUID employeeId, LeaveType type, BigDecimal days) {
        UUID flow = workflow.start(LeaveApprovals.SUBJECT, requestId, employeeId, planFor(employeeId, type, days));
        jdbc.sql("update leave_request set workflow_id = :flow where id = :id").param("flow", flow).param("id", requestId).update();
    }

    /** A request sent back for changes: the employee edits it and it starts a fresh approval chain. */
    @Transactional
    public Request resubmit(UUID employeeId, UUID requestId, ApplyInput in) {
        Request r = find(requestId).orElseThrow(LeaveService::notFound);
        if (!r.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_REQUEST", "You can only change your own leave.");
        }
        if (!"RETURNED".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_RETURNED", "Only a request sent back for changes can be edited.");
        }
        UUID user = jdbc.sql("select user_id from employee where id = :id").param("id", employeeId).query(UUID.class).single();
        // Keep the same attachment unless a new one is given.
        ApplyInput effective = in.attachmentId() == null && r.attachment() != null
            ? new ApplyInput(in.leaveTypeId(), in.startDate(), in.endDate(), in.dayPart(), in.reason(), r.attachment().id())
            : in;
        // The attachment kept from before counts as theirs even if HR uploaded it when recording.
        boolean keptAttachment = r.attachment() != null && r.attachment().id().equals(effective.attachmentId());
        Checked c = check(employeeId, effective, requestId, false, keptAttachment ? ownerOf(r.attachment().id()) : user);
        jdbc.sql("""
                update leave_request set leave_type_id = :type, start_date = :start, end_date = :end, day_part = :part, days = :days,
                       reason = :reason, attachment_id = :file, status = 'PENDING', returned_comment = null, decided_at = null,
                       version = version + 1
                where id = :id""")
            .param("type", c.type().id()).param("start", effective.startDate()).param("end", effective.endDate())
            .param("part", c.part().name()).param("days", c.days()).param("reason", blankToNull(effective.reason()))
            .param("file", effective.attachmentId()).param("id", requestId).update();
        startApproval(requestId, employeeId, c.type(), c.days());
        return find(requestId).orElseThrow();
    }

    private UUID ownerOf(UUID fileId) {
        return files.meta(fileId).map(FileStore.FileMeta::ownerUserId).orElse(null);
    }

    // ---------- planner (drafts) ----------

    /**
     * A planned leave: not sent for approval and not counted in balances. Only the basics are checked here
     * (dates, half days, no clash with other leave or plans); balance and documents are checked on submit.
     */
    @Transactional
    public Request saveDraft(UUID employeeId, UUID draftId, ApplyInput in) {
        if (in.leaveTypeId() == null || in.startDate() == null || in.endDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Choose a leave type and the dates.");
        }
        if (draftId != null) requireDraft(employeeId, draftId);
        LeaveType type = type(in.leaveTypeId());
        if (!type.active()) throw new ApiException(HttpStatus.BAD_REQUEST, "LEAVE_TYPE_INACTIVE", type.name() + " can't be planned any more.");
        DayPart part = parsePart(in.dayPart());
        LocalDate start = in.startDate(), end = in.endDate();
        if (end.isBefore(start)) throw new ApiException(HttpStatus.BAD_REQUEST, "DATES_REVERSED", "The end date is before the start date.");
        if (start.getYear() != end.getYear()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEAVE_SPANS_YEARS", "Leave can't cross into a new year. Plan it as two pieces.");
        }
        if (start.isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAN_IN_PAST", "Plan leave from today onwards. For past days, apply instead.");
        }
        if (part != DayPart.FULL && !start.equals(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HALF_DAY_ONE_DATE", "A half day must start and end on the same date.");
        }
        if (part != DayPart.FULL && !type.halfDayAllowed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HALF_DAY_NOT_ALLOWED", type.name() + " can only be taken in full days.");
        }
        BigDecimal days = LeaveDays.count(start, end, part, workDays(employeeId), holidaySet(start, end));
        if (days.signum() == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_WORKING_DAYS", "Those dates are all non-working days or public holidays.");
        }
        boolean clash = jdbc.sql("""
                select exists (select 1 from leave_request where employee_id = :emp and status in ('DRAFT', 'PENDING', 'APPROVED')
                               and id <> :self and start_date <= :end and end_date >= :start
                               and (day_part = 'FULL' or :part = 'FULL' or day_part = :part))""")
            .param("emp", employeeId).param("self", draftId == null ? new UUID(0, 0) : draftId)
            .param("start", start).param("end", end).param("part", part.name()).query(Boolean.class).single();
        if (clash) throw new ApiException(HttpStatus.CONFLICT, "LEAVE_OVERLAP", "You already have leave or a plan on some of these dates.");

        if (draftId == null) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    insert into leave_request (id, tenant_id, employee_id, leave_type_id, start_date, end_date, day_part, days, reason, status)
                    values (:id, :tenant, :emp, :type, :start, :end, :part, :days, :reason, 'DRAFT')""")
                .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("type", type.id())
                .param("start", start).param("end", end).param("part", part.name()).param("days", days)
                .param("reason", blankToNull(in.reason())).update();
            return find(id).orElseThrow();
        }
        jdbc.sql("""
                update leave_request set leave_type_id = :type, start_date = :start, end_date = :end, day_part = :part,
                       days = :days, reason = :reason, version = version + 1
                where id = :id""")
            .param("type", type.id()).param("start", start).param("end", end).param("part", part.name()).param("days", days)
            .param("reason", blankToNull(in.reason())).param("id", draftId).update();
        return find(draftId).orElseThrow();
    }

    @Transactional
    public void deleteDraft(UUID employeeId, UUID draftId) {
        requireDraft(employeeId, draftId);
        jdbc.sql("delete from leave_request where id = :id and status = 'DRAFT'").param("id", draftId).update();
    }

    /** Sends a planned leave for approval, with every check an application gets (balance, overlaps, documents). */
    @Transactional
    public Request submitDraft(UUID employeeId, UUID draftId, UUID attachmentId) {
        Request d = requireDraft(employeeId, draftId);
        UUID user = jdbc.sql("select user_id from employee where id = :id").param("id", employeeId).query(UUID.class).single();
        ApplyInput in = new ApplyInput(d.leaveTypeId(), d.startDate(), d.endDate(), d.dayPart(), d.reason(), attachmentId);
        if (d.startDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAN_IN_PAST", "This plan is in the past now. Change the dates or delete it.");
        }
        Checked c = check(employeeId, in, draftId, false, user);
        jdbc.sql("""
                update leave_request set status = 'PENDING', days = :days, attachment_id = :file, created_at = now(),
                       version = version + 1
                where id = :id""")
            .param("days", c.days()).param("file", attachmentId).param("id", draftId).update();
        startApproval(draftId, employeeId, c.type(), c.days());
        return find(draftId).orElseThrow();
    }

    List<Request> drafts(UUID employeeId) {
        return jdbc.sql(SELECT_REQUEST + "where r.employee_id = :emp and r.status = 'DRAFT' order by r.start_date")
            .param("emp", employeeId).query(Row.class).list().stream().map(this::toRequest).toList();
    }

    private Request requireDraft(UUID employeeId, UUID draftId) {
        Request d = find(draftId).orElseThrow(LeaveService::notFound);
        if (!d.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_REQUEST", "You can only change your own plans.");
        }
        if (!"DRAFT".equals(d.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_A_DRAFT", "This leave has already been submitted.");
        }
        return d;
    }

    /** The employee withdraws a pending or returned request, or cancels approved leave before it starts. */
    @Transactional
    public void cancel(UUID employeeId, UUID requestId) {
        Request r = find(requestId).orElseThrow(LeaveService::notFound);
        if (!r.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_REQUEST", "You can only cancel your own leave.");
        }
        if (!r.canCancel()) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL",
                "APPROVED".equals(r.status()) ? "This leave has already started. Ask HR to change it." : "This request is already closed.");
        }
        if ("PENDING".equals(r.status())) {
            workflow.cancel(r.workflowId(), employeeId); // marks the request CANCELLED through LeaveApprovals
        } else {
            jdbc.sql("update leave_request set status = 'CANCELLED', decided_at = now(), version = version + 1 where id = :id")
                .param("id", requestId).update();
        }
    }

    /** HR cancels approved (or returned) leave, even after it has started, with a reason the employee sees. */
    @Transactional
    void cancelByHr(UUID actorUser, UUID requestId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Say why you're cancelling it; they'll see this.");
        }
        Request r = find(requestId).orElseThrow(LeaveService::notFound);
        if (!"APPROVED".equals(r.status()) && !"RETURNED".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL", "PENDING".equals(r.status())
                ? "It's still waiting for approval. Reject it from Approvals instead." : "This request is already closed.");
        }
        jdbc.sql("""
                update leave_request set status = 'CANCELLED', decided_at = now(), cancel_reason = :reason,
                       cancelled_by = :by, version = version + 1
                where id = :id""")
            .param("reason", reason.trim()).param("by", actorUser).param("id", requestId).update();
        notifications.toEmployee(r.employeeId(), new Notifications.Message(Notifications.Kind.REQUEST_CANCELLED,
            "Your " + what(r) + " was cancelled by HR", nameOfUser(actorUser) + ": “" + reason.trim() + "”",
            "/app/leave?request=" + requestId, "pink"));
    }

    // ---------- reading ----------

    private static final String SELECT_REQUEST = """
        select r.id, r.employee_id, e.full_name as employee_name, r.leave_type_id, t.name as type_name, t.tone,
               r.start_date, r.end_date, r.day_part, r.days, r.reason, r.status, r.created_at, r.decided_at, r.workflow_id,
               r.attachment_id, f.name as attachment_name, f.content_type as attachment_type, f.size_bytes as attachment_size,
               rb.full_name as recorded_by, r.returned_comment, r.cancel_reason
        from leave_request r
        join leave_type t on t.id = r.leave_type_id
        join employee e on e.id = r.employee_id
        left join file_object f on f.id = r.attachment_id
        left join employee rb on rb.user_id = r.recorded_by
        """;

    private record Row(UUID id, UUID employeeId, String employeeName, UUID leaveTypeId, String typeName, String tone,
                       LocalDate startDate, LocalDate endDate, String dayPart, BigDecimal days, String reason,
                       String status, Instant createdAt, Instant decidedAt, UUID workflowId,
                       UUID attachmentId, String attachmentName, String attachmentType, Integer attachmentSize,
                       String recordedBy, String returnedComment, String cancelReason) {
    }

    private Request toRequest(Row r) {
        var pending = "PENDING".equals(r.status()) && r.workflowId() != null ? workflow.pendingWith(r.workflowId()).orElse(null) : null;
        boolean canCancel = "PENDING".equals(r.status()) || "RETURNED".equals(r.status())
            || ("APPROVED".equals(r.status()) && r.startDate().isAfter(LocalDate.now()));
        Attachment attachment = r.attachmentId() == null ? null
            : new Attachment(r.attachmentId(), r.attachmentName(), r.attachmentType(), r.attachmentSize() == null ? 0 : r.attachmentSize());
        return new Request(r.id(), r.employeeId(), r.employeeName(), r.leaveTypeId(), r.typeName(), r.tone(), r.startDate(),
            r.endDate(), r.dayPart(), r.days(), r.reason(), r.status(), r.createdAt(), r.decidedAt(), r.workflowId(),
            pending, canCancel, "RETURNED".equals(r.status()), attachment, r.recordedBy(), r.returnedComment(), r.cancelReason());
    }

    Optional<Request> find(UUID id) {
        return jdbc.sql(SELECT_REQUEST + "where r.id = :id").param("id", id).query(Row.class).optional().map(this::toRequest);
    }

    List<Request> mine(UUID employeeId, int year) {
        return jdbc.sql(SELECT_REQUEST + "where r.employee_id = :emp and r.status <> 'DRAFT' and extract(year from r.start_date) = :year order by r.start_date desc")
            .param("emp", employeeId).param("year", year).query(Row.class).list().stream().map(this::toRequest).toList();
    }

    /** Other people's leave in a date range, limited to the caller's leave:view scope. */
    List<Request> others(UUID me, DataScope scope, LocalDate from, LocalDate to, boolean includePending) {
        return jdbc.sql(SELECT_REQUEST + """
                where %s and r.employee_id <> coalesce(:me, '00000000-0000-0000-0000-000000000000'::uuid)
                  and r.start_date <= :to and r.end_date >= :from
                  and (r.status = 'APPROVED' or (:pending and r.status = 'PENDING'))
                order by r.start_date, e.full_name""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me).param("from", from).param("to", to).param("pending", includePending)
            .query(Row.class).list().stream().map(this::toRequest).toList();
    }

    /** Whether someone with this scope may see the given employee's leave. */
    boolean canSee(UUID me, DataScope scope, UUID employeeId) {
        if (employeeId.equals(me)) return true;
        return jdbc.sql("select exists (select 1 from employee e where e.id = :target and %s)".formatted(EmployeeScope.predicate(scope, "e")))
            .param("target", employeeId).param("me", me).query(Boolean.class).single();
    }

    RequestDetail detail(UUID id) {
        Request r = find(id).orElseThrow(LeaveService::notFound);
        return new RequestDetail(r, r.workflowId() == null ? List.of() : workflow.timeline(r.workflowId()));
    }

    /** True when the employee is on approved leave covering that day. */
    boolean isOnLeave(UUID employeeId, LocalDate day) {
        return jdbc.sql("""
                select exists (select 1 from leave_request where employee_id = :emp and status = 'APPROVED'
                               and start_date <= :day and end_date >= :day)""")
            .param("emp", employeeId).param("day", day).query(Boolean.class).single();
    }

    static DayPart parsePart(String value) {
        if (value == null || value.isBlank()) return DayPart.FULL;
        try {
            return DayPart.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_DAY_PART", "Day part must be FULL, AM or PM.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "LEAVE_NOT_FOUND", "That leave request doesn't exist.");
    }
}
