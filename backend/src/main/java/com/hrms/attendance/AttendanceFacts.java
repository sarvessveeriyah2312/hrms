package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads what attendance views are built from: people and their legal entity's time zone and working week, sites and
 * their hours, clock-ins, approved leave and public holidays. Shared by Today and timesheets so they agree.
 */
@Component
class AttendanceFacts {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kuala_Lumpur");

    record Emp(UUID id, String employeeNo, String fullName, String department, ZoneId zone, Set<DayOfWeek> workDays,
               LocalDate joinDate) {
    }

    record SiteRef(UUID id, String name, LocalTime startTime, LocalTime endTime, int graceMin) {
        DayStatus.Hours hours() {
            return new DayStatus.Hours(startTime, endTime, graceMin);
        }
    }

    record PunchRow(UUID employeeId, Punch.Type type, Instant at, UUID siteId, String result) {
    }

    record Leave(UUID employeeId, LocalDate start, LocalDate end, DayStatus.LeavePart part, String type) {
    }

    /** A correction request for one day; times are local. Only APPROVED ones change the day. */
    record CorrectionRow(UUID id, UUID employeeId, LocalDate date, LocalTime clockIn, LocalTime clockOut, String status) {
    }

    /** Approved overtime on one day: hours at a rate. */
    record OvertimeRow(UUID employeeId, LocalDate date, BigDecimal hours, BigDecimal rate) {
    }

    /** Approved leave on one day; morning and afternoon on the same day make a full day. */
    record LeaveOn(DayStatus.LeavePart part, String type) {
    }

    private static final String SELECT_EMP = """
            select e.id, e.employee_no, e.full_name, d.name as department, l.time_zone, l.work_days, e.join_date
            from employee e
            join legal_entity l on l.id = e.entity_id
            left join department d on d.id = e.department_id
            """;

    private final JdbcClient jdbc;

    AttendanceFacts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Active people in the scope who had joined by {@code joinedBy}, by name; at most {@code max}. */
    List<Emp> people(UUID me, DataScope scope, LocalDate joinedBy, int max) {
        return jdbc.sql(SELECT_EMP + """
                where %s and e.status = 'ACTIVE' and e.join_date <= :day
                order by e.full_name
                limit :max""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me).param("day", joinedBy).param("max", max)
            .query((rs, i) -> emp(rs))
            .list();
    }

    Optional<Emp> person(UUID employeeId) {
        return jdbc.sql(SELECT_EMP + "where e.id = :id").param("id", employeeId).query((rs, i) -> emp(rs)).optional();
    }

    /** Whether someone with this scope may see the given employee's attendance. */
    boolean canSee(UUID me, DataScope scope, UUID employeeId) {
        if (employeeId.equals(me)) return true;
        return jdbc.sql("select exists (select 1 from employee e where e.id = :target and %s)".formatted(EmployeeScope.predicate(scope, "e")))
            .param("target", employeeId).param("me", me).query(Boolean.class).single();
    }

    /** The caller's time zone (their legal entity's), else the first legal entity's. */
    ZoneId zoneOf(UUID me) {
        String tz = me == null ? null : jdbc.sql("select l.time_zone from employee e join legal_entity l on l.id = e.entity_id where e.id = :me")
            .param("me", me).query(String.class).optional().orElse(null);
        if (tz == null) {
            tz = jdbc.sql("select time_zone from legal_entity order by created_at limit 1").query(String.class).optional().orElse(null);
        }
        return zone(tz);
    }

    Map<UUID, SiteRef> sites() {
        return jdbc.sql("select id, name, start_time, end_time, late_grace_min from site")
            .query((rs, i) -> new SiteRef(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(), rs.getInt("late_grace_min")))
            .list().stream().collect(Collectors.toMap(SiteRef::id, s -> s));
    }

    /** Each person's first active site by name: where they're expected when they haven't clocked in. */
    Map<UUID, UUID> homeSites(List<UUID> ids) {
        Map<UUID, UUID> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.sql("""
                select es.employee_id, s.id as site_id from employee_site es join site s on s.id = es.site_id
                where s.active and es.employee_id in (:ids) order by s.name""")
            .param("ids", ids)
            .query((rs, i) -> Map.entry(rs.getObject("employee_id", UUID.class), rs.getObject("site_id", UUID.class)))
            .list().forEach(e -> out.putIfAbsent(e.getKey(), e.getValue()));
        return out;
    }

    /** Saved clock-ins from the day before {@code from} to the day after {@code to} (UTC), so every time zone is covered. */
    Map<UUID, List<PunchRow>> punches(List<UUID> ids, LocalDate from, LocalDate to) {
        if (ids.isEmpty()) return Map.of();
        Instant start = from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbc.sql("""
                select employee_id, type, server_time, site_id, result from punch
                where employee_id in (:ids) and server_time >= :from and server_time < :to and result <> 'BLOCKED'
                order by server_time""")
            .param("ids", ids).param("from", Timestamp.from(start)).param("to", Timestamp.from(end))
            .query((rs, i) -> new PunchRow(rs.getObject("employee_id", UUID.class), Punch.Type.valueOf(rs.getString("type")),
                rs.getTimestamp("server_time").toInstant(), rs.getObject("site_id", UUID.class), rs.getString("result")))
            .list().stream().collect(Collectors.groupingBy(PunchRow::employeeId, LinkedHashMap::new, Collectors.toList()));
    }

    /** Approved leave overlapping the range, by person. */
    Map<UUID, List<Leave>> leave(List<UUID> ids, LocalDate from, LocalDate to) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql("""
                select r.employee_id, r.start_date, r.end_date, r.day_part, t.name
                from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.status = 'APPROVED' and r.start_date <= :to and r.end_date >= :from and r.employee_id in (:ids)
                order by r.start_date""")
            .param("from", from).param("to", to).param("ids", ids)
            .query((rs, i) -> new Leave(rs.getObject("employee_id", UUID.class), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), DayStatus.LeavePart.valueOf(rs.getString("day_part")), rs.getString("name")))
            .list().stream().collect(Collectors.groupingBy(Leave::employeeId));
    }

    /** Correction requests in the range (not withdrawn), oldest first, by person. */
    Map<UUID, List<CorrectionRow>> corrections(List<UUID> ids, LocalDate from, LocalDate to) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql("""
                select id, employee_id, work_date, clock_in, clock_out, status from attendance_correction
                where employee_id in (:ids) and work_date between :from and :to and status <> 'CANCELLED'
                order by created_at""")
            .param("ids", ids).param("from", from).param("to", to)
            .query((rs, i) -> new CorrectionRow(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getObject("work_date", LocalDate.class), rs.getObject("clock_in", LocalTime.class),
                rs.getObject("clock_out", LocalTime.class), rs.getString("status")))
            .list().stream().collect(Collectors.groupingBy(CorrectionRow::employeeId));
    }

    /** The approved correction that applies on a day (the latest), and the latest request of any status (to show). */
    record CorrectionsOn(CorrectionRow applied, CorrectionRow latest) {
    }

    static CorrectionsOn correctionsOn(List<CorrectionRow> rows, LocalDate day) {
        CorrectionRow applied = null, latest = null;
        for (CorrectionRow c : rows == null ? List.<CorrectionRow>of() : rows) {
            if (!c.date().equals(day)) continue;
            latest = c;
            if ("APPROVED".equals(c.status())) applied = c;
        }
        return new CorrectionsOn(applied, latest);
    }

    /** Approved overtime in the range, by person. */
    Map<UUID, List<OvertimeRow>> approvedOvertime(List<UUID> ids, LocalDate from, LocalDate to) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql("""
                select employee_id, work_date, hours, rate from overtime_request
                where employee_id in (:ids) and work_date between :from and :to and status = 'APPROVED'
                order by work_date, created_at""")
            .param("ids", ids).param("from", from).param("to", to)
            .query((rs, i) -> new OvertimeRow(rs.getObject("employee_id", UUID.class), rs.getObject("work_date", LocalDate.class),
                rs.getBigDecimal("hours"), rs.getBigDecimal("rate")))
            .list().stream().collect(Collectors.groupingBy(OvertimeRow::employeeId));
    }

    /** Approved overtime on a day, in minutes. */
    static int overtimeMinOn(List<OvertimeRow> rows, LocalDate day) {
        int min = 0;
        for (OvertimeRow o : rows == null ? List.<OvertimeRow>of() : rows) {
            if (o.date().equals(day)) min += o.hours().multiply(BigDecimal.valueOf(60)).intValue();
        }
        return min;
    }

    Map<LocalDate, String> holidays(LocalDate from, LocalDate to) {
        Map<LocalDate, String> out = new HashMap<>();
        jdbc.sql("select date, name from public_holiday where date between :from and :to").param("from", from).param("to", to)
            .query((rs, i) -> Map.entry(rs.getObject("date", LocalDate.class), rs.getString("name")))
            .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** The leave someone has on a day, if any. */
    static LeaveOn leaveOn(List<Leave> leave, LocalDate day) {
        LeaveOn out = null;
        for (Leave l : leave == null ? List.<Leave>of() : leave) {
            if (day.isBefore(l.start()) || day.isAfter(l.end())) continue;
            if (out == null) {
                out = new LeaveOn(l.part(), l.type());
            } else if (out.part() != DayStatus.LeavePart.FULL && l.part() != out.part()) {
                out = new LeaveOn(DayStatus.LeavePart.FULL, out.type().equals(l.type()) ? out.type() : out.type() + ", " + l.type());
            }
        }
        return out;
    }

    /** The person's clock-ins on their own local day. */
    static List<PunchRow> on(List<PunchRow> punches, LocalDate day, ZoneId zone) {
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        return (punches == null ? List.<PunchRow>of() : punches).stream()
            .filter(p -> !p.at().isBefore(start) && p.at().isBefore(end)).toList();
    }

    static LocalTime local(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalTime().withSecond(0).withNano(0);
    }

    static ZoneId zone(String tz) {
        try {
            return tz == null ? DEFAULT_ZONE : ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    private static Emp emp(java.sql.ResultSet rs) throws SQLException {
        return new Emp(rs.getObject("id", UUID.class), rs.getString("employee_no"), rs.getString("full_name"),
            rs.getString("department"), zone(rs.getString("time_zone")), workDays(rs.getArray("work_days")),
            rs.getObject("join_date", LocalDate.class));
    }

    private static Set<DayOfWeek> workDays(Array array) throws SQLException {
        if (array == null) return EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        return Arrays.stream((Object[]) array.getArray()).map(d -> DayOfWeek.of(((Number) d).intValue()))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }
}
