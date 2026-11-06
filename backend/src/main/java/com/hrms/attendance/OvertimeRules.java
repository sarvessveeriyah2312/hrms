package com.hrms.attendance;

import com.hrms.leave.LeaveCredits;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How overtime is compensated: paid, given back as time off (leave), or the employee's choice per request; and how
 * hours become leave. A company-wide rule, optionally overridden for a legal entity or a department; a person gets their
 * department's rule, else their entity's, else the company's. With no rule, overtime is paid.
 */
@Service
class OvertimeRules {

    static final Set<String> COMPENSATIONS = Set.of("PAY", "TIME_OFF", "CHOICE");
    static final Set<String> CONVERSIONS = Set.of("BANKED", "PER_REQUEST");
    static final Set<String> UNITS = Set.of("HALF_DAY", "FULL_DAY");
    static final Set<String> EXPIRIES = Set.of("NONE", "DAYS", "YEAR_END");
    static final Set<String> RATE_MODES = Set.of("AUTO", "EMPLOYEE");
    static final BigDecimal WORKING_RATE = new BigDecimal("1.5");
    static final BigDecimal REST_RATE = new BigDecimal("2.0");
    static final BigDecimal HOLIDAY_RATE = new BigDecimal("3.0");

    /** The kind of day overtime is worked on, which sets its rate. */
    enum DayKind { WORKING, REST, HOLIDAY }

    /**
     * scope: COMPANY, ENTITY, DEPARTMENT, or DEFAULT (no rule set: paid). leaveTypeId is null when overtime is only
     * paid. expiryDays is set only for expiry DAYS. Rates: for a working day, rest day and public holiday; rateMode
     * AUTO sets the rate from the day, EMPLOYEE lets the employee pick one of them.
     */
    record Rule(UUID id, String scope, UUID entityId, String entityName, UUID departmentId, String departmentName,
                String compensation, String conversion, String creditUnit, BigDecimal hoursPerUnit, boolean applyRate,
                UUID leaveTypeId, String leaveTypeName, String expiry, Integer expiryDays, Instant updatedAt,
                BigDecimal rateWorking, BigDecimal rateRest, BigDecimal rateHoliday, String rateMode,
                boolean requirePreApproval) {

        BigDecimal rateFor(DayKind kind) {
            return switch (kind) {
                case WORKING -> rateWorking;
                case REST -> rateRest;
                case HOLIDAY -> rateHoliday;
            };
        }

        /** The rates an employee may pick from, lowest first. */
        List<BigDecimal> rates() {
            return java.util.stream.Stream.of(rateWorking, rateRest, rateHoliday)
                .map(BigDecimal::stripTrailingZeros).distinct().sorted().toList();
        }

        boolean allowsTimeOff() {
            return !"PAY".equals(compensation) && leaveTypeId != null;
        }

        BigDecimal unitDays() {
            return "FULL_DAY".equals(creditUnit) ? BigDecimal.ONE.setScale(1) : new BigDecimal("0.5");
        }
    }

    static final Rule DEFAULT = new Rule(null, "DEFAULT", null, null, null, null, "PAY", "BANKED", "HALF_DAY",
        new BigDecimal("4"), false, null, null, "NONE", null, null, WORKING_RATE, REST_RATE, HOLIDAY_RATE, "AUTO", false);

    /** entityId or departmentId (not both) or neither for the company-wide rule. */
    record RuleInput(UUID entityId, UUID departmentId, String compensation, String conversion, String creditUnit,
                     BigDecimal hoursPerUnit, Boolean applyRate, UUID leaveTypeId, String expiry, Integer expiryDays,
                     BigDecimal rateWorking, BigDecimal rateRest, BigDecimal rateHoliday, String rateMode,
                     Boolean requirePreApproval) {
    }

    record Option(UUID id, String name) {
    }

    /** The rules, and what they can be set for: entities, departments, and leave types that keep a balance. */
    record Settings(List<Rule> rules, List<Option> entities, List<Option> departments, List<Option> leaveTypes) {
    }

    private static final String SELECT = """
            select r.id, case when r.department_id is not null then 'DEPARTMENT' when r.entity_id is not null then 'ENTITY'
                              else 'COMPANY' end as scope,
                   r.entity_id, le.name as entity_name, r.department_id, d.name as department_name, r.compensation,
                   r.conversion, r.credit_unit, r.hours_per_unit, r.apply_rate, r.leave_type_id, lt.name as leave_type_name,
                   r.expiry, r.expiry_days, r.updated_at, r.rate_working, r.rate_rest, r.rate_holiday, r.rate_mode,
                   r.require_pre_approval
            from overtime_rule r
            left join legal_entity le on le.id = r.entity_id
            left join department d on d.id = r.department_id
            left join leave_type lt on lt.id = r.leave_type_id
            """;

    private final JdbcClient jdbc;
    private final LeaveCredits credits;
    private final AuditLog audit;

    OvertimeRules(JdbcClient jdbc, LeaveCredits credits, AuditLog audit) {
        this.jdbc = jdbc;
        this.credits = credits;
        this.audit = audit;
    }

    // ---------- reading ----------

    /** Company first, then entities, then departments, each by name. */
    List<Rule> list() {
        return jdbc.sql(SELECT + """
                order by case when r.department_id is not null then 2 when r.entity_id is not null then 1 else 0 end,
                         le.name, d.name""").query(Rule.class).list();
    }

    @Transactional(readOnly = true)
    Settings settings() {
        return new Settings(list(),
            jdbc.sql("select id, name from legal_entity order by name").query(Option.class).list(),
            jdbc.sql("select id, name from department order by name").query(Option.class).list(),
            jdbc.sql("select id, name from leave_type where active and track_balance order by name").query(Option.class).list());
    }

    /** The rule that applies to someone: their department's, else their entity's, else the company's, else paid. */
    Rule forEmployee(UUID employeeId) {
        record Where(UUID entityId, UUID departmentId) {
        }
        Where w = jdbc.sql("select entity_id, department_id from employee where id = :id").param("id", employeeId)
            .query(Where.class).optional().orElse(new Where(null, null));
        List<Rule> rules = list();
        return pick(rules, w.entityId(), w.departmentId());
    }

    static Rule pick(List<Rule> rules, UUID entityId, UUID departmentId) {
        return first(rules, r -> departmentId != null && departmentId.equals(r.departmentId()))
            .or(() -> first(rules, r -> entityId != null && entityId.equals(r.entityId())))
            .or(() -> first(rules, r -> "COMPANY".equals(r.scope())))
            .orElse(DEFAULT);
    }

    private static Optional<Rule> first(List<Rule> rules, java.util.function.Predicate<Rule> p) {
        return rules.stream().filter(p).findFirst();
    }

    // ---------- converting hours to leave ----------

    /**
     * countedHours: the hours that count (after the rate, if the rule applies it). days: leave earned now.
     * bankAfter: banked hours left (BANKED); for PER_REQUEST the bank is untouched and lostHours are the hours short of
     * a whole unit.
     */
    record Conversion(BigDecimal countedHours, BigDecimal days, BigDecimal bankAfter, BigDecimal lostHours) {
    }

    static Conversion convert(Rule r, BigDecimal hours, BigDecimal rate, BigDecimal bankBefore) {
        BigDecimal counted = r.applyRate() && rate != null ? hours.multiply(rate) : hours;
        counted = counted.setScale(2, RoundingMode.HALF_UP);
        boolean banked = "BANKED".equals(r.conversion());
        BigDecimal pool = banked ? bankBefore.add(counted) : counted;
        BigDecimal units = pool.divide(r.hoursPerUnit(), 0, RoundingMode.DOWN);
        BigDecimal used = units.multiply(r.hoursPerUnit());
        BigDecimal days = units.multiply(r.unitDays()).setScale(1, RoundingMode.UNNECESSARY);
        return banked
            ? new Conversion(counted, days, pool.subtract(used).setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO.setScale(2))
            : new Conversion(counted, days, bankBefore, pool.subtract(used).setScale(2, RoundingMode.HALF_UP));
    }

    /** When leave earned on {@code earnedOn} expires; null: it doesn't. */
    static LocalDate expiresOn(Rule r, LocalDate earnedOn) {
        return switch (r.expiry()) {
            case "DAYS" -> earnedOn.plusDays(r.expiryDays());
            case "YEAR_END" -> LocalDate.of(earnedOn.getYear(), 12, 31);
            default -> null;
        };
    }

    // ---------- changing ----------

    @Transactional
    Rule save(UUID actor, UUID id, RuleInput in) {
        validate(in);
        boolean pay = "PAY".equals(in.compensation());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("entity", in.entityId());
        values.put("dept", in.departmentId());
        values.put("comp", in.compensation());
        values.put("conv", pay ? "BANKED" : in.conversion());
        values.put("unit", pay ? "HALF_DAY" : in.creditUnit());
        values.put("hours", pay ? new BigDecimal("4") : in.hoursPerUnit());
        values.put("rate", !pay && Boolean.TRUE.equals(in.applyRate()));
        values.put("type", pay ? null : in.leaveTypeId());
        values.put("expiry", pay ? "NONE" : in.expiry());
        values.put("days", !pay && "DAYS".equals(in.expiry()) ? in.expiryDays() : null);
        values.put("rw", orDefault(in.rateWorking(), WORKING_RATE));
        values.put("rr", orDefault(in.rateRest(), REST_RATE));
        values.put("rh", orDefault(in.rateHoliday(), HOLIDAY_RATE));
        values.put("mode", in.rateMode() == null ? "AUTO" : in.rateMode());
        values.put("pre", Boolean.TRUE.equals(in.requirePreApproval()));
        UUID ruleId = id == null ? UUID.randomUUID() : id;
        try {
            if (id == null) {
                jdbc.sql("""
                        insert into overtime_rule (id, tenant_id, entity_id, department_id, compensation, conversion, credit_unit,
                                                   hours_per_unit, apply_rate, leave_type_id, expiry, expiry_days,
                                                   rate_working, rate_rest, rate_holiday, rate_mode, require_pre_approval)
                        values (:id, :tenant, :entity, :dept, :comp, :conv, :unit, :hours, :rate, :type, :expiry, :days,
                                :rw, :rr, :rh, :mode, :pre)""")
                    .param("id", ruleId).param("tenant", TenantContext.require()).params(values).update();
            } else {
                int n = jdbc.sql("""
                        update overtime_rule set entity_id = :entity, department_id = :dept, compensation = :comp,
                               conversion = :conv, credit_unit = :unit, hours_per_unit = :hours, apply_rate = :rate,
                               leave_type_id = :type, expiry = :expiry, expiry_days = :days, rate_working = :rw,
                               rate_rest = :rr, rate_holiday = :rh, rate_mode = :mode, require_pre_approval = :pre, updated_at = now()
                        where id = :id""")
                    .param("id", ruleId).params(values).update();
                if (n == 0) throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "RULE_EXISTS", in.departmentId() != null
                ? "That department already has an overtime rule. Change that one instead."
                : in.entityId() != null ? "That company entity already has an overtime rule. Change that one instead."
                : "There's already a company-wide overtime rule. Change that one instead.");
        }
        Map<String, Object> after = new LinkedHashMap<>(values);
        after.values().removeIf(java.util.Objects::isNull);
        audit.record(actor, id == null ? "OVERTIME_RULE_CREATED" : "OVERTIME_RULE_UPDATED", "overtime_rule", ruleId, after);
        return list().stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
    }

    /** Requests already approved keep the leave they earned; banked hours stay in the bank. */
    @Transactional
    void delete(UUID actor, UUID id) {
        if (jdbc.sql("delete from overtime_rule where id = :id").param("id", id).update() == 0) throw notFound();
        audit.record(actor, "OVERTIME_RULE_DELETED", "overtime_rule", id, Map.of());
    }

    private void validate(RuleInput in) {
        if (in == null || in.compensation() == null || !COMPENSATIONS.contains(in.compensation())) {
            throw bad("BAD_COMPENSATION", "Choose how overtime is compensated: paid, time off, or the employee chooses.");
        }
        if (in.entityId() != null && in.departmentId() != null) {
            throw bad("BAD_SCOPE", "A rule is for the whole company, one company entity, or one department.");
        }
        if (in.entityId() != null && !exists("legal_entity", in.entityId())) throw bad("UNKNOWN_ENTITY", "That company entity doesn't exist.");
        if (in.departmentId() != null && !exists("department", in.departmentId())) throw bad("UNKNOWN_DEPARTMENT", "That department doesn't exist.");
        if (in.rateMode() != null && !RATE_MODES.contains(in.rateMode())) throw bad("BAD_RATE_MODE", "Choose how the rate is set.");
        for (BigDecimal rate : java.util.Arrays.asList(in.rateWorking(), in.rateRest(), in.rateHoliday())) {
            if (rate != null && !validRate(rate)) throw bad("BAD_RATE", "Use rates from 1.0× to 5.0×, in quarter steps (like 1.5 or 1.75).");
        }
        if ("PAY".equals(in.compensation())) return;
        if (!credits.canCredit(in.leaveTypeId())) {
            throw bad("LEAVE_TYPE_REQUIRED", "Choose the leave type that time off is added to (one that keeps a balance).");
        }
        if (in.conversion() == null || !CONVERSIONS.contains(in.conversion())) throw bad("BAD_CONVERSION", "Choose how hours become leave.");
        if (in.creditUnit() == null || !UNITS.contains(in.creditUnit())) throw bad("BAD_UNIT", "Choose half days or full days.");
        BigDecimal h = in.hoursPerUnit();
        if (h == null || h.signum() <= 0 || h.compareTo(new BigDecimal("24")) > 0
            || h.multiply(BigDecimal.valueOf(4)).stripTrailingZeros().scale() > 0) {
            throw bad("BAD_HOURS", "Give the hours for each half or full day, in quarter hours up to 24.");
        }
        if (in.expiry() == null || !EXPIRIES.contains(in.expiry())) throw bad("BAD_EXPIRY", "Choose when earned time off expires.");
        if ("DAYS".equals(in.expiry()) && (in.expiryDays() == null || in.expiryDays() < 1 || in.expiryDays() > 730)) {
            throw bad("BAD_EXPIRY", "Give the number of days earned time off lasts (1 to 730).");
        }
    }

    static boolean validRate(BigDecimal rate) {
        return rate.compareTo(BigDecimal.ONE) >= 0 && rate.compareTo(new BigDecimal("5")) <= 0
            && rate.multiply(BigDecimal.valueOf(4)).stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal orDefault(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    /**
     * The overtime rate for a request: from the kind of day (AUTO, or when the employee didn't pick), else the one
     * the employee picked, which must be one of the rule's rates.
     */
    static BigDecimal rateFor(Rule rule, DayKind kind, BigDecimal asked) {
        if ("AUTO".equals(rule.rateMode()) || asked == null) return rule.rateFor(kind);
        if (rule.rates().stream().noneMatch(x -> x.compareTo(asked) == 0)) {
            throw bad("BAD_RATE", "Choose one of the overtime rates: " + String.join(", ",
                rule.rates().stream().map(x -> x.setScale(Math.max(1, x.scale()), java.math.RoundingMode.UNNECESSARY).toPlainString() + "×").toList()) + ".");
        }
        return asked;
    }

    /** Checked through RLS, so ids from another company count as missing. */
    private boolean exists(String table, UUID id) {
        return jdbc.sql("select exists (select 1 from " + table + " where id = :id)").param("id", id).query(Boolean.class).single();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "That overtime rule doesn't exist.");
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
