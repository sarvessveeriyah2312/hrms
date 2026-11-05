package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrms.attendance.DayStatus.LeavePart;
import com.hrms.attendance.DayStatus.Status;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class DayStatusTests {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    private static final DayStatus.Hours HOURS = new DayStatus.Hours(LocalTime.of(9, 0), LocalTime.of(18, 0), 5);

    private static DayStatus.Result at(String now, boolean workingDay, LeavePart leave, String firstIn) {
        return DayStatus.of(new DayStatus.Input(workingDay, leave, firstIn == null ? null : LocalTime.parse(firstIn), HOURS,
            DAY.atTime(LocalTime.parse(now)), DAY));
    }

    @Test
    void onTimeWithinGrace() {
        var r = at("10:00", true, null, "09:05");
        assertThat(r.status()).isEqualTo(Status.IN);
        assertThat(r.minutesLate()).isZero();
        assertThat(r.expectedAt()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void lateAfterGraceCountsFromTheStart() {
        var r = at("10:00", true, null, "09:06");
        assertThat(r.status()).isEqualTo(Status.LATE);
        assertThat(r.minutesLate()).isEqualTo(6);
    }

    @Test
    void notInYetBeforeTheStartIsNotOverdue() {
        var r = at("08:30", true, null, null);
        assertThat(r.status()).isEqualTo(Status.NOT_IN);
        assertThat(r.overdue()).isFalse();
    }

    @Test
    void notInAfterGraceIsOverdue() {
        var r = at("09:20", true, null, null);
        assertThat(r.status()).isEqualTo(Status.NOT_IN);
        assertThat(r.overdue()).isTrue();
        assertThat(r.minutesLate()).isEqualTo(20);
    }

    @Test
    void overdueStopsCountingAtTheEndOfTheDay() {
        var r = DayStatus.of(new DayStatus.Input(true, null, null, HOURS, DAY.plusDays(1).atTime(8, 0), DAY));
        assertThat(r.overdue()).isTrue();
        assertThat(r.minutesLate()).isEqualTo(9 * 60);
    }

    @Test
    void fullDayLeave() {
        assertThat(at("10:00", true, LeavePart.FULL, null).status()).isEqualTo(Status.ON_LEAVE);
    }

    @Test
    void morningLeaveExpectsThemAtMidday() {
        var waiting = at("12:00", true, LeavePart.AM, null);
        assertThat(waiting.status()).isEqualTo(Status.NOT_IN);
        assertThat(waiting.overdue()).isFalse();
        assertThat(waiting.expectedAt()).isEqualTo(LocalTime.of(13, 30));

        assertThat(at("14:00", true, LeavePart.AM, "13:30").status()).isEqualTo(Status.IN);
    }

    @Test
    void afternoonLeaveStillExpectsTheMorning() {
        assertThat(at("09:30", true, LeavePart.PM, "09:20").status()).isEqualTo(Status.LATE);
    }

    @Test
    void restDayAndHolidayAreOff() {
        assertThat(at("10:00", false, null, null).status()).isEqualTo(Status.OFF);
    }

    @Test
    void comingInOnADayOffShowsAsInNeverLate() {
        var r = at("11:00", false, null, "10:45");
        assertThat(r.status()).isEqualTo(Status.IN);
        assertThat(r.minutesLate()).isZero();
        assertThat(at("11:00", true, LeavePart.FULL, "10:45").status()).isEqualTo(Status.IN);
    }
}
