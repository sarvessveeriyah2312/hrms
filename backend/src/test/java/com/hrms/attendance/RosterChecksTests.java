package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What stops or warns before a week is published. Week of Mon 9 Nov 2026. */
class RosterChecksTests {

    private static final UUID AINA = UUID.randomUUID();
    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final Map<UUID, java.util.Set<DayOfWeek>> WEEK = Map.of(AINA, EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));

    private static RosterChecks.Shift shift(LocalDate day, String start, String end, boolean here) {
        return new RosterChecks.Shift(UUID.randomUUID(), AINA, "Aina", here ? "HQ" : "Mall", day, LocalTime.parse(start), LocalTime.parse(end), here);
    }

    private static List<String> kinds(List<RosterChecks.Problem> problems) {
        return problems.stream().map(p -> p.severity() + ":" + p.kind()).toList();
    }

    @Test
    void aNormalWeekHasNoProblems() {
        var shifts = List.of(shift(MON, "09:00", "18:00", true), shift(MON.plusDays(1), "09:00", "18:00", true));
        assertThat(RosterChecks.check(shifts, WEEK, Map.of(), Map.of())).isEmpty();
    }

    @Test
    void overlapsAreErrorsEvenWithAnotherSitesPublishedShift() {
        var shifts = List.of(shift(MON, "09:00", "13:00", true), shift(MON, "12:00", "18:00", false));
        assertThat(kinds(RosterChecks.check(shifts, WEEK, Map.of(), Map.of()))).containsExactly("ERROR:OVERLAP");
        // Back to back is fine (a split shift).
        var split = List.of(shift(MON, "09:00", "13:00", true), shift(MON, "13:00", "18:00", true));
        assertThat(RosterChecks.check(split, WEEK, Map.of(), Map.of())).isEmpty();
    }

    @Test
    void leaveHolidaysAndRestDaysWarn() {
        var leave = Map.of(AINA, List.of(new AttendanceFacts.Leave(AINA, MON, MON, DayStatus.LeavePart.AM, "Annual leave")));
        var shifts = List.of(shift(MON, "09:00", "18:00", true), shift(MON.plusDays(2), "09:00", "18:00", true),
            shift(MON.plusDays(5), "09:00", "13:00", true));
        var problems = RosterChecks.check(shifts, WEEK, leave, Map.of(MON.plusDays(2), "Deepavali"));
        assertThat(kinds(problems)).containsExactlyInAnyOrder("WARNING:ON_LEAVE", "WARNING:HOLIDAY", "WARNING:REST_DAY");
        assertThat(problems.stream().filter(p -> p.kind().equals("ON_LEAVE")).findFirst().orElseThrow().detail()).isEqualTo("Annual leave (AM)");
    }

    @Test
    void tooLittleRestBetweenDays() {
        var shifts = List.of(shift(MON, "14:00", "23:00", true), shift(MON.plusDays(1), "06:00", "14:00", true));
        var problems = RosterChecks.check(shifts, WEEK, Map.of(), Map.of());
        assertThat(kinds(problems)).containsExactly("WARNING:SHORT_REST");
        assertThat(problems.get(0).detail()).isEqualTo("7h 0m");
    }

    @Test
    void moreThanThreeShiftsADay() {
        var shifts = List.of(shift(MON, "06:00", "08:00", true), shift(MON, "09:00", "11:00", true),
            shift(MON, "12:00", "14:00", true), shift(MON, "15:00", "17:00", true));
        assertThat(kinds(RosterChecks.check(shifts, WEEK, Map.of(), Map.of()))).containsExactly("ERROR:TOO_MANY");
    }

    @Test
    void otherSitesAloneAreNotThisWeeksProblem() {
        var shifts = List.of(shift(MON, "09:00", "13:00", false), shift(MON, "12:00", "18:00", false));
        assertThat(RosterChecks.check(shifts, WEEK, Map.of(), Map.of())).isEmpty();
    }

    @Test
    void shiftHoursRunFromTheFirstStartToTheLastEnd() {
        var h = TimesheetService.shiftHours(List.of(
            new AttendanceFacts.ShiftRow(AINA, MON, LocalTime.of(14, 0), LocalTime.of(18, 0), 10, null, "PM", 0),
            new AttendanceFacts.ShiftRow(AINA, MON, LocalTime.of(7, 0), LocalTime.of(11, 0), 0, null, "AM", 0)));
        assertThat(h.start()).isEqualTo(LocalTime.of(7, 0));
        assertThat(h.end()).isEqualTo(LocalTime.of(18, 0));
        assertThat(h.graceMin()).isZero();
    }

    @Test
    void aRosteredWeekWithoutAShiftThatDayIsADayOff() {
        var rows = List.of(new AttendanceFacts.ShiftRow(AINA, MON, LocalTime.of(9, 0), LocalTime.of(18, 0), 5, null, null, 60));
        assertThat(AttendanceFacts.rosterOn(rows, MON).shifts()).hasSize(1);
        var tue = AttendanceFacts.rosterOn(rows, MON.plusDays(1));
        assertThat(tue.shifts()).isEmpty();
        assertThat(tue.rosteredWeek()).isTrue();
        assertThat(AttendanceFacts.rosterOn(rows, MON.plusDays(7)).rosteredWeek()).isFalse();
    }

    @Test
    void shiftsOutsideAvailabilityWarnAndOpenShiftsArentChecked() {
        var avail = Map.of(AINA, Map.of(1, new Availability.Day(1, "WINDOWS", List.of(new Availability.Window(LocalTime.of(8, 0), LocalTime.of(14, 0)))),
            2, new Availability.Day(2, "NONE", List.of())));
        var shifts = List.of(shift(MON, "09:00", "13:00", true), shift(MON.plusDays(1), "09:00", "13:00", true),
            shift(MON.plusDays(3), "09:00", "13:00", true),
            new RosterChecks.Shift(UUID.randomUUID(), null, null, "HQ", MON, LocalTime.of(6, 0), LocalTime.of(23, 0), true));
        var problems = RosterChecks.check(shifts, WEEK, Map.of(), Map.of(), avail);
        assertThat(kinds(problems)).containsExactly("WARNING:UNAVAILABLE");   // Tuesday only; Monday fits, Thursday is any time
        assertThat(problems.get(0).date()).isEqualTo(MON.plusDays(1));
    }

    @Test
    void availabilityWindowsAreChecked() {
        var ok = new Availability.Day(1, "WINDOWS", List.of(new Availability.Window(LocalTime.of(9, 0), LocalTime.of(12, 0)),
            new Availability.Window(LocalTime.of(14, 0), LocalTime.of(18, 0))));
        Availability.check(ok);
        assertThat(ok.fits(LocalTime.of(14, 0), LocalTime.of(18, 0))).isTrue();
        assertThat(ok.fits(LocalTime.of(11, 0), LocalTime.of(15, 0))).isFalse();
        var overlapping = new Availability.Day(1, "WINDOWS", List.of(new Availability.Window(LocalTime.of(9, 0), LocalTime.of(12, 0)),
            new Availability.Window(LocalTime.of(11, 0), LocalTime.of(13, 0))));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Availability.check(overlapping))
            .isInstanceOf(com.hrms.shared.web.ApiException.class);
    }
}
