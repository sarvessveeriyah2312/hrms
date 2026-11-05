package com.hrms.attendance;

import com.hrms.attendance.AttendanceFacts.CorrectionRow;
import com.hrms.attendance.AttendanceFacts.Emp;
import com.hrms.attendance.AttendanceFacts.Leave;
import com.hrms.attendance.AttendanceFacts.PunchRow;
import com.hrms.attendance.AttendanceFacts.SiteRef;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Timesheets: each person's clock-ins by day, with time worked, late arrival, early leaving and missed clock-outs,
 * plus totals; for one person or everyone in the caller's attendance:timesheets scope. See {@link WorkDay}.
 */
@Service
class TimesheetService {

    static final int MAX_DAYS = 62;
    static final int MAX_PEOPLE = 500;

    /**
     * One day. Times are local to the person's legal entity. offReason: HOLIDAY or REST_DAY. overdue: not in and past
     * the expected time plus grace (overdueMin since the expected time); absent: overdue on a day that's over.
     * corrected: an approved correction changed the times; correction: the day's latest correction request, if any.
     */
    record Day(LocalDate date, DayStatus.Status status, boolean absent, String offReason, String holiday,
               String leaveType, DayStatus.LeavePart leavePart, UUID siteId, String site, LocalTime expectedIn,
               LocalTime expectedOut, List<WorkDay.Session> sessions, LocalTime firstIn, LocalTime lastOut,
               int workedMin, int lateMin, int earlyMin, boolean missedOut, boolean stillIn, boolean flagged,
               boolean overdue, int overdueMin, boolean corrected, CorrectionRef correction) {
    }

    /** A correction request on a day: status PENDING, APPROVED, REJECTED or RETURNED. */
    record CorrectionRef(UUID id, String status, LocalTime clockIn, LocalTime clockOut) {
    }

    /**
     * expectedDays: working days without full-day leave. absent: expected, no clock-in, and the day is over.
     * leaveDays: approved leave on working days (half days count 0.5).
     */
    record Totals(int expectedDays, int daysWorked, int workedMin, int lateCount, int lateMin, int earlyCount, int earlyMin,
                  int missedOut, int absent, BigDecimal leaveDays, int flagged) {
    }

    record Sheet(UUID employeeId, String employeeNo, String fullName, String department, LocalDate from, LocalDate to,
                 List<Day> days, Totals totals) {
    }

    record Line(UUID employeeId, String employeeNo, String fullName, String department, String site, Totals totals) {
    }

    record Team(LocalDate from, LocalDate to, DataScope scope, List<Line> people, boolean truncated) {
    }

    private final AttendanceFacts facts;
    private final Clock clock;

    @Autowired
    TimesheetService(AttendanceFacts facts) {
        this(facts, Clock.systemUTC());
    }

    /** For tests: a fixed "now". */
    TimesheetService(AttendanceFacts facts, Clock clock) {
        this.facts = facts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Sheet sheet(UUID employeeId, LocalDate from, LocalDate to) {
        Emp e = facts.person(employeeId).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "That person doesn't exist."));
        LocalDate[] range = range(from, to, LocalDate.now(clock.withZone(e.zone())));
        List<UUID> ids = List.of(e.id());
        List<Day> days = days(e, range[0], range[1], facts.punches(ids, range[0], range[1]).get(e.id()),
            facts.leave(ids, range[0], range[1]).get(e.id()), facts.holidays(range[0], range[1]), facts.sites(),
            facts.homeSites(ids).get(e.id()), facts.corrections(ids, range[0], range[1]).get(e.id()));
        return new Sheet(e.id(), e.employeeNo(), e.fullName(), e.department(), range[0], range[1], days, totals(days));
    }

    @Transactional(readOnly = true)
    Team team(UUID me, DataScope scope, LocalDate from, LocalDate to) {
        LocalDate[] range = range(from, to, LocalDate.now(clock.withZone(facts.zoneOf(me))));
        List<Emp> people = facts.people(me, scope, range[1], MAX_PEOPLE + 1);
        boolean truncated = people.size() > MAX_PEOPLE;
        if (truncated) people = people.subList(0, MAX_PEOPLE);
        List<UUID> ids = people.stream().map(Emp::id).toList();

        Map<UUID, List<PunchRow>> punches = facts.punches(ids, range[0], range[1]);
        Map<UUID, List<Leave>> leave = facts.leave(ids, range[0], range[1]);
        Map<LocalDate, String> holidays = facts.holidays(range[0], range[1]);
        Map<UUID, SiteRef> sites = facts.sites();
        Map<UUID, UUID> home = facts.homeSites(ids);
        Map<UUID, List<CorrectionRow>> corrections = facts.corrections(ids, range[0], range[1]);

        List<Line> lines = new ArrayList<>(people.size());
        for (Emp e : people) {
            List<Day> days = days(e, range[0], range[1], punches.get(e.id()), leave.get(e.id()), holidays, sites, home.get(e.id()),
                corrections.get(e.id()));
            SiteRef site = sites.get(home.get(e.id()));
            lines.add(new Line(e.id(), e.employeeNo(), e.fullName(), e.department(), site == null ? null : site.name(), totals(days)));
        }
        return new Team(range[0], range[1], scope, lines, truncated);
    }

    /** The person's days in the range, from the day they joined up to today in their time zone. */
    private List<Day> days(Emp e, LocalDate from, LocalDate to, List<PunchRow> punches, List<Leave> leave,
                           Map<LocalDate, String> holidays, Map<UUID, SiteRef> sites, UUID homeSite,
                           List<CorrectionRow> corrections) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(e.zone()));
        LocalDate start = from.isBefore(e.joinDate()) ? e.joinDate() : from;
        LocalDate end = to.isAfter(now.toLocalDate()) ? now.toLocalDate() : to;
        List<Day> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            out.add(day(e, d, now, punches, leave, holidays.get(d), sites, homeSite, corrections));
        }
        return out;
    }

    /** One person's day, with an approved correction applied; Today uses this too. */
    static Day day(Emp e, LocalDate d, LocalDateTime now, List<PunchRow> punches, List<Leave> leave, String holiday,
                   Map<UUID, SiteRef> sites, UUID homeSite, List<CorrectionRow> corrections) {
        List<PunchRow> mine = AttendanceFacts.on(punches, d, e.zone());
        PunchRow firstIn = mine.stream().filter(p -> p.type() == Punch.Type.IN).findFirst().orElse(null);
        SiteRef site = firstIn != null && firstIn.siteId() != null && sites.containsKey(firstIn.siteId())
            ? sites.get(firstIn.siteId()) : sites.get(homeSite);
        boolean workingDay = holiday == null && e.workDays().contains(d.getDayOfWeek());
        AttendanceFacts.LeaveOn l = AttendanceFacts.leaveOn(leave, d);
        AttendanceFacts.CorrectionsOn c = AttendanceFacts.correctionsOn(corrections, d);
        List<WorkDay.Clocking> clockings = mine.stream()
            .map(p -> new WorkDay.Clocking(AttendanceFacts.local(p.at(), e.zone()), p.type())).toList();
        if (c.applied() != null) clockings = WorkDay.corrected(clockings, c.applied().clockIn(), c.applied().clockOut());

        var r = WorkDay.of(new WorkDay.Input(d, workingDay, l == null ? null : l.part(),
            site == null ? DayStatus.Hours.DEFAULT : site.hours(), clockings, now));

        boolean absent = r.status().status() == DayStatus.Status.NOT_IN && r.status().overdue() && d.isBefore(now.toLocalDate());
        return new Day(d, r.status().status(), absent, workingDay ? null : holiday != null ? "HOLIDAY" : "REST_DAY", holiday,
            l == null ? null : l.type(), l == null ? null : l.part(), site == null ? null : site.id(), site == null ? null : site.name(),
            r.status().expectedAt(), r.expectedOut(), r.sessions(), r.firstIn(), r.lastOut(), r.workedMin(),
            r.lateMin(), r.earlyMin(), r.missedOut(), r.stillIn(), mine.stream().anyMatch(p -> "FLAGGED".equals(p.result())),
            r.status().overdue(), r.status().overdue() ? r.status().minutesLate() : 0, c.applied() != null,
            c.latest() == null ? null : new CorrectionRef(c.latest().id(), c.latest().status(), c.latest().clockIn(), c.latest().clockOut()));
    }

    static Totals totals(List<Day> days) {
        int expected = 0, worked = 0, minutes = 0, lateCount = 0, lateMin = 0, earlyCount = 0, earlyMin = 0, missed = 0, absent = 0, flagged = 0;
        BigDecimal leaveDays = BigDecimal.ZERO;
        for (Day d : days) {
            boolean workingDay = d.offReason() == null;
            if (workingDay && d.leavePart() != DayStatus.LeavePart.FULL) expected++;
            if (workingDay && d.leavePart() != null) {
                leaveDays = leaveDays.add(d.leavePart() == DayStatus.LeavePart.FULL ? BigDecimal.ONE : new BigDecimal("0.5"));
            }
            if (d.firstIn() != null) worked++;
            minutes += d.workedMin();
            if (d.status() == DayStatus.Status.LATE) {
                lateCount++;
                lateMin += d.lateMin();
            }
            if (d.earlyMin() > 0) {
                earlyCount++;
                earlyMin += d.earlyMin();
            }
            if (d.missedOut()) missed++;
            if (d.absent()) absent++;
            if (d.flagged()) flagged++;
        }
        return new Totals(expected, worked, minutes, lateCount, lateMin, earlyCount, earlyMin, missed, absent, leaveDays, flagged);
    }

    /** Defaults to this month so far; refuses an upside-down or over-long range. */
    private static LocalDate[] range(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? (from == null ? today : f.plusMonths(1).withDayOfMonth(1).minusDays(1)) : to;
        if (t.isBefore(f)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE", "The end date must be on or after the start date.");
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RANGE_TOO_LONG", "Pick up to " + MAX_DAYS + " days at a time.");
        }
        return new LocalDate[] {f, t};
    }
}
