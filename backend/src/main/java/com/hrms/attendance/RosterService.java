package com.hrms.attendance;

import com.hrms.attendance.AttendanceFacts.Emp;
import com.hrms.notification.Notifications;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rosters: a site's week of shifts, planned in a working copy and published for the people on it. Planners need
 * attendance:roster and see (and plan) only the people in its scope. Publishing checks the week first
 * ({@link RosterChecks}), copies it to published_shift and tells everyone whose shifts changed.
 */
@Service
class RosterService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final int MAX_NOTE = 200;
    static final int MAX_SCHEDULE_DAYS = 62;

    record Site(UUID id, String name) {
    }

    record Shift(UUID id, UUID siteId, UUID employeeId, LocalDate date, LocalTime startTime, LocalTime endTime, int breakMin,
                 int graceMin, UUID templateId, String code, String tone, String note) {
    }

    /**
     * leave: approved leave by day ("2026-11-10" → "Annual leave (AM)"). workDays: ISO day numbers. availability: the
     * weekdays they've limited (others are any time).
     */
    record Person(UUID id, String employeeNo, String fullName, String department, List<Integer> workDays,
                  Map<LocalDate, String> leave, boolean member, List<Availability.Day> availability) {
    }

    /** published: the week has been published; changed: the working copy changed since (or was never published). */
    record Status(boolean published, Instant publishedAt, String publishedBy, boolean changed) {
    }

    record Week(Site site, LocalDate weekStart, List<LocalDate> days, Map<LocalDate, String> holidays, List<Person> people,
                List<Shift> shifts, Status status, List<RosterChecks.Problem> problems, boolean locked) {
    }

    /** templateId fills in times not given. */
    record ShiftInput(UUID siteId, UUID employeeId, LocalDate date, UUID templateId, LocalTime startTime, LocalTime endTime,
                      Integer breakMin, Integer graceMin, String note) {
    }

    record CopyResult(int copied, int skipped) {
    }

    /** A published shift on someone's own schedule. */
    record MyShift(UUID id, LocalDate date, LocalTime startTime, LocalTime endTime, int breakMin, String code, String tone,
                   String note, UUID siteId, String site) {
    }

    private final JdbcClient jdbc;
    private final AttendanceFacts facts;
    private final ShiftTemplates templates;
    private final PeriodLocks locks;
    private final Notifications notifications;
    private final AuditLog audit;
    private final Availability availability;

    RosterService(JdbcClient jdbc, AttendanceFacts facts, ShiftTemplates templates, PeriodLocks locks, Notifications notifications,
                  AuditLog audit, Availability availability) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.templates = templates;
        this.locks = locks;
        this.notifications = notifications;
        this.audit = audit;
        this.availability = availability;
    }

    static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ---------- reading ----------

    List<Site> sites() {
        return jdbc.sql("select id, name from site where active order by name").query(Site.class).list();
    }

    @Transactional(readOnly = true)
    Week week(UUID me, DataScope scope, UUID siteId, LocalDate anyDay) {
        Site site = site(siteId);
        LocalDate start = monday(anyDay);
        LocalDate end = start.plusDays(6);
        List<Shift> shifts = drafts(siteId, start, end, me, scope);

        // People at the site in your scope, and anyone in scope who already has shifts here this week.
        Set<UUID> members = new HashSet<>(jdbc.sql("""
                select es.employee_id from employee_site es join employee e on e.id = es.employee_id
                where es.site_id = :site and e.status = 'ACTIVE' and e.join_date <= :end and %s""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("site", siteId).param("end", end).param("me", me).query(UUID.class).list());
        Set<UUID> ids = new HashSet<>(members);
        shifts.forEach(s -> { if (s.employeeId() != null) ids.add(s.employeeId()); });
        List<Emp> emps = ids.stream().map(facts::person).flatMap(java.util.Optional::stream)
            .sorted(java.util.Comparator.comparing(Emp::fullName)).toList();
        List<UUID> idList = emps.stream().map(Emp::id).toList();
        Map<UUID, List<AttendanceFacts.Leave>> leave = facts.leave(idList, start, end);
        Map<LocalDate, String> holidays = facts.holidays(start, end);
        Map<UUID, Map<Integer, Availability.Day>> avail = availability.forPeople(idList);

        List<Person> people = emps.stream().map(e -> {
            Map<LocalDate, String> l = new LinkedHashMap<>();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                var on = AttendanceFacts.leaveOn(leave.get(e.id()), d);
                if (on != null) l.put(d, on.type() + (on.part() == DayStatus.LeavePart.FULL ? "" : " (" + on.part() + ")"));
            }
            return new Person(e.id(), e.employeeNo(), e.fullName(), e.department(),
                e.workDays().stream().map(DayOfWeek::getValue).sorted().toList(), l, members.contains(e.id()),
                avail.getOrDefault(e.id(), Map.of()).values().stream().sorted(java.util.Comparator.comparing(Availability.Day::weekday)).toList());
        }).toList();

        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) days.add(d);
        boolean locked = days.stream().anyMatch(locks::isLocked);
        return new Week(site, start, days, holidays, people, shifts, status(siteId, start),
            problems(siteId, start, shifts, emps, leave, holidays, avail), locked);
    }

    /** A shift on a site's published roster, as colleagues see it. employeeId null: open. */
    record SiteShift(UUID id, UUID employeeId, LocalDate date, LocalTime startTime, LocalTime endTime, String code, String tone) {
    }

    /** sites: the sites you're at (to switch between). people: everyone with a published shift there that week, and you. */
    record SiteRoster(List<Site> sites, Site site, LocalDate weekStart, List<LocalDate> days, List<Site> people, List<SiteShift> shifts) {
    }

    /**
     * The published roster of one of your sites for a week: names and times only (no leave, availability or notes).
     * Without siteId, your first site. Sites you aren't at are refused.
     */
    @Transactional(readOnly = true)
    SiteRoster siteRoster(UUID me, UUID siteId, LocalDate anyDay) {
        List<Site> mine = jdbc.sql("""
                select s.id, s.name from employee_site es join site s on s.id = es.site_id
                where es.employee_id = :me and s.active order by s.name""").param("me", me).query(Site.class).list();
        if (mine.isEmpty()) return new SiteRoster(List.of(), null, monday(anyDay), List.of(), List.of(), List.of());
        Site site = siteId == null ? mine.get(0) : mine.stream().filter(s -> s.id().equals(siteId)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "That site doesn't exist."));
        LocalDate start = monday(anyDay);
        LocalDate end = start.plusDays(6);
        List<SiteShift> shifts = jdbc.sql("""
                select id, employee_id, work_date as date, start_time, end_time, code, tone from published_shift
                where site_id = :site and work_date between :from and :to order by work_date, start_time""")
            .param("site", site.id()).param("from", start).param("to", end).query(SiteShift.class).list();
        Set<UUID> ids = new HashSet<>();
        ids.add(me);
        shifts.forEach(s -> { if (s.employeeId() != null) ids.add(s.employeeId()); });
        List<Site> people = jdbc.sql("select id, full_name as name from employee where id in (:ids) order by full_name")
            .param("ids", ids).query(Site.class).list();
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) days.add(d);
        return new SiteRoster(mine, site, start, days, people, shifts);
    }

    /** Your published shifts at every site, from..to (up to 62 days). */
    List<MyShift> mySchedule(UUID employeeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_SCHEDULE_DAYS) {
            throw ShiftTemplates.bad("BAD_RANGE", "Pick up to " + MAX_SCHEDULE_DAYS + " days, with the end on or after the start.");
        }
        return jdbc.sql("""
                select p.id, p.work_date as date, p.start_time, p.end_time, p.break_min, p.code, p.tone, p.note, p.site_id, s.name as site
                from published_shift p join site s on s.id = p.site_id
                where p.employee_id = :emp and p.work_date between :from and :to order by p.work_date, p.start_time""")
            .param("emp", employeeId).param("from", from).param("to", to).query(MyShift.class).list();
    }

    // ---------- planning ----------

    @Transactional
    Shift add(UUID me, DataScope scope, ShiftInput in) {
        ShiftInput full = complete(in);
        checkShift(me, scope, full, null);
        UUID id = UUID.randomUUID();
        var t = full.templateId() == null ? null : templates.find(full.templateId());
        jdbc.sql("""
                insert into roster_shift (id, tenant_id, site_id, employee_id, work_date, start_time, end_time, break_min, grace_min,
                                          template_id, code, tone, note)
                values (:id, :tenant, :site, :emp, :date, :start, :end, :break, :grace, :template, :code, :tone, :note)""")
            .param("id", id).param("tenant", TenantContext.require()).param("site", full.siteId()).param("emp", full.employeeId())
            .param("date", full.date()).param("start", full.startTime()).param("end", full.endTime())
            .param("break", full.breakMin()).param("grace", full.graceMin()).param("template", full.templateId())
            .param("code", t == null ? null : t.code()).param("tone", t == null ? "blue" : t.tone())
            .param("note", blankToNull(full.note())).update();
        touch(full.siteId(), full.date());
        return draft(id);
    }

    /** Change a shift's times, or move it to another person or day (same site). */
    @Transactional
    Shift update(UUID me, DataScope scope, UUID id, ShiftInput in) {
        Shift old = draft(id);
        requireInScope(me, scope, old.employeeId());
        ShiftInput full = complete(new ShiftInput(old.siteId(), in.employeeId() == null ? old.employeeId() : in.employeeId(),
            in.date() == null ? old.date() : in.date(), in.templateId(),
            in.startTime() == null && in.templateId() == null ? old.startTime() : in.startTime(),
            in.endTime() == null && in.templateId() == null ? old.endTime() : in.endTime(),
            in.breakMin() == null && in.templateId() == null ? old.breakMin() : in.breakMin(),
            in.graceMin() == null && in.templateId() == null ? old.graceMin() : in.graceMin(),
            in.note() == null ? old.note() : in.note()));
        checkShift(me, scope, full, id);
        locks.requireOpen(old.date(), "roster");
        var t = full.templateId() == null ? null : templates.find(full.templateId());
        jdbc.sql("""
                update roster_shift set employee_id = :emp, work_date = :date, start_time = :start, end_time = :end,
                       break_min = :break, grace_min = :grace, note = :note, updated_at = now(),
                       template_id = coalesce(:template, template_id), code = coalesce(:code, code), tone = coalesce(:tone, tone)
                where id = :id""")
            .param("emp", full.employeeId()).param("date", full.date()).param("start", full.startTime()).param("end", full.endTime())
            .param("break", full.breakMin()).param("grace", full.graceMin()).param("note", blankToNull(full.note()))
            .param("template", full.templateId()).param("code", t == null ? null : t.code()).param("tone", t == null ? null : t.tone())
            .param("id", id).update();
        touch(old.siteId(), old.date());
        touch(old.siteId(), full.date());
        return draft(id);
    }

    @Transactional
    void delete(UUID me, DataScope scope, UUID id) {
        Shift old = draft(id);
        requireInScope(me, scope, old.employeeId());
        locks.requireOpen(old.date(), "roster");
        jdbc.sql("delete from roster_shift where id = :id").param("id", id).update();
        touch(old.siteId(), old.date());
    }

    /** Copies last week's shifts at the site onto the same weekdays, for people with nothing planned there that day. */
    @Transactional
    CopyResult copyPreviousWeek(UUID me, DataScope scope, UUID siteId, LocalDate anyDay) {
        site(siteId);
        LocalDate start = monday(anyDay);
        requireWeekOpen(start);
        List<Shift> previous = drafts(siteId, start.minusDays(7), start.minusDays(1), me, scope);
        Set<String> taken = drafts(siteId, start, start.plusDays(6), me, scope).stream()
            .map(s -> s.employeeId() + "|" + s.date()).collect(Collectors.toSet());
        int copied = 0, skipped = 0;
        for (Shift s : previous) {
            LocalDate date = s.date().plusDays(7);
            // Days that already had shifts before copying are left alone (a day with two shifts last week copies both).
            if (s.employeeId() != null && taken.contains(s.employeeId() + "|" + date)) {
                skipped++;
                continue;
            }
            try {
                checkShift(me, scope, new ShiftInput(siteId, s.employeeId(), date, null, s.startTime(), s.endTime(), s.breakMin(),
                    s.graceMin(), s.note()), null);
            } catch (ApiException e) {
                skipped++;
                continue;
            }
            jdbc.sql("""
                    insert into roster_shift (tenant_id, site_id, employee_id, work_date, start_time, end_time, break_min, grace_min,
                                              template_id, code, tone, note)
                    values (:tenant, :site, :emp, :date, :start, :end, :break, :grace, :template, :code, :tone, :note)""")
                .param("tenant", TenantContext.require()).param("site", siteId).param("emp", s.employeeId()).param("date", date)
                .param("start", s.startTime()).param("end", s.endTime()).param("break", s.breakMin()).param("grace", s.graceMin())
                .param("template", s.templateId()).param("code", s.code()).param("tone", s.tone()).param("note", s.note()).update();
            copied++;
        }
        if (copied > 0) touch(siteId, start);
        return new CopyResult(copied, skipped);
    }

    /** Removes every planned shift at the site this week (in your scope); what's published stays until you publish again. */
    @Transactional
    int clear(UUID me, DataScope scope, UUID siteId, LocalDate anyDay) {
        LocalDate start = monday(anyDay);
        requireWeekOpen(start);
        List<UUID> ids = drafts(siteId, start, start.plusDays(6), me, scope).stream().map(Shift::id).toList();
        if (ids.isEmpty()) return 0;
        jdbc.sql("delete from roster_shift where id in (:ids)").param("ids", ids).update();
        touch(siteId, start);
        return ids.size();
    }

    // ---------- publishing ----------

    /**
     * Publishes the site's week: errors stop it; warnings need {@code acknowledge}. Everyone whose published shifts
     * changed is told. Publishes the whole site's week, not only the people in your scope, so planners with a narrower
     * scope can only publish when the week has nobody outside it.
     */
    @Transactional
    Week publish(UUID actor, UUID me, DataScope scope, UUID siteId, LocalDate anyDay, boolean acknowledge) {
        LocalDate start = monday(anyDay);
        LocalDate end = start.plusDays(6);
        requireWeekOpen(start);
        requireWholeWeekInScope(me, scope, siteId, start, end);
        Week w = week(me, scope, siteId, start);
        List<RosterChecks.Problem> errors = w.problems().stream().filter(p -> "ERROR".equals(p.severity())).toList();
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROSTER_HAS_ERRORS", "Fix the problems marked in red before publishing.")
                .with("problems", errors);
        }
        if (!acknowledge && !w.problems().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROSTER_HAS_WARNINGS", "Check the warnings, then publish again to confirm.")
                .with("problems", w.problems());
        }
        Map<UUID, Set<String>> before = publishedByPerson(siteId, start, end);
        jdbc.sql("delete from published_shift where site_id = :site and work_date between :from and :to")
            .param("site", siteId).param("from", start).param("to", end).update();
        jdbc.sql("""
                insert into published_shift (id, tenant_id, site_id, employee_id, work_date, start_time, end_time, break_min, grace_min,
                                             code, tone, note)
                select id, tenant_id, site_id, employee_id, work_date, start_time, end_time, break_min, grace_min, code, tone, note
                from roster_shift where site_id = :site and work_date between :from and :to""")
            .param("site", siteId).param("from", start).param("to", end).update();
        jdbc.sql("""
                insert into roster_week (tenant_id, site_id, week_start, published_at, published_by) values (:tenant, :site, :week, now(), :actor)
                on conflict (tenant_id, site_id, week_start) do update set published_at = now(), published_by = :actor""")
            .param("tenant", TenantContext.require()).param("site", siteId).param("week", start).param("actor", actor).update();
        Map<UUID, Set<String>> after = publishedByPerson(siteId, start, end);
        notifyChanged(before, after, w.site(), start, false);
        notifyOpenShifts(before.getOrDefault(OPEN, Set.of()), after.getOrDefault(OPEN, Set.of()), w.site(), start);
        audit.record(actor, "ROSTER_PUBLISHED", "site", siteId, Map.of("week", start.toString(), "shifts", w.shifts().size()));
        return week(me, scope, siteId, start);
    }

    @Transactional
    Week unpublish(UUID actor, UUID me, DataScope scope, UUID siteId, LocalDate anyDay) {
        LocalDate start = monday(anyDay);
        LocalDate end = start.plusDays(6);
        requireWeekOpen(start);
        requireWholeWeekInScope(me, scope, siteId, start, end);
        Site site = site(siteId);
        Map<UUID, Set<String>> before = publishedByPerson(siteId, start, end);
        jdbc.sql("delete from published_shift where site_id = :site and work_date between :from and :to")
            .param("site", siteId).param("from", start).param("to", end).update();
        jdbc.sql("update roster_week set published_at = null, published_by = null where site_id = :site and week_start = :week")
            .param("site", siteId).param("week", start).update();
        notifyChanged(before, Map.of(), site, start, true);
        audit.record(actor, "ROSTER_UNPUBLISHED", "site", siteId, Map.of("week", start.toString()));
        return week(me, scope, siteId, start);
    }

    /** The key open shifts are kept under in {@link #publishedByPerson}. */
    private static final UUID OPEN = new UUID(0, 0);

    /** New open shifts: tell the people at the site, who can claim them on My schedule. */
    private void notifyOpenShifts(Set<String> before, Set<String> after, Site site, LocalDate start) {
        Set<String> added = new HashSet<>(after);
        added.removeAll(before);
        if (added.isEmpty()) return;
        List<UUID> members = jdbc.sql("""
                select es.employee_id from employee_site es join employee e on e.id = es.employee_id
                where es.site_id = :site and e.status = 'ACTIVE'""").param("site", site.id()).query(UUID.class).list();
        String week = start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        notifications.toEmployees(members, new Notifications.Message(Notifications.Kind.SCHEDULE_PUBLISHED,
            added.size() == 1 ? "An open shift at " + site.name() : added.size() + " open shifts at " + site.name(),
            "Week of " + week + ". Claim one on My schedule.", "/app/attendance?tab=schedule&week=" + start, "blue"));
    }

    private void notifyChanged(Map<UUID, Set<String>> before, Map<UUID, Set<String>> after, Site site, LocalDate start, boolean withdrawn) {
        Set<UUID> people = new HashSet<>(before.keySet());
        people.addAll(after.keySet());
        people.remove(OPEN);
        String week = start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
        String link = "/app/attendance?tab=schedule&week=" + start;
        for (UUID p : people) {
            Set<String> was = before.getOrDefault(p, Set.of());
            Set<String> now = after.getOrDefault(p, Set.of());
            if (was.equals(now)) continue;
            String title = withdrawn ? "Your schedule for the week of " + week + " was taken back"
                : was.isEmpty() ? "Your schedule for the week of " + week + " is out" : "Your schedule for the week of " + week + " changed";
            String body = withdrawn ? site.name() + ": wait for the new schedule."
                : now.isEmpty() ? site.name() + ": you have no shifts there that week."
                : site.name() + ": " + now.size() + (now.size() == 1 ? " shift" : " shifts");
            notifications.toEmployee(p, new Notifications.Message(Notifications.Kind.SCHEDULE_PUBLISHED, title, body, link, "blue"));
        }
    }

    /** What each person has published at the site that week, as comparable text. */
    private Map<UUID, Set<String>> publishedByPerson(UUID siteId, LocalDate from, LocalDate to) {
        Map<UUID, Set<String>> out = new HashMap<>();
        jdbc.sql("""
                select employee_id, work_date, start_time, end_time, break_min from published_shift
                where site_id = :site and work_date between :from and :to""")
            .param("site", siteId).param("from", from).param("to", to)
            .query((rs, i) -> {
                UUID who = rs.getObject("employee_id", UUID.class);
                out.computeIfAbsent(who == null ? OPEN : who, k -> new HashSet<>()).add(
                    rs.getObject("work_date", LocalDate.class) + " " + rs.getObject("start_time", LocalTime.class) + "-"
                        + rs.getObject("end_time", LocalTime.class) + " " + rs.getInt("break_min"));
                return null;
            }).list();
        return out;
    }

    // ---------- checks ----------

    private List<RosterChecks.Problem> problems(UUID siteId, LocalDate start, List<Shift> shifts, List<Emp> emps,
                                                Map<UUID, List<AttendanceFacts.Leave>> leave, Map<LocalDate, String> holidays,
                                                Map<UUID, Map<Integer, Availability.Day>> avail) {
        shifts = shifts.stream().filter(s -> s.employeeId() != null).toList();
        if (shifts.isEmpty()) return List.of();
        Map<UUID, String> names = emps.stream().collect(Collectors.toMap(Emp::id, Emp::fullName));
        Map<UUID, Set<DayOfWeek>> week = emps.stream().collect(Collectors.toMap(Emp::id, Emp::workDays));
        String siteName = site(siteId).name();
        List<RosterChecks.Shift> all = new ArrayList<>();
        shifts.forEach(s -> all.add(new RosterChecks.Shift(s.id(), s.employeeId(), names.get(s.employeeId()), siteName, s.date(),
            s.startTime(), s.endTime(), true)));
        // Other sites' published shifts for the same people, the day before through the week, for overlaps and rest.
        List<UUID> ids = shifts.stream().map(Shift::employeeId).distinct().toList();
        jdbc.sql("""
                select p.id, p.employee_id, s.name as site, p.work_date, p.start_time, p.end_time from published_shift p
                join site s on s.id = p.site_id
                where p.employee_id in (:ids) and p.site_id <> :site and p.work_date between :from and :to""")
            .param("ids", ids).param("site", siteId).param("from", start.minusDays(1)).param("to", start.plusDays(6))
            .query((rs, i) -> all.add(new RosterChecks.Shift(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                names.get(rs.getObject("employee_id", UUID.class)), rs.getString("site"), rs.getObject("work_date", LocalDate.class),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class), false))).list();
        // This site's shifts the day before the week (published), for rest between Sunday and Monday.
        jdbc.sql("""
                select id, employee_id, work_date, start_time, end_time from published_shift
                where employee_id in (:ids) and site_id = :site and work_date = :day""")
            .param("ids", ids).param("site", siteId).param("day", start.minusDays(1))
            .query((rs, i) -> all.add(new RosterChecks.Shift(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                names.get(rs.getObject("employee_id", UUID.class)), siteName, rs.getObject("work_date", LocalDate.class),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class), false))).list();
        return RosterChecks.check(all, week, leave, holidays, avail);
    }

    /** Fills in times from the template; defaults for break and grace. */
    private ShiftInput complete(ShiftInput in) {
        if (in == null) throw ShiftTemplates.bad("SHIFT_REQUIRED", "Give the shift's details.");
        if (in.templateId() == null) {
            return new ShiftInput(in.siteId(), in.employeeId(), in.date(), null, in.startTime(), in.endTime(),
                in.breakMin() == null ? 0 : in.breakMin(), in.graceMin() == null ? 5 : in.graceMin(), in.note());
        }
        var t = templates.find(in.templateId());
        return new ShiftInput(in.siteId(), in.employeeId(), in.date(), t.id(),
            in.startTime() == null ? t.startTime() : in.startTime(), in.endTime() == null ? t.endTime() : in.endTime(),
            in.breakMin() == null ? t.breakMin() : in.breakMin(), in.graceMin() == null ? t.graceMin() : in.graceMin(), in.note());
    }

    /** Site, person in scope, an open month, sensible times, and no overlap or fourth shift that day (any site). */
    private void checkShift(UUID me, DataScope scope, ShiftInput in, UUID except) {
        if (in.siteId() == null) throw ShiftTemplates.bad("SITE_REQUIRED", "Choose the site.");
        site(in.siteId());
        if (in.date() == null) throw ShiftTemplates.bad("DATE_REQUIRED", "Choose the day.");
        if (in.employeeId() == null) {
            // An open shift: nobody on it yet, so only the site, month and times matter.
            locks.requireOpen(in.date(), "roster");
            ShiftTemplates.checkTimes(in.startTime(), in.endTime(), in.breakMin(), in.graceMin());
            if (in.note() != null && in.note().trim().length() > MAX_NOTE) throw ShiftTemplates.bad("NOTE_TOO_LONG", "Keep the note under " + MAX_NOTE + " characters.");
            return;
        }
        requireInScope(me, scope, in.employeeId());
        Emp e = facts.person(in.employeeId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "That person doesn't exist."));
        if (e.joinDate() != null && in.date().isBefore(e.joinDate())) throw ShiftTemplates.bad("BEFORE_JOINING", e.fullName() + " hasn't joined by then.");
        locks.requireOpen(in.date(), "roster");
        ShiftTemplates.checkTimes(in.startTime(), in.endTime(), in.breakMin(), in.graceMin());
        if (in.note() != null && in.note().trim().length() > MAX_NOTE) throw ShiftTemplates.bad("NOTE_TOO_LONG", "Keep the note under " + MAX_NOTE + " characters.");

        record Other(UUID id, LocalTime startTime, LocalTime endTime, String site) {
        }
        List<Other> sameDay = jdbc.sql("""
                select r.id, r.start_time, r.end_time, s.name as site from roster_shift r join site s on s.id = r.site_id
                where r.employee_id = :emp and r.work_date = :date""")
            .param("emp", in.employeeId()).param("date", in.date()).query(Other.class).list()
            .stream().filter(o -> !o.id().equals(except)).toList();
        if (sameDay.size() >= RosterChecks.MAX_PER_DAY) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_SHIFTS", e.fullName() + " already has " + RosterChecks.MAX_PER_DAY
                + " shifts on " + in.date().format(DAY) + ".");
        }
        for (Other o : sameDay) {
            if (in.startTime().isBefore(o.endTime()) && o.startTime().isBefore(in.endTime())) {
                throw new ApiException(HttpStatus.CONFLICT, "SHIFT_OVERLAPS", e.fullName() + " already works " + o.startTime() + "–"
                    + o.endTime() + " at " + o.site() + " on " + in.date().format(DAY) + ".");
            }
        }
    }

    private void requireInScope(UUID me, DataScope scope, UUID employeeId) {
        if (employeeId != null && !facts.canSee(me, scope, employeeId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "That person doesn't exist.");
        }
    }

    private void requireWeekOpen(LocalDate monday) {
        for (int i = 0; i < 7; i++) locks.requireOpen(monday.plusDays(i), "roster");
    }

    /** Publishing changes everyone's week at the site, so it needs everyone with shifts there to be in your scope. */
    private void requireWholeWeekInScope(UUID me, DataScope scope, UUID siteId, LocalDate from, LocalDate to) {
        if (scope == DataScope.TENANT) return;
        List<UUID> people = jdbc.sql("""
                select employee_id from roster_shift where site_id = :site and work_date between :from and :to
                union select employee_id from published_shift where site_id = :site and work_date between :from and :to""")
            .param("site", siteId).param("from", from).param("to", to).query(UUID.class).list();
        for (UUID p : people) {
            if (p != null && !facts.canSee(me, scope, p)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ROSTER_OUTSIDE_SCOPE",
                    "This week has shifts for people outside your planning scope, so someone with a wider scope has to publish it.");
            }
        }
    }

    private List<Shift> drafts(UUID siteId, LocalDate from, LocalDate to, UUID me, DataScope scope) {
        return jdbc.sql("""
                select r.id, r.site_id, r.employee_id, r.work_date as date, r.start_time, r.end_time, r.break_min, r.grace_min,
                       r.template_id, r.code, r.tone, r.note
                from roster_shift r left join employee e on e.id = r.employee_id
                where r.site_id = :site and r.work_date between :from and :to and (r.employee_id is null or %s)
                order by r.work_date, r.start_time""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("site", siteId).param("from", from).param("to", to).param("me", me).query(Shift.class).list();
    }

    private Shift draft(UUID id) {
        return jdbc.sql("""
                select id, site_id, employee_id, work_date as date, start_time, end_time, break_min, grace_min, template_id, code, tone, note
                from roster_shift where id = :id""").param("id", id).query(Shift.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SHIFT_NOT_FOUND", "That shift doesn't exist."));
    }

    private Site site(UUID id) {
        if (id == null) throw ShiftTemplates.bad("SITE_REQUIRED", "Choose the site.");
        return jdbc.sql("select id, name from site where id = :id").param("id", id).query(Site.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "That site doesn't exist."));
    }

    private Status status(UUID siteId, LocalDate start) {
        record Row(Instant publishedAt, String publishedBy, Instant changedAt) {
        }
        Row r = jdbc.sql("""
                select w.published_at, coalesce(e.full_name, u.email::text) as published_by, w.changed_at from roster_week w
                left join app_user u on u.id = w.published_by left join employee e on e.user_id = w.published_by
                where w.site_id = :site and w.week_start = :week""")
            .param("site", siteId).param("week", start).query(Row.class).optional().orElse(null);
        if (r == null) return new Status(false, null, null, false);
        boolean published = r.publishedAt() != null;
        boolean changed = r.changedAt() != null && (!published || r.changedAt().isAfter(r.publishedAt()));
        return new Status(published, r.publishedAt(), r.publishedBy(), changed);
    }

    /** Marks the site's week as changed since it was last published. */
    private void touch(UUID siteId, LocalDate day) {
        jdbc.sql("""
                insert into roster_week (tenant_id, site_id, week_start, changed_at) values (:tenant, :site, :week, now())
                on conflict (tenant_id, site_id, week_start) do update set changed_at = now()""")
            .param("tenant", TenantContext.require()).param("site", siteId).param("week", monday(day)).update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
