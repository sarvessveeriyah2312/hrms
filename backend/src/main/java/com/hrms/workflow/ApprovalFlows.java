package com.hrms.workflow;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configured approval flows. A request uses the first active flow (lowest position) whose conditions match:
 * type, department and quantity (days for leave). Callers fall back to their own default chain when none match.
 */
@Service
public class ApprovalFlows {

    public record Step(String kind, Integer managerLevel, UUID employeeId, UUID roleId) {
    }

    public record Flow(UUID id, String subjectType, String name, String description, boolean active, int position,
                       List<UUID> typeIds, List<UUID> departmentIds, BigDecimal minQuantity, BigDecimal maxQuantity,
                       List<Step> steps, Instant updatedAt) {
    }

    public record FlowInput(String name, String description, Boolean active, List<UUID> typeIds, List<UUID> departmentIds,
                            BigDecimal minQuantity, BigDecimal maxQuantity, List<Step> steps) {
    }

    static final int MAX_STEPS = 8;

    private final JdbcClient jdbc;
    private final AuditLog audit;

    ApprovalFlows(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------- matching (used by subject modules) ----------

    /** The plan of the first active flow that matches, if any. */
    public Optional<WorkflowService.Plan> match(String subjectType, UUID typeId, UUID departmentId, BigDecimal quantity) {
        return list(subjectType).stream()
            .filter(Flow::active)
            .filter(f -> f.typeIds().isEmpty() || f.typeIds().contains(typeId))
            .filter(f -> f.departmentIds().isEmpty() || (departmentId != null && f.departmentIds().contains(departmentId)))
            .filter(f -> f.minQuantity() == null || (quantity != null && quantity.compareTo(f.minQuantity()) >= 0))
            .filter(f -> f.maxQuantity() == null || (quantity != null && quantity.compareTo(f.maxQuantity()) <= 0))
            .findFirst()
            .map(f -> new WorkflowService.Plan(f.id(), f.name(), f.steps().stream()
                .map(s -> new WorkflowService.StepSpec(s.kind(), s.managerLevel(), s.employeeId(), s.roleId())).toList()));
    }

    // ---------- reading ----------

    public List<Flow> list(String subjectType) {
        record StepRow(UUID flowId, String kind, Integer managerLevel, UUID employeeId, UUID roleId) {
        }
        List<StepRow> steps = jdbc.sql("""
                select s.flow_id, s.kind, s.manager_level, s.employee_id, s.role_id
                from approval_flow_step s join approval_flow f on f.id = s.flow_id
                where f.subject_type = :type order by s.flow_id, s.position""")
            .param("type", subjectType).query(StepRow.class).list();
        return jdbc.sql("""
                select id, subject_type, name, description, active, position, type_ids, department_ids,
                       min_quantity, max_quantity, updated_at
                from approval_flow where subject_type = :type order by position, name""")
            .param("type", subjectType)
            .query((rs, i) -> {
                UUID id = rs.getObject("id", UUID.class);
                return new Flow(id, rs.getString("subject_type"), rs.getString("name"), rs.getString("description"),
                    rs.getBoolean("active"), rs.getInt("position"), uuids(rs.getArray("type_ids")),
                    uuids(rs.getArray("department_ids")), rs.getBigDecimal("min_quantity"), rs.getBigDecimal("max_quantity"),
                    steps.stream().filter(s -> s.flowId().equals(id))
                        .map(s -> new Step(s.kind(), s.managerLevel(), s.employeeId(), s.roleId())).toList(),
                    rs.getTimestamp("updated_at").toInstant());
            })
            .list();
    }

    Flow find(UUID id) {
        String type = jdbc.sql("select subject_type from approval_flow where id = :id").param("id", id).query(String.class)
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FLOW_NOT_FOUND", "That workflow doesn't exist."));
        return list(type).stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    // ---------- changing ----------

    @Transactional
    Flow create(UUID actor, String subjectType, FlowInput in) {
        String name = requireName(in.name());
        validate(in);
        int position = jdbc.sql("select coalesce(max(position), -1) + 1 from approval_flow where subject_type = :type")
            .param("type", subjectType).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into approval_flow (id, tenant_id, subject_type, name, description, active, position, type_ids,
                                               department_ids, min_quantity, max_quantity)
                    values (:id, :tenant, :type, :name, :desc, :active, :position, cast(:types as uuid[]), cast(:depts as uuid[]),
                            :min, :max)""")
                .param("id", id).param("tenant", TenantContext.require()).param("type", subjectType).param("name", name)
                .param("desc", blankToNull(in.description())).param("active", in.active() == null || in.active())
                .param("position", position).param("types", arrayLiteral(in.typeIds())).param("depts", arrayLiteral(in.departmentIds()))
                .param("min", in.minQuantity()).param("max", in.maxQuantity()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "FLOW_NAME_TAKEN", "There's already a workflow called " + name + ".");
        }
        writeSteps(id, in.steps());
        audit.record(actor, "APPROVAL_FLOW_CREATED", "approval_flow", id, Map.of("name", name, "steps", in.steps().size()));
        return find(id);
    }

    @Transactional
    Flow update(UUID actor, UUID id, FlowInput in) {
        Flow existing = find(id);
        String name = requireName(in.name());
        validate(in);
        try {
            jdbc.sql("""
                    update approval_flow set name = :name, description = :desc, active = :active,
                           type_ids = cast(:types as uuid[]), department_ids = cast(:depts as uuid[]),
                           min_quantity = :min, max_quantity = :max, updated_at = now()
                    where id = :id""")
                .param("name", name).param("desc", blankToNull(in.description()))
                .param("active", in.active() == null ? existing.active() : in.active())
                .param("types", arrayLiteral(in.typeIds())).param("depts", arrayLiteral(in.departmentIds()))
                .param("min", in.minQuantity()).param("max", in.maxQuantity()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "FLOW_NAME_TAKEN", "There's already a workflow called " + name + ".");
        }
        jdbc.sql("delete from approval_flow_step where flow_id = :id").param("id", id).update();
        writeSteps(id, in.steps());
        audit.record(actor, "APPROVAL_FLOW_UPDATED", "approval_flow", id, Map.of("name", name, "steps", in.steps().size()));
        return find(id);
    }

    /** Requests already in approval keep the steps they started with. */
    @Transactional
    void delete(UUID actor, UUID id) {
        Flow existing = find(id);
        jdbc.sql("delete from approval_flow where id = :id").param("id", id).update();
        audit.record(actor, "APPROVAL_FLOW_DELETED", "approval_flow", id, Map.of("name", existing.name()));
    }

    /** Sets the checking order: the ids in the order they should be tried. */
    @Transactional
    List<Flow> reorder(UUID actor, String subjectType, List<UUID> order) {
        List<UUID> current = list(subjectType).stream().map(Flow::id).toList();
        if (order == null || order.size() != current.size() || !order.containsAll(current)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ORDER", "Send every workflow exactly once, in the new order.");
        }
        for (int i = 0; i < order.size(); i++) {
            jdbc.sql("update approval_flow set position = :p where id = :id").param("p", i).param("id", order.get(i)).update();
        }
        audit.record(actor, "APPROVAL_FLOWS_REORDERED", "approval_flow", null, Map.of("order", order.toString()));
        return list(subjectType);
    }

    // ---------- validation ----------

    private void validate(FlowInput in) {
        if (in.steps() == null || in.steps().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STEPS_REQUIRED", "Add at least one approval step.");
        }
        if (in.steps().size() > MAX_STEPS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_STEPS", "A workflow can have up to " + MAX_STEPS + " steps.");
        }
        if (in.minQuantity() != null && in.maxQuantity() != null && in.minQuantity().compareTo(in.maxQuantity()) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE", "The minimum days is more than the maximum.");
        }
        if ((in.minQuantity() != null && in.minQuantity().signum() < 0) || (in.maxQuantity() != null && in.maxQuantity().signum() < 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE", "Days can't be negative.");
        }
        for (Step s : in.steps()) {
            switch (s.kind() == null ? "" : s.kind()) {
                case "MANAGER" -> {
                    if (s.managerLevel() == null || s.managerLevel() < 1 || s.managerLevel() > 3) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_STEP", "Choose manager level 1, 2 or 3.");
                    }
                }
                case "PERSON" -> {
                    if (s.employeeId() == null || !exists("employee", s.employeeId())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_STEP", "Choose the person for each “specific person” step.");
                    }
                }
                case "ROLE" -> {
                    if (s.roleId() == null || !exists("role", s.roleId())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_STEP", "Choose the role for each “anyone with a role” step.");
                    }
                }
                case "HR" -> { }
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_STEP", "Unknown step type.");
            }
        }
        for (UUID dept : in.departmentIds() == null ? List.<UUID>of() : in.departmentIds()) {
            if (!exists("department", dept)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_DEPARTMENT", "One of the departments doesn't exist.");
        }
    }

    /** Checked through RLS, so ids from another company count as missing. */
    private boolean exists(String table, UUID id) {
        return jdbc.sql("select exists (select 1 from " + table + " where id = :id)").param("id", id).query(Boolean.class).single();
    }

    private void writeSteps(UUID flowId, List<Step> steps) {
        int position = 1;
        for (Step s : steps) {
            jdbc.sql("""
                    insert into approval_flow_step (tenant_id, flow_id, position, kind, manager_level, employee_id, role_id)
                    values (:tenant, :flow, :pos, :kind, :level, :emp, :role)""")
                .param("tenant", TenantContext.require()).param("flow", flowId).param("pos", position++).param("kind", s.kind())
                .param("level", "MANAGER".equals(s.kind()) ? s.managerLevel() : null)
                .param("emp", "PERSON".equals(s.kind()) ? s.employeeId() : null)
                .param("role", "ROLE".equals(s.kind()) ? s.roleId() : null).update();
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Give the workflow a name of up to 80 characters.");
        }
        return name.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "{a,b}" for uuid[], or null for "any". */
    private static String arrayLiteral(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        ids.stream().distinct().forEach(id -> parts.add(id.toString()));
        return "{" + String.join(",", parts) + "}";
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) return List.of();
        return Arrays.stream((Object[]) array.getArray()).map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString())).toList();
    }
}
