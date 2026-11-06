package com.hrms.attendance;

import com.hrms.attendance.AttendanceFacts.Emp;
import com.hrms.attendance.AttendanceFacts.Leave;
import com.hrms.attendance.AttendanceFacts.PunchRow;
import com.hrms.attendance.AttendanceFacts.SiteRef;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Today for managers: everyone the caller's attendance:view scope covers, with their first clock-in, whether they
 * were late, not in yet, on approved leave or off (rest day or public holiday), by site. Days are worked out as in
 * timesheets ({@link TimesheetService#day}); each person's day and times are in their legal entity's time zone.
 */
@Service
class TodayService {

    /** Larger scopes are cut off here; the response says so. */
    static final int MAX_PEOPLE = 2000;

    /**
     * One person's day. Times are local ("09:12"). site: where they clocked in, else the first site they belong to.
     * minutesLate: late clock-ins, or how long overdue someone not in yet is. offReason: HOLIDAY or REST_DAY.
     */
    record Person(UUID employeeId, String employeeNo, String fullName, String department, UUID siteId, String site,
                  DayStatus.Status status, LocalTime expectedAt, LocalTime firstIn, LocalTime lastOut, boolean stillIn,
                  int minutesLate, boolean overdue, boolean flagged, String leaveType, DayStatus.LeavePart leavePart,
                  String offReason) {
    }

    record Today(LocalDate date, boolean isToday, DataScope scope, String holiday, List<SiteRef> sites,
                 List<Person> people, boolean truncated) {
    }

    private final AttendanceFacts facts;
    private final Clock clock;

    TodayService(AttendanceFacts facts) {
        this.facts = facts;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    Today today(UUID me, DataScope scope, LocalDate requested) {
        ZoneId myZone = facts.zoneOf(me);
        LocalDate todayHere = LocalDate.now(clock.withZone(myZone));
        LocalDate day = requested == null ? todayHere : requested;
        if (day.isAfter(todayHere)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FUTURE_DATE", "Pick today or an earlier day.");
        }

        List<Emp> people = facts.people(me, scope, day, MAX_PEOPLE + 1);
        boolean truncated = people.size() > MAX_PEOPLE;
        if (truncated) people = people.subList(0, MAX_PEOPLE);
        String holiday = facts.holidays(day, day).get(day);
        if (people.isEmpty()) return new Today(day, day.equals(todayHere), scope, holiday, List.of(), List.of(), false);

        List<UUID> ids = people.stream().map(Emp::id).toList();
        Map<UUID, SiteRef> sites = facts.sites();
        Map<UUID, UUID> home = facts.homeSites(ids);
        Map<UUID, List<PunchRow>> punches = facts.punches(ids, day, day);
        Map<UUID, List<Leave>> leave = facts.leave(ids, day, day);
        var corrections = facts.corrections(ids, day, day);
        var shifts = facts.publishedShifts(ids, day, day);

        List<Person> out = new ArrayList<>(people.size());
        for (Emp e : people) {
            var d = TimesheetService.day(e, day, LocalDateTime.now(clock.withZone(e.zone())), punches.get(e.id()),
                leave.get(e.id()), holiday, sites, home.get(e.id()), corrections.get(e.id()), null, shifts.get(e.id()));
            out.add(new Person(e.id(), e.employeeNo(), e.fullName(), e.department(), d.siteId(), d.site(), d.status(),
                d.expectedIn(), d.firstIn(), d.stillIn() ? null : d.lastOut(), d.stillIn(),
                d.overdue() ? d.overdueMin() : d.lateMin(), d.overdue(), d.flagged(), d.leaveType(), d.leavePart(), d.offReason()));
        }

        List<SiteRef> used = out.stream().map(Person::siteId).filter(Objects::nonNull).distinct()
            .map(sites::get).sorted(Comparator.comparing(SiteRef::name, String.CASE_INSENSITIVE_ORDER)).toList();
        return new Today(day, day.equals(todayHere), scope, holiday, used, out, truncated);
    }
}
