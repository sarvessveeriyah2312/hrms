package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.web.ApiException;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Today for managers: everyone the caller's attendance:view scope covers, with their first clock-in, whether they
 * were late, not in yet, on approved leave or off (rest day or public holiday), by site. See {@link DayStatus}.
 * Each person's day and times are in their legal entity's time zone.
 */
@Service
class TodayService {

    /** Larger scopes are cut off here; the response says so. */
    static final int MAX_PEOPLE = 2000;

    record SiteRef(UUID id, String name, LocalTime startTime, LocalTime endTime, int graceMin) {
    }

    /**
     * One person's day. Times are local ("09:12"). site: where they clocked in, else the first site they belong to.
     * offReason: HOLIDAY or REST_DAY when the day isn't a working day.
     */
    record Person(UUID employeeId, String employeeNo, String fullName, String department, UUID siteId, String site,
                  DayStatus.Status status, LocalTime expectedAt, LocalTime firstIn, LocalTime lastOut, boolean stillIn,
                  int minutesLate, boolean overdue, boolean flagged, String leaveType, DayStatus.LeavePart leavePart,
                  String offReason) {
    }

    record Today(LocalDate date, boolean isToday, DataScope scope, String holiday, List<SiteRef> sites,
                 List<Person> people, boolean truncated) {
    }

    private record Emp(UUID id, String employeeNo, String fullName, String department, String timeZone, Set<DayOfWeek> workDays) {
    }

    private record PunchRow(UUID employeeId, Punch.Type type, Instant at, UUID siteId, String result) {
    }

    private record Leave(UUID employeeId, DayStatus.LeavePart part, String type) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    TodayService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    Today today(UUID me, DataScope scope, LocalDate requested) {
        ZoneId myZone = zoneOf(me);
        LocalDate todayHere = LocalDate.now(clock.withZone(myZone));
        LocalDate day = requested == null ? todayHere : requested;
        if (day.isAfter(todayHere)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FUTURE_DATE", "Pick today or an earlier day.");
        }

        List<Emp> people = jdbc.sql("""
                select e.id, e.employee_no, e.full_name, d.name as department, l.time_zone, l.work_days
                from employee e
                join legal_entity l on l.id = e.entity_id
                left join department d on d.id = e.department_id
                where %s and e.status = 'ACTIVE' and e.join_date <= :day
                order by e.full_name
                limit :max""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me).param("day", day).param("max", MAX_PEOPLE + 1)
            .query((rs, i) -> new Emp(rs.getObject("id", UUID.class), rs.getString("employee_no"), rs.getString("full_name"),
                rs.getString("department"), rs.getString("time_zone"), workDays(rs.getArray("work_days"))))
            .list();
        boolean truncated = people.size() > MAX_PEOPLE;
        if (truncated) people = people.subList(0, MAX_PEOPLE);

        String holiday = jdbc.sql("select name from public_holiday where date = :day").param("day", day)
            .query(String.class).optional().orElse(null);
        Map<UUID, SiteRef> sites = jdbc.sql("select id, name, start_time, end_time, late_grace_min from site")
            .query((rs, i) -> new SiteRef(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(), rs.getInt("late_grace_min")))
            .list().stream().collect(Collectors.toMap(SiteRef::id, s -> s));

        if (people.isEmpty()) return new Today(day, day.equals(todayHere), scope, holiday, List.of(), List.of(), false);
        List<UUID> ids = people.stream().map(Emp::id).toList();

        // First active site by name, for people who haven't clocked in.
        Map<UUID, UUID> homeSite = new HashMap<>();
        jdbc.sql("""
                select es.employee_id, s.id as site_id from employee_site es join site s on s.id = es.site_id
                where s.active and es.employee_id in (:ids) order by s.name""")
            .param("ids", ids)
            .query((rs, i) -> Map.entry(rs.getObject("employee_id", UUID.class), rs.getObject("site_id", UUID.class)))
            .list().forEach(e -> homeSite.putIfAbsent(e.getKey(), e.getValue()));

        // A day either side covers every time zone; each person's own day is picked out below.
        Instant from = day.minusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant to = day.plusDays(2).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Map<UUID, List<PunchRow>> punches = jdbc.sql("""
                select employee_id, type, server_time, site_id, result from punch
                where employee_id in (:ids) and server_time >= :from and server_time < :to and result <> 'BLOCKED'
                order by server_time""")
            .param("ids", ids).param("from", java.sql.Timestamp.from(from)).param("to", java.sql.Timestamp.from(to))
            .query((rs, i) -> new PunchRow(rs.getObject("employee_id", UUID.class), Punch.Type.valueOf(rs.getString("type")),
                rs.getTimestamp("server_time").toInstant(), rs.getObject("site_id", UUID.class), rs.getString("result")))
            .list().stream().collect(Collectors.groupingBy(PunchRow::employeeId, LinkedHashMap::new, Collectors.toList()));

        Map<UUID, Leave> leave = new HashMap<>();
        jdbc.sql("""
                select r.employee_id, r.day_part, t.name from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.status = 'APPROVED' and :day between r.start_date and r.end_date and r.employee_id in (:ids)
                order by r.start_date""")
            .param("day", day).param("ids", ids)
            .query((rs, i) -> new Leave(rs.getObject("employee_id", UUID.class), DayStatus.LeavePart.valueOf(rs.getString("day_part")), rs.getString("name")))
            .list().forEach(l -> leave.merge(l.employeeId(), l, TodayService::combine));

        List<Person> out = new ArrayList<>(people.size());
        for (Emp e : people) {
            ZoneId zone = zone(e.timeZone());
            Instant dayStart = day.atStartOfDay(zone).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
            List<PunchRow> mine = punches.getOrDefault(e.id(), List.of()).stream()
                .filter(p -> !p.at().isBefore(dayStart) && p.at().isBefore(dayEnd)).toList();
            PunchRow firstIn = mine.stream().filter(p -> p.type() == Punch.Type.IN).findFirst().orElse(null);
            PunchRow last = mine.isEmpty() ? null : mine.getLast();

            SiteRef site = firstIn != null && firstIn.siteId() != null ? sites.get(firstIn.siteId()) : sites.get(homeSite.get(e.id()));
            DayStatus.Hours hours = site == null ? DayStatus.Hours.DEFAULT : new DayStatus.Hours(site.startTime(), site.endTime(), site.graceMin());
            boolean workingDay = holiday == null && e.workDays().contains(day.getDayOfWeek());
            Leave l = leave.get(e.id());

            var result = DayStatus.of(new DayStatus.Input(workingDay, l == null ? null : l.part(),
                firstIn == null ? null : local(firstIn.at(), zone), hours, LocalDateTime.now(clock.withZone(zone)), day));

            out.add(new Person(e.id(), e.employeeNo(), e.fullName(), e.department(), site == null ? null : site.id(),
                site == null ? null : site.name(), result.status(), result.expectedAt(),
                firstIn == null ? null : local(firstIn.at(), zone),
                last != null && last.type() == Punch.Type.OUT ? local(last.at(), zone) : null,
                last != null && last.type() == Punch.Type.IN,
                result.minutesLate(), result.overdue(), mine.stream().anyMatch(p -> "FLAGGED".equals(p.result())),
                l == null ? null : l.type(), l == null ? null : l.part(),
                workingDay ? null : holiday != null ? "HOLIDAY" : "REST_DAY"));
        }

        List<SiteRef> used = out.stream().map(Person::siteId).filter(java.util.Objects::nonNull).distinct()
            .map(sites::get).sorted(Comparator.comparing(SiteRef::name, String.CASE_INSENSITIVE_ORDER)).toList();
        return new Today(day, day.equals(todayHere), scope, holiday, used, out, truncated);
    }

    /** Morning and afternoon leave on the same day make a full day. */
    private static Leave combine(Leave a, Leave b) {
        if (a.part() == DayStatus.LeavePart.FULL || a.part() == b.part()) return a;
        if (b.part() == DayStatus.LeavePart.FULL) return b;
        return new Leave(a.employeeId(), DayStatus.LeavePart.FULL, a.type().equals(b.type()) ? a.type() : a.type() + ", " + b.type());
    }

    private ZoneId zoneOf(UUID me) {
        String tz = me == null ? null : jdbc.sql("select l.time_zone from employee e join legal_entity l on l.id = e.entity_id where e.id = :me")
            .param("me", me).query(String.class).optional().orElse(null);
        if (tz == null) {
            tz = jdbc.sql("select time_zone from legal_entity order by created_at limit 1").query(String.class).optional().orElse(null);
        }
        return zone(tz);
    }

    private static ZoneId zone(String tz) {
        try {
            return tz == null ? ZoneId.of("Asia/Kuala_Lumpur") : ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            return ZoneId.of("Asia/Kuala_Lumpur");
        }
    }

    private static LocalTime local(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalTime().withSecond(0).withNano(0);
    }

    private static Set<DayOfWeek> workDays(Array array) throws SQLException {
        if (array == null) return java.util.EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        return Arrays.stream((Object[]) array.getArray()).map(d -> DayOfWeek.of(((Number) d).intValue()))
            .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(DayOfWeek.class)));
    }
}
