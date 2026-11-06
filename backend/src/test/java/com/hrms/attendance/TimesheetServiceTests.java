package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hrms.attendance.AttendanceFacts.Emp;
import com.hrms.attendance.AttendanceFacts.Leave;
import com.hrms.attendance.AttendanceFacts.PunchRow;
import com.hrms.attendance.AttendanceFacts.SiteRef;
import com.hrms.attendance.DayStatus.LeavePart;
import com.hrms.attendance.DayStatus.Status;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Timesheets over a mocked {@link AttendanceFacts}, with "now" fixed at Thursday 5 Nov 2026, 10:00 in Kuala Lumpur. */
class TimesheetServiceTests {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneOffset.UTC);
    private static final UUID ME = UUID.randomUUID();
    private static final SiteRef HQ = new SiteRef(UUID.randomUUID(), "HQ", LocalTime.of(9, 0), LocalTime.of(18, 0), 5);

    private final AttendanceFacts facts = mock(AttendanceFacts.class);
    private final TimesheetService service = new TimesheetService(facts, NOW);
    private final List<PunchRow> punches = new ArrayList<>();
    private final List<Leave> leave = new ArrayList<>();

    private static Emp emp(UUID id, LocalDate joined) {
        return new Emp(id, "E001", "Aina", "Ops", AttendanceFacts.DEFAULT_ZONE,
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), joined);
    }

    /** A clock-in at a local Kuala Lumpur time (UTC+8). */
    private void clock(String date, String time, Punch.Type type) {
        Instant at = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(AttendanceFacts.DEFAULT_ZONE).toInstant();
        punches.add(new PunchRow(ME, type, at, HQ.id(), "OK"));
    }

    @BeforeEach
    void facts() {
        when(facts.person(ME)).thenReturn(java.util.Optional.of(emp(ME, LocalDate.of(2026, 1, 1))));
        when(facts.punches(anyList(), any(), any())).thenAnswer(i -> Map.of(ME, punches));
        when(facts.leave(anyList(), any(), any())).thenAnswer(i -> Map.of(ME, leave));
        when(facts.holidays(any(), any())).thenReturn(Map.of());
        when(facts.sites()).thenReturn(new HashMap<>(Map.of(HQ.id(), HQ)));   // like the real one: get(null) is allowed
        when(facts.homeSites(anyList())).thenAnswer(i -> Map.of(ME, HQ.id()));
        when(facts.zoneOf(any())).thenReturn(AttendanceFacts.DEFAULT_ZONE);
    }

    @Test
    void defaultsToThisMonthSoFar() {
        var sheet = service.sheet(ME, null, null);
        assertThat(sheet.from()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(sheet.to()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(sheet.days()).extracting(TimesheetService.Day::date)
            .containsExactly(IntStream.rangeClosed(1, 5).mapToObj(d -> LocalDate.of(2026, 11, d)).toArray(LocalDate[]::new));
        assertThat(sheet.days().get(0).offReason()).isEqualTo("REST_DAY");
    }

    @Test
    void aFromDateAloneMeansThatWholeMonthButStopsAtToday() {
        var sheet = service.sheet(ME, LocalDate.of(2026, 11, 1), null);
        assertThat(sheet.to()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(sheet.days()).last().extracting(TimesheetService.Day::date).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void refusesUpsideDownAndOverLongRanges() {
        assertThatThrownBy(() -> service.sheet(ME, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 9)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BAD_RANGE"));
        assertThatThrownBy(() -> service.sheet(ME, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 2)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("RANGE_TOO_LONG"));
        assertThat(service.sheet(ME, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1)).from())
            .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void startsOnTheDayTheyJoined() {
        when(facts.person(ME)).thenReturn(java.util.Optional.of(emp(ME, LocalDate.of(2026, 11, 4))));
        assertThat(service.sheet(ME, null, null).days()).extracting(TimesheetService.Day::date)
            .containsExactly(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5));
    }

    @Test
    void unknownPeopleAreNotFound() {
        UUID stranger = UUID.randomUUID();
        when(facts.person(stranger)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.sheet(stranger, null, null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EMPLOYEE_NOT_FOUND"));
    }

    @Test
    void addsUpTheMonth() {
        clock("2026-11-02", "09:00", Punch.Type.IN);   // on time, full day
        clock("2026-11-02", "18:00", Punch.Type.OUT);
        clock("2026-11-03", "09:20", Punch.Type.IN);   // late 20, left an hour early
        clock("2026-11-03", "17:00", Punch.Type.OUT);
        clock("2026-11-04", "09:00", Punch.Type.IN);   // never clocked out
        // Thursday 5th: not in yet at 10:00, but the day isn't over, so not absent.

        var sheet = service.sheet(ME, null, null);
        var t = sheet.totals();
        assertThat(t.expectedDays()).isEqualTo(4);
        assertThat(t.daysWorked()).isEqualTo(3);
        assertThat(t.workedMin()).isEqualTo(9 * 60 + 7 * 60 + 40);
        assertThat(t.lateCount()).isEqualTo(1);
        assertThat(t.lateMin()).isEqualTo(20);
        assertThat(t.earlyCount()).isEqualTo(1);
        assertThat(t.earlyMin()).isEqualTo(60);
        assertThat(t.missedOut()).isEqualTo(1);
        assertThat(t.absent()).isZero();

        var today = sheet.days().get(4);
        assertThat(today.status()).isEqualTo(Status.NOT_IN);
        assertThat(today.overdue()).isTrue();
        assertThat(today.absent()).isFalse();
    }

    @Test
    void anApprovedCorrectionChangesTheDayAndAPendingOneDoesNot() {
        clock("2026-11-03", "09:00", Punch.Type.IN);   // forgot to clock out
        clock("2026-11-04", "09:00", Punch.Type.IN);   // forgot to clock out too, correction still waiting
        UUID approved = UUID.randomUUID(), pending = UUID.randomUUID();
        when(facts.corrections(anyList(), any(), any())).thenReturn(Map.of(ME, List.of(
            new AttendanceFacts.CorrectionRow(approved, ME, LocalDate.of(2026, 11, 3), null, LocalTime.of(18, 0), "APPROVED"),
            new AttendanceFacts.CorrectionRow(pending, ME, LocalDate.of(2026, 11, 4), null, LocalTime.of(18, 0), "PENDING"))));

        var days = service.sheet(ME, null, null).days();
        var tue = days.get(2);
        assertThat(tue.corrected()).isTrue();
        assertThat(tue.missedOut()).isFalse();
        assertThat(tue.workedMin()).isEqualTo(9 * 60);
        assertThat(tue.correction().status()).isEqualTo("APPROVED");

        var wed = days.get(3);
        assertThat(wed.corrected()).isFalse();
        assertThat(wed.missedOut()).isTrue();
        assertThat(wed.correction().id()).isEqualTo(pending);
        assertThat(days.get(1).correction()).isNull();
    }

    @Test
    void approvedOvertimeCountsOnItsDayAndInTheTotals() {
        when(facts.approvedOvertime(anyList(), any(), any())).thenReturn(Map.of(ME, List.of(
            new AttendanceFacts.OvertimeRow(ME, LocalDate.of(2026, 11, 3), new BigDecimal("2.50"), new BigDecimal("1.5")),
            new AttendanceFacts.OvertimeRow(ME, LocalDate.of(2026, 11, 3), new BigDecimal("1.00"), new BigDecimal("2.0")),
            new AttendanceFacts.OvertimeRow(ME, LocalDate.of(2026, 11, 4), new BigDecimal("0.25"), new BigDecimal("1.0")))));

        var sheet = service.sheet(ME, null, null);
        assertThat(sheet.days().get(2).overtimeMin()).isEqualTo(210);
        assertThat(sheet.days().get(3).overtimeMin()).isEqualTo(15);
        assertThat(sheet.days().get(1).overtimeMin()).isZero();
        assertThat(sheet.totals().overtimeMin()).isEqualTo(225);
    }

    @Test
    void aPublishedShiftSetsTheHoursAndARosteredWeekMakesOtherDaysOff() {
        // Mon 2 Nov: rostered 14:00–22:00, in at 14:20 (late against the shift, not the site's 09:00).
        clock("2026-11-02", "14:20", Punch.Type.IN);
        clock("2026-11-02", "22:00", Punch.Type.OUT);
        when(facts.publishedShifts(anyList(), any(), any())).thenReturn(Map.of(ME, List.of(
            new AttendanceFacts.ShiftRow(ME, LocalDate.of(2026, 11, 2), LocalTime.of(14, 0), LocalTime.of(22, 0), 10, HQ.id(), "PM", 30))));

        var days = service.sheet(ME, null, null).days();
        var mon = days.get(1);
        assertThat(mon.status()).isEqualTo(Status.LATE);
        assertThat(mon.lateMin()).isEqualTo(20);
        assertThat(mon.expectedIn()).isEqualTo(LocalTime.of(14, 0));
        assertThat(mon.shifts()).extracting(TimesheetService.ShiftRef::code).containsExactly("PM");
        // Worked 7h40m against a 7h30m paid shift (8h less a 30-minute break): 10 minutes extra.
        assertThat(mon.extraMin()).isEqualTo(10);
        // Tue–Thu have no shift in a rostered week: days off, not absences.
        assertThat(days.get(2).offReason()).isEqualTo("REST_DAY");
        assertThat(days.get(2).absent()).isFalse();
    }

    @Test
    void aPastDayWithNoClockInIsAbsent() {
        var t = service.sheet(ME, null, null).totals();
        assertThat(t.absent()).isEqualTo(3);   // Mon–Wed; Sunday is a rest day and Thursday isn't over
    }

    @Test
    void leaveAndHolidays() {
        when(facts.holidays(any(), any())).thenReturn(Map.of(LocalDate.of(2026, 11, 2), "Deepavali"));
        leave.add(new Leave(ME, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3), LeavePart.FULL, "Annual"));
        leave.add(new Leave(ME, LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 4), LeavePart.PM, "Annual"));
        clock("2026-11-04", "09:00", Punch.Type.IN);
        clock("2026-11-04", "13:30", Punch.Type.OUT);

        var sheet = service.sheet(ME, null, null);
        assertThat(sheet.days().get(1).offReason()).isEqualTo("HOLIDAY");
        assertThat(sheet.days().get(1).holiday()).isEqualTo("Deepavali");
        assertThat(sheet.days().get(2).status()).isEqualTo(Status.ON_LEAVE);
        assertThat(sheet.days().get(3).earlyMin()).isZero();   // afternoon off: leaving at midday is on time

        var t = sheet.totals();
        assertThat(t.expectedDays()).isEqualTo(2);   // Wed (half day) and Thu
        assertThat(t.leaveDays()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(t.absent()).isZero();
    }

    @Test
    void teamListsEveryoneInScopeAndSaysWhenItStopped() {
        List<Emp> many = IntStream.range(0, TimesheetService.MAX_PEOPLE + 1)
            .mapToObj(i -> emp(UUID.randomUUID(), LocalDate.of(2026, 1, 1))).toList();
        when(facts.people(eq(ME), eq(DataScope.TEAM), any(), anyInt())).thenReturn(many);
        when(facts.punches(anyList(), any(), any())).thenReturn(Map.of());
        when(facts.leave(anyList(), any(), any())).thenReturn(Map.of());
        when(facts.homeSites(anyList())).thenReturn(new HashMap<>());

        var team = service.team(ME, DataScope.TEAM, null, null);
        assertThat(team.truncated()).isTrue();
        assertThat(team.people()).hasSize(TimesheetService.MAX_PEOPLE);
        assertThat(team.scope()).isEqualTo(DataScope.TEAM);
        assertThat(team.people().get(0).site()).isNull();
        assertThat(team.people().get(0).totals().absent()).isEqualTo(3);
    }
}
