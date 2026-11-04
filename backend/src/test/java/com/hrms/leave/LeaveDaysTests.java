package com.hrms.leave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hrms.leave.LeaveDays.DayPart;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeaveDaysTests {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final LocalDate FRI = LocalDate.of(2026, 11, 13);
    private static final LocalDate SAT = LocalDate.of(2026, 11, 14);

    @Test
    void countsWeekdaysOnly() {
        assertEquals(BigDecimal.valueOf(5), LeaveDays.count(MON, FRI, DayPart.FULL, Set.of()));
        assertEquals(BigDecimal.valueOf(6), LeaveDays.count(MON, MON.plusDays(7), DayPart.FULL, Set.of()));
    }

    @Test
    void skipsPublicHolidays() {
        assertEquals(BigDecimal.valueOf(4), LeaveDays.count(MON, FRI, DayPart.FULL, Set.of(MON.plusDays(2))));
    }

    @Test
    void weekendOnlyRangeIsZero() {
        assertEquals(BigDecimal.ZERO, LeaveDays.count(SAT, SAT.plusDays(1), DayPart.FULL, Set.of()));
    }

    @Test
    void halfDayIsHalfAndOnlyForOneWorkingDay() {
        assertEquals(new BigDecimal("0.5"), LeaveDays.count(MON, MON, DayPart.AM, Set.of()));
        assertEquals(BigDecimal.ZERO, LeaveDays.count(MON, FRI, DayPart.PM, Set.of()));
        assertEquals(BigDecimal.ZERO, LeaveDays.count(SAT, SAT, DayPart.AM, Set.of()));
    }

    @Test
    void followsTheEntitysWorkingWeek() {
        var monToSat = java.util.EnumSet.range(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SATURDAY);
        assertEquals(BigDecimal.valueOf(6), LeaveDays.count(MON, SAT, DayPart.FULL, monToSat, Set.of()));
        assertEquals(new BigDecimal("0.5"), LeaveDays.count(SAT, SAT, DayPart.AM, monToSat, Set.of()));
        var sunToThu = java.util.EnumSet.of(java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
            java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY);
        assertEquals(BigDecimal.valueOf(4), LeaveDays.count(MON, FRI, DayPart.FULL, sunToThu, Set.of()));
    }

    @Test
    void endBeforeStartIsZero() {
        assertEquals(BigDecimal.ZERO, LeaveDays.count(FRI, MON, DayPart.FULL, Set.of()));
    }

    @Test
    void formatsRangesAndDays() {
        assertEquals("9–13 Nov", LeaveDays.range(MON, FRI, DayPart.FULL));
        assertEquals("28 Nov – 2 Dec", LeaveDays.range(LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 2), DayPart.FULL));
        assertEquals("9 Nov (morning)", LeaveDays.range(MON, MON, DayPart.AM));
        assertEquals("1 day", LeaveDays.days(BigDecimal.ONE));
        assertEquals("0.5 days", LeaveDays.days(new BigDecimal("0.5")));
        assertEquals("3 days", LeaveDays.days(new BigDecimal("3.0")));
    }
}
