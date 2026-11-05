package com.hrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrms.attendance.DayStatus.LeavePart;
import com.hrms.attendance.Punch.Type;
import com.hrms.attendance.WorkDay.Clocking;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkDayTests {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final DayStatus.Hours HOURS = new DayStatus.Hours(LocalTime.of(9, 0), LocalTime.of(18, 0), 5);
    private static final LocalDateTime NEXT_MORNING = DAY.plusDays(1).atTime(8, 0);

    /** "09:00 IN", "18:00 OUT", ... */
    private static List<Clocking> clocks(String... entries) {
        List<Clocking> out = new ArrayList<>();
        for (String e : entries) {
            String[] p = e.split(" ");
            out.add(new Clocking(LocalTime.parse(p[0]), Type.valueOf(p[1])));
        }
        return out;
    }

    private static WorkDay.Result day(LocalDateTime now, LeavePart leave, String... entries) {
        return WorkDay.of(new WorkDay.Input(DAY, true, leave, HOURS, clocks(entries), now));
    }

    @Test
    void aFullDayWithALunchBreak() {
        var r = day(NEXT_MORNING, null, "09:00 IN", "12:30 OUT", "13:30 IN", "18:00 OUT");
        assertThat(r.sessions()).hasSize(2);
        assertThat(r.workedMin()).isEqualTo(8 * 60);
        assertThat(r.lateMin()).isZero();
        assertThat(r.earlyMin()).isZero();
        assertThat(r.missedOut()).isFalse();
        assertThat(r.firstIn()).isEqualTo(LocalTime.of(9, 0));
        assertThat(r.lastOut()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void lateAndLeftEarly() {
        var r = day(NEXT_MORNING, null, "09:20 IN", "17:00 OUT");
        assertThat(r.lateMin()).isEqualTo(20);
        assertThat(r.earlyMin()).isEqualTo(60);
        assertThat(r.workedMin()).isEqualTo(7 * 60 + 40);
    }

    @Test
    void leavingWithinGraceIsNotEarly() {
        assertThat(day(NEXT_MORNING, null, "09:00 IN", "17:56 OUT").earlyMin()).isZero();
    }

    @Test
    void aMissedClockOutCountsNoTime() {
        var r = day(NEXT_MORNING, null, "09:00 IN");
        assertThat(r.missedOut()).isTrue();
        assertThat(r.stillIn()).isFalse();
        assertThat(r.workedMin()).isZero();
        assertThat(r.sessions()).containsExactly(new WorkDay.Session(LocalTime.of(9, 0), null));
        assertThat(r.earlyMin()).isZero();
    }

    @Test
    void todayAnOpenSessionCountsUpToNow() {
        var r = day(DAY.atTime(11, 0), null, "09:00 IN");
        assertThat(r.stillIn()).isTrue();
        assertThat(r.missedOut()).isFalse();
        assertThat(r.workedMin()).isEqualTo(120);
    }

    @Test
    void earlyLeavingIsNotJudgedBeforeTheDayEnds() {
        assertThat(day(DAY.atTime(13, 0), null, "09:00 IN", "12:00 OUT").earlyMin()).isZero();
        assertThat(day(DAY.atTime(18, 30), null, "09:00 IN", "12:00 OUT").earlyMin()).isEqualTo(6 * 60);
    }

    @Test
    void repeatedClockInsAndStrayClockOutsAreIgnored() {
        var r = day(NEXT_MORNING, null, "08:00 OUT", "09:00 IN", "09:01 IN", "18:00 OUT", "18:05 OUT");
        assertThat(r.sessions()).containsExactly(new WorkDay.Session(LocalTime.of(9, 0), LocalTime.of(18, 0)));
        assertThat(r.workedMin()).isEqualTo(9 * 60);
        assertThat(r.lastOut()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void afternoonLeaveExpectsThemToLeaveAtMidday() {
        var r = day(NEXT_MORNING, LeavePart.PM, "09:00 IN", "13:30 OUT");
        assertThat(r.expectedOut()).isEqualTo(LocalTime.of(13, 30));
        assertThat(r.earlyMin()).isZero();
    }

    @Test
    void noClockInsOnAWorkingDayIsOverdue() {
        var r = day(NEXT_MORNING, null);
        assertThat(r.status().status()).isEqualTo(DayStatus.Status.NOT_IN);
        assertThat(r.status().overdue()).isTrue();
        assertThat(r.workedMin()).isZero();
    }
}
