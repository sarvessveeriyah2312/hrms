package com.hrms.leave;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Counts leave days: the employee's working days (their legal entity's working week, Monday to Friday
 * by default) that aren't public holidays. A half day (morning or afternoon) is only possible for a single
 * day and counts as 0.5.
 */
final class LeaveDays {

    enum DayPart { FULL, AM, PM }

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d", Locale.ENGLISH);

    private LeaveDays() {
    }

    static final Set<DayOfWeek> MONDAY_TO_FRIDAY = java.util.EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);

    static boolean isWorkingDay(LocalDate day, Set<DayOfWeek> workDays, Set<LocalDate> holidays) {
        return workDays.contains(day.getDayOfWeek()) && !holidays.contains(day);
    }

    static BigDecimal count(LocalDate start, LocalDate end, DayPart part, Set<LocalDate> holidays) {
        return count(start, end, part, MONDAY_TO_FRIDAY, holidays);
    }

    static BigDecimal count(LocalDate start, LocalDate end, DayPart part, Set<DayOfWeek> workDays, Set<LocalDate> holidays) {
        if (end.isBefore(start)) return BigDecimal.ZERO;
        if (part != DayPart.FULL) {
            return start.equals(end) && isWorkingDay(start, workDays, holidays) ? new BigDecimal("0.5") : BigDecimal.ZERO;
        }
        int days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWorkingDay(d, workDays, holidays)) days++;
        }
        return BigDecimal.valueOf(days);
    }

    /** "12 Nov", "12–14 Nov", "28 Nov – 2 Dec", "12 Nov (morning)". */
    static String range(LocalDate start, LocalDate end, DayPart part) {
        if (start.equals(end)) {
            String day = start.format(DAY_MONTH);
            return switch (part) {
                case AM -> day + " (morning)";
                case PM -> day + " (afternoon)";
                case FULL -> day;
            };
        }
        if (start.getMonth() == end.getMonth() && start.getYear() == end.getYear()) {
            return start.format(DAY) + "–" + end.format(DAY_MONTH);
        }
        return start.format(DAY_MONTH) + " – " + end.format(DAY_MONTH);
    }

    /** "1 day", "0.5 days", "3 days". */
    static String days(BigDecimal days) {
        String n = days.stripTrailingZeros().toPlainString();
        return n + (days.compareTo(BigDecimal.ONE) == 0 ? " day" : " days");
    }
}
