package com.hrms.workflow;

import com.hrms.notification.Notifications;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approval chains for any request type.
 *
 * <p>A request runs through a {@link Plan}: ordered steps, each a manager level (1 = their manager,
 * 2 = that manager's manager…), a specific person, anyone with a role, or HR (any company-wide approver).
 * Plans come from a configured approval flow, or from a simple {@link ChainSpec}. Steps that can't be filled
 * are skipped; with nothing left, HR approves. Steps run in order; only the active step can be acted on.
 *
 * <p>Who may act on the active step (never the requester):
 * <ul>
 *   <li>a manager step: that manager, with {@code <module>:approve};</li>
 *   <li>a person step: that person (the admin chose them, so no approve permission is needed);</li>
 *   <li>a role step: any member of the role;</li>
 *   <li>an HR step: anyone whose approve permission covers the whole company;</li>
 *   <li>the requester's backup approver, for manager and person steps, while the approver is away or the step is overdue;</li>
 *   <li>company-wide approvers may step in on any step (an override, shown separately).</li>
 * </ul>
 */
@Service
public class WorkflowService {

    public record ChainSpec(int managerLevels, boolean hrStep) {
        /** The same chain as a plan: manager levels 1..n, then HR if asked. */
        public Plan toPlan() {
            List<StepSpec> steps = new ArrayList<>();
            for (int i = 1; i <= managerLevels; i++) steps.add(StepSpec.manager(i));
            if (hrStep) steps.add(StepSpec.hr());
            return new Plan(null, null, steps);
        }
    }

    /** One planned step. kind: MANAGER (managerLevel), PERSON (employeeId), ROLE (roleId) or HR. */
    public record StepSpec(String kind, Integer managerLevel, UUID employeeId, UUID roleId) {
        public static StepSpec manager(int level) { return new StepSpec("MANAGER", level, null, null); }
        public static StepSpec person(UUID employee) { return new StepSpec("PERSON", null, employee, null); }
        public static StepSpec role(UUID role) { return new StepSpec("ROLE", null, null, role); }
        public static StepSpec hr() { return new StepSpec("HR", null, null, null); }
    }

    /** The steps a request will go through; flowId/flowName when it came from a configured flow. */
    public record Plan(UUID flowId, String flowName, List<StepSpec> steps) {
    }

    /** A plan step resolved for a given requester: who approves, or why it's skipped. */
    public record PlannedStep(int position, String kind, String approver, boolean skipped, String note) {
    }

    /** One step as shown on a request's timeline. */
    public record TimelineStep(int level, String label, String approver, String state, String action,
                               String actedBy, String actedAs, String comment, Instant actedAt) {
    }

    /** What the requester sees while waiting: who has it now. */
    public record PendingWith(int level, int totalSteps, String label, String approver) {
    }

    public record Actor(UUID employeeId, Map<String, DataScope> scopes) {
    }

    /** RETURN sends the request back to the requester to change and resubmit (a new chain starts then). */
    public enum Decision { APPROVE, REJECT, RETURN }

    private final JdbcClient jdbc;
    private final Map<String, ApprovalSubject> subjects;
    private final ObjectProvider<ApproverAvailability> availability;
    private final Notifications notifications;
    private final Duration due;

    WorkflowService(JdbcClient jdbc, List<ApprovalSubject> subjects, ObjectProvider<ApproverAvailability> availability,
                    Notifications notifications, @Value("${hrms.workflow.due-hours:48}") long dueHours) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.subjects = subjects.stream().collect(Collectors.toMap(ApprovalSubject::subjectType, Function.identity()));
        this.availability = availability;
        this.due = Duration.ofHours(dueHours);
    }

    ApprovalSubject subject(String type) {
        ApprovalSubject s = subjects.get(type);
        if (s == null) throw new IllegalStateException("No approval handler for " + type);
        return s;
    }

    /** Builds a simple chain and activates its first step. Call inside the caller's transaction. */
    @Transactional
    public UUID start(String subjectType, UUID subjectId, UUID requester, ChainSpec spec) {
        return start(subjectType, subjectId, requester, spec.toPlan());
    }

    /** Builds the steps of a plan for this requester and activates the first. Call inside the caller's transaction. */
    @Transactional
    public UUID start(String subjectType, UUID subjectId, UUID requester, Plan plan) {
        ApprovalSubject subject = subject(subjectType);
        UUID tenant = TenantContext.require();
        UUID instance = UUID.randomUUID();
        jdbc.sql("""
                insert into workflow_instance (id, tenant_id, subject_type, subject_id, state, current_level, module, requester_id,
                                               flow_id, flow_name)
                values (:id, :tenant, :type, :subject, 'PENDING', null, :module, :requester, :flow, :flowName)""")
            .param("id", instance).param("tenant", tenant).param("type", subjectType).param("subject", subjectId)
            .param("module", subject.module()).param("requester", requester)
            .param("flow", plan.flowId()).param("flowName", plan.flowName()).update();

        int level = 1;
        for (Resolved r : resolve(plan, requester)) {
            if (r.skipped()) continue;
            insertStep(tenant, instance, level++, r.kind(), r.assignee(), r.pool(), r.roleId());
        }
        activateNext(instance).ifPresent(step -> notifyWaiting(instance, step));
        return instance;
    }

    /** Who would approve, step by step, for this requester. Nothing is saved. */
    public List<PlannedStep> preview(Plan plan, UUID requester) {
        List<Resolved> resolved = resolve(plan, requester);
        List<PlannedStep> out = new ArrayList<>();
        int position = 1;
        for (Resolved r : resolved) {
            out.add(new PlannedStep(r.skipped() ? 0 : position, r.kind(), r.approver(), r.skipped(), r.note()));
            if (!r.skipped()) position++;
        }
        return out;
    }

    private record Resolved(String kind, UUID assignee, String pool, UUID roleId, String approver, boolean skipped, String note) {
        static Resolved skip(String kind, String approver, String note) {
            return new Resolved(kind, null, null, null, approver, true, note);
        }
    }

    /** Turns plan steps into approvers. Unfillable steps are skipped with a reason; with none left, HR approves. */
    private List<Resolved> resolve(Plan plan, UUID requester) {
        List<Resolved> out = new ArrayList<>();
        UUID previous = null;
        for (StepSpec step : plan.steps()) {
            Resolved r = switch (step.kind()) {
                case "MANAGER" -> {
                    UUID m = requester;
                    for (int i = 0; i < step.managerLevel() && m != null; i++) m = managerOf(m).orElse(null);
                    String label = step.managerLevel() == 1 ? "Their manager" : "Manager, level " + step.managerLevel();
                    if (m == null) yield Resolved.skip("MANAGER", label, "No manager at this level");
                    if (m.equals(requester)) yield Resolved.skip("MANAGER", label, "That's the requester");
                    yield new Resolved("MANAGER", m, null, null, nameOf(m), false, label);
                }
                case "PERSON" -> {
                    String name = nameOf(step.employeeId());
                    boolean active = jdbc.sql("select exists (select 1 from employee where id = :id and status = 'ACTIVE')")
                        .param("id", step.employeeId()).query(Boolean.class).single();
                    if (!active) yield Resolved.skip("PERSON", name, "Not an active employee");
                    if (step.employeeId().equals(requester)) yield Resolved.skip("PERSON", name, "That's the requester");
                    yield new Resolved("PERSON", step.employeeId(), null, null, name, false, "Specific person");
                }
                case "ROLE" -> {
                    String role = jdbc.sql("select name from role where id = :id").param("id", step.roleId())
                        .query(String.class).optional().orElse("Deleted role");
                    if (roleMembers(step.roleId(), requester).isEmpty()) {
                        yield Resolved.skip("ROLE", role, "Nobody else has this role");
                    }
                    yield new Resolved("ROLE", null, "ROLE", step.roleId(), role, false, "Anyone with the role");
                }
                default -> new Resolved("HR", null, "HR", null, "HR", false, "Anyone who approves company-wide");
            };
            if (!r.skipped() && r.assignee() != null && r.assignee().equals(previous)) {
                r = Resolved.skip(r.kind(), r.approver(), "Same person as the step before");
            }
            if (!r.skipped()) previous = r.assignee();
            out.add(r);
        }
        if (out.stream().allMatch(Resolved::skipped)) {
            out.add(new Resolved("HR", null, "HR", null, "HR", false, "No approver found, so HR approves"));
        }
        return out;
    }

    private String nameOf(UUID employee) {
        return jdbc.sql("select full_name from employee where id = :id").param("id", employee).query(String.class)
            .optional().orElse("Unknown person");
    }

    /** Active employees with a login who hold the role, except someone. */
    private Set<UUID> roleMembers(UUID roleId, UUID except) {
        Set<UUID> out = new java.util.LinkedHashSet<>(jdbc.sql("""
                select e.id from employee e join app_user u on u.id = e.user_id and u.status = 'ACTIVE'
                join user_role ur on ur.user_id = u.id
                where ur.role_id = :role and e.status = 'ACTIVE'""")
            .param("role", roleId).query(UUID.class).list());
        out.remove(except);
        return out;
    }

    private Set<UUID> rolesOf(UUID employee) {
        if (employee == null) return Set.of();
        return new java.util.HashSet<>(jdbc.sql("""
                select ur.role_id from user_role ur join employee e on e.user_id = ur.user_id where e.id = :id""")
            .param("id", employee).query(UUID.class).list());
    }

    /** The requester withdraws a pending request. */
    @Transactional
    public void cancel(UUID instanceId, UUID requester) {
        Instance in = lock(instanceId);
        if (!requester.equals(in.requesterId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_REQUEST", "Only the person who made this request can cancel it.");
        }
        if (!"PENDING".equals(in.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_DECIDED", "This request has already been decided.");
        }
        finish(in, ApprovalSubject.Outcome.CANCELLED, null);
    }

    // ---------- inbox and decisions (used by ApprovalsController) ----------

    record Instance(UUID id, String subjectType, UUID subjectId, String state, String module, UUID requesterId,
                    Instant createdAt) {
    }

    record Step(UUID id, UUID instanceId, int level, UUID assigneeId, String pool, UUID poolRoleId, String kind,
                String state, Instant dueAt) {
    }

    /** actingAs: ASSIGNEE, BACKUP or HR (it's waiting for you), or OVERRIDE (waiting for {@code waitingFor}). */
    record InboxItem(UUID instanceId, String subjectType, UUID subjectId, String module, String title, String detail, String tone,
                     String requester, String requesterNo, Instant submittedAt, String stepLabel, String actingAs,
                     String waitingFor, Instant dueAt, boolean overdue) {
    }

    List<InboxItem> inbox(Actor actor) {
        record Row(UUID instanceId, String subjectType, UUID subjectId, String module, UUID requesterId, Instant createdAt,
                   UUID stepId, int level, UUID assigneeId, String pool, UUID poolRoleId, String kind, Instant dueAt,
                   String requester, String requesterNo, UUID requesterBackup, String assignee, String roleName,
                   int position, int totalSteps) {
        }
        Set<UUID> myRoles = rolesOf(actor.employeeId());
        List<Row> rows = jdbc.sql("""
                select i.id as instance_id, i.subject_type, i.subject_id, i.module, i.requester_id, i.created_at,
                       s.id as step_id, s.level, s.assignee_id, s.pool, s.pool_role_id, s.kind, s.due_at,
                       e.full_name as requester, e.employee_no as requester_no, e.backup_approver_id as requester_backup,
                       a.full_name as assignee, pr.name as role_name,
                       (select count(*) from workflow_step x where x.instance_id = i.id and x.state <> 'SKIPPED' and x.level <= s.level) as position,
                       (select count(*) from workflow_step x where x.instance_id = i.id and x.state <> 'SKIPPED') as total_steps
                from workflow_instance i
                join workflow_step s on s.instance_id = i.id and s.state = 'ACTIVE'
                join employee e on e.id = i.requester_id
                left join employee a on a.id = s.assignee_id
                left join role pr on pr.id = s.pool_role_id
                where i.state = 'PENDING'
                order by i.created_at""")
            .query(Row.class).list();
        List<InboxItem> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Row r : rows) {
            Optional<String> as = actingAs(actor, myRoles, r.module(), r.requesterId(), r.requesterBackup(), r.assigneeId(),
                r.pool(), r.poolRoleId(), r.kind(), r.dueAt());
            if (as.isEmpty()) continue;
            ApprovalSubject.Summary summary = subject(r.subjectType()).summarize(r.subjectId());
            out.add(new InboxItem(r.instanceId(), r.subjectType(), r.subjectId(), r.module(), summary.title(), summary.detail(), summary.tone(),
                r.requester(), r.requesterNo(), r.createdAt(), stepLabel(r.pool(), r.position(), r.totalSteps()), as.get(),
                r.assignee() != null ? r.assignee() : r.roleName() != null ? r.roleName() : "HR", r.dueAt(),
                r.dueAt() != null && r.dueAt().isBefore(now)));
        }
        return out;
    }

    public void decide(Actor actor, UUID instanceId, boolean approve, String comment) {
        decide(actor, instanceId, approve ? Decision.APPROVE : Decision.REJECT, comment);
    }

    @Transactional
    public void decide(Actor actor, UUID instanceId, Decision decision, String comment) {
        if (actor.employeeId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "You need an employee record to approve requests.");
        }
        if (decision != Decision.APPROVE && (comment == null || comment.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", decision == Decision.RETURN
                ? "Say what they should change before sending it back."
                : "Say why you're rejecting it, so they know what to change.");
        }
        Instance in = lock(instanceId);
        if (!"PENDING".equals(in.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_DECIDED", "Someone has already decided this request.");
        }
        Step step = activeStep(instanceId).orElseThrow(() ->
            new ApiException(HttpStatus.CONFLICT, "REQUEST_DECIDED", "Someone has already decided this request."));
        UUID backup = jdbc.sql("select backup_approver_id from employee where id = :id")
            .param("id", in.requesterId()).query(UUID.class).optional().orElse(null);
        String as = actingAs(actor, rolesOf(actor.employeeId()), in.module(), in.requesterId(), backup, step.assigneeId(),
                step.pool(), step.poolRoleId(), step.kind(), step.dueAt())
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_APPROVAL",
                "You can't act on this request. It's waiting for someone else."));

        complete(step, actor.employeeId(), decision.name(), as, blankToNull(comment));
        if (decision == Decision.REJECT) {
            finish(in, ApprovalSubject.Outcome.REJECTED, blankToNull(comment));
            return;
        }
        if (decision == Decision.RETURN) {
            finish(in, ApprovalSubject.Outcome.RETURNED, blankToNull(comment));
            return;
        }
        // The same person at the next level (e.g. HR who already approved as backup) doesn't approve twice.
        while (true) {
            Optional<Step> next = activateNext(instanceId);
            if (next.isEmpty()) {
                finish(in, ApprovalSubject.Outcome.APPROVED, blankToNull(comment));
                return;
            }
            if (!actor.employeeId().equals(next.get().assigneeId())) {
                notifyWaiting(instanceId, next.get());
                return;
            }
            complete(next.get(), actor.employeeId(), "APPROVE", "AUTO", "Already approved at an earlier level");
        }
    }

    // ---------- timeline (used by subject modules) ----------

    public List<TimelineStep> timeline(UUID instanceId) {
        record Row(int level, String assignee, String roleName, String pool, String state, String action, String actedBy,
                   String actedAs, String comment, Instant actedAt) {
        }
        List<Row> rows = jdbc.sql("""
                select s.level, a.full_name as assignee, pr.name as role_name, s.pool, s.state, s.action,
                       b.full_name as acted_by, s.acted_as, s.comment, s.acted_at
                from workflow_step s
                left join employee a on a.id = s.assignee_id
                left join role pr on pr.id = s.pool_role_id
                left join employee b on b.id = s.acted_by
                where s.instance_id = :id order by s.level""")
            .param("id", instanceId).query(Row.class).list();
        int total = rows.size();
        List<TimelineStep> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            out.add(new TimelineStep(r.level(), stepLabel(r.pool(), i + 1, total),
                r.assignee() != null ? r.assignee() : r.roleName() != null ? r.roleName() : "HR",
                r.state(), r.action(), r.actedBy(), r.actedAs(), r.comment(), r.actedAt()));
        }
        return out;
    }

    public Optional<PendingWith> pendingWith(UUID instanceId) {
        List<TimelineStep> steps = timeline(instanceId);
        return steps.stream().filter(s -> "ACTIVE".equals(s.state())).findFirst()
            .map(s -> new PendingWith(s.level(), steps.size(), s.label(), s.approver()));
    }

    // ---------- internals ----------

    /** How the actor may act on a step, or empty when they may not. */
    private Optional<String> actingAs(Actor actor, Set<UUID> myRoles, String module, UUID requester, UUID requesterBackup,
                                      UUID assignee, String pool, UUID poolRoleId, String kind, Instant dueAt) {
        UUID me = actor.employeeId();
        if (me == null || me.equals(requester)) return Optional.empty();
        DataScope scope = actor.scopes().get(module + ":approve");
        boolean companyWide = scope == DataScope.TENANT;
        if ("ROLE".equals(pool)) {
            if (poolRoleId != null && myRoles.contains(poolRoleId)) return Optional.of("ROLE");
            return companyWide ? Optional.of("OVERRIDE") : Optional.empty();
        }
        if ("HR".equals(pool)) return companyWide ? Optional.of("HR") : Optional.empty();
        // A named person was chosen by an admin; a manager also needs the approve permission.
        if (me.equals(assignee) && ("PERSON".equals(kind) || scope != null)) return Optional.of("ASSIGNEE");
        if (scope == null) return Optional.empty();
        if (me.equals(requesterBackup)) {
            boolean overdue = dueAt != null && dueAt.isBefore(Instant.now());
            boolean away = assignee != null && availability.getIfAvailable(() -> (e, d) -> false).isAway(assignee, LocalDate.now());
            if (overdue || away) return Optional.of("BACKUP");
        }
        return companyWide ? Optional.of("OVERRIDE") : Optional.empty();
    }

    private Optional<UUID> managerOf(UUID employee) {
        return jdbc.sql("select manager_id from employee where id = :id and manager_id is not null")
            .param("id", employee).query(UUID.class).optional();
    }

    private void insertStep(UUID tenant, UUID instance, int level, String kind, UUID assignee, String pool, UUID roleId) {
        jdbc.sql("""
                insert into workflow_step (id, tenant_id, instance_id, level, kind, assignee_id, pool, pool_role_id, state)
                values (:id, :tenant, :instance, :level, :kind, :assignee, :pool, :role, 'WAITING')""")
            .param("id", UUID.randomUUID()).param("tenant", tenant).param("instance", instance).param("level", level)
            .param("kind", kind).param("assignee", assignee).param("pool", pool).param("role", roleId).update();
    }

    /** Activates the lowest waiting step and returns it; empty when none is left. */
    private Optional<Step> activateNext(UUID instanceId) {
        Optional<Step> next = jdbc.sql("""
                select id, instance_id, level, assignee_id, pool, pool_role_id, kind, state, due_at from workflow_step
                where instance_id = :id and state = 'WAITING' order by level limit 1""")
            .param("id", instanceId).query(Step.class).optional();
        next.ifPresent(s -> {
            Instant now = Instant.now();
            jdbc.sql("update workflow_step set state = 'ACTIVE', activated_at = :now, due_at = :due where id = :id")
                .param("now", java.sql.Timestamp.from(now)).param("due", java.sql.Timestamp.from(now.plus(due)))
                .param("id", s.id()).update();
            jdbc.sql("update workflow_instance set current_level = :level, version = version + 1 where id = :id")
                .param("level", s.level()).param("id", instanceId).update();
        });
        return next;
    }

    private Optional<Step> activeStep(UUID instanceId) {
        return jdbc.sql("""
                select id, instance_id, level, assignee_id, pool, pool_role_id, kind, state, due_at from workflow_step
                where instance_id = :id and state = 'ACTIVE' for update""")
            .param("id", instanceId).query(Step.class).optional();
    }

    private void complete(Step step, UUID actor, String action, String as, String comment) {
        jdbc.sql("""
                update workflow_step set state = 'DONE', action = :action, acted_by = :actor, acted_as = :as,
                       comment = :comment, acted_at = now()
                where id = :id""")
            .param("action", action).param("actor", actor).param("as", as).param("comment", comment)
            .param("id", step.id()).update();
    }

    // ---------- notifications ----------

    private static String lowerFirst(String s) {
        return s == null || s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Tells whoever the newly active step is waiting for: its approver, or every company-wide approver for an HR step. */
    private void notifyWaiting(UUID instanceId, Step step) {
        Instance in = jdbc.sql("""
                select id, subject_type, subject_id, state, module, requester_id, created_at
                from workflow_instance where id = :id""")
            .param("id", instanceId).query(Instance.class).single();
        ApprovalSubject.Summary summary = subject(in.subjectType()).summarize(in.subjectId());
        String requester = jdbc.sql("select full_name from employee where id = :id").param("id", in.requesterId())
            .query(String.class).optional().orElse("Someone");
        Set<UUID> recipients = waitingRecipients(in, step);
        notifications.toEmployees(recipients, new Notifications.Message(Notifications.Kind.APPROVAL_WAITING,
            requester + " asked for " + lowerFirst(summary.title()),
            summary.detail() + ("HR".equals(step.pool()) ? " · HR approval" : ""),
            "/app/approvals?open=" + instanceId, summary.tone()));
    }

    /** Who a step is waiting for: its approver, everyone with its role, or every company-wide approver for an HR step. */
    Set<UUID> waitingRecipients(Instance in, Step step) {
        return "HR".equals(step.pool())
            ? notifications.employeesWithCompanyWide(in.module() + ":approve", Set.of(in.requesterId()))
            : "ROLE".equals(step.pool()) && step.poolRoleId() != null ? roleMembers(step.poolRoleId(), in.requesterId())
            : step.assigneeId() == null ? Set.of() : Set.of(step.assigneeId());
    }

    /** Tells the requester the outcome (not when they withdrew it themselves). */
    private void notifyRequester(Instance in, ApprovalSubject.Outcome outcome, String comment) {
        if (outcome == ApprovalSubject.Outcome.CANCELLED) return;
        ApprovalSubject subject = subject(in.subjectType());
        ApprovalSubject.Summary summary = subject.summarize(in.subjectId());
        String by = jdbc.sql("""
                select e.full_name from workflow_step s join employee e on e.id = s.acted_by
                where s.instance_id = :id and s.state = 'DONE' and s.acted_as <> 'AUTO'
                order by s.acted_at desc limit 1""")
            .param("id", in.id()).query(String.class).optional().orElse(null);
        String what = "Your " + lowerFirst(summary.title());
        String link = subject.requesterLink(in.subjectId());
        String said = comment == null ? "" : "“" + comment + "”";
        Notifications.Message message = switch (outcome) {
            case APPROVED -> new Notifications.Message(Notifications.Kind.REQUEST_APPROVED, what + " was approved",
                (by == null ? "" : "By " + by) + (comment == null ? "" : " · " + said), link, "green");
            case REJECTED -> new Notifications.Message(Notifications.Kind.REQUEST_REJECTED, what + " was rejected",
                (by == null ? "" : by + ": ") + said, link, "pink");
            case RETURNED -> new Notifications.Message(Notifications.Kind.REQUEST_RETURNED, what + " was sent back for changes",
                (by == null ? "" : by + ": ") + said, link, "orange");
            case CANCELLED -> null;
        };
        if (message != null) notifications.toEmployee(in.requesterId(), message);
    }

    private void finish(Instance in, ApprovalSubject.Outcome outcome, String comment) {
        jdbc.sql("update workflow_step set state = 'SKIPPED' where instance_id = :id and state in ('WAITING', 'ACTIVE')")
            .param("id", in.id()).update();
        jdbc.sql("""
                update workflow_instance set state = :state, current_level = null, decided_at = now(), version = version + 1
                where id = :id""")
            .param("state", outcome.name()).param("id", in.id()).update();
        subject(in.subjectType()).onFinished(in.subjectId(), outcome, comment);
        notifyRequester(in, outcome, comment);
    }

    private Instance lock(UUID instanceId) {
        return jdbc.sql("""
                select id, subject_type, subject_id, state, module, requester_id, created_at
                from workflow_instance where id = :id for update""")
            .param("id", instanceId).query(Instance.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "That request doesn't exist."));
    }

    static String stepLabel(String pool, int position, int totalSteps) {
        String step = "step " + position + " of " + totalSteps;
        return "HR".equals(pool) ? "HR · " + step : Character.toUpperCase(step.charAt(0)) + step.substring(1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
