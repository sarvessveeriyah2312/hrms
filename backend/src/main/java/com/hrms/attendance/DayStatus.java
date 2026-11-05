package com.hrms.attendance;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Where one person stands on one day: in, late, not in yet, on leave or off. Pure, so it can be tested without a
 * database. Times are local to the person's legal entity.
 *
 * <ul>
 *   <li>A clock-in always wins: someone who comes in on a rest day or during leave shows as in.</li>
 *   <li>Late means the first clock-in is after the expected time plus the grace minutes. With morning leave the
 *       expected time is the middle of the working day.</li>
 *   <li>Not in yet is "overdue" once the expected time plus grace has passed (or the day is over).</li>
 * </ul>
 */
final class DayStatus {

    enum Status { IN, LATE, NOT_IN, ON_LEAVE, OFF }

    /** Approved leave on the day: FULL, AM (morning) or PM (afternoon). */
    enum LeavePart { FULL, AM, PM }

    record Hours(LocalTime start, LocalTime end, int graceMin) {
        static final Hours DEFAULT = new Hours(LocalTime.of(9, 0), LocalTime.of(18, 0), 5);

        LocalTime midday() {
            return start.plusMinutes(Duration.between(start, end).toMinutes() / 2);
        }
    }

    /**
     * @param workingDay  a day in the person's working week that isn't a public holiday
     * @param leave       approved leave on the day, or null
     * @param firstIn     the first clock-in of the day, or null
     * @param now         the current local date and time (to tell "not yet" from "overdue")
     * @param day         the day being looked at
     */
    record Input(boolean workingDay, LeavePart leave, LocalTime firstIn, Hours hours, LocalDateTime now, java.time.LocalDate day) {
    }

    /**
     * @param expectedAt  when the person was expected (null when not expected at all)
     * @param minutesLate minutes after the expected time (late clock-ins, or overdue not-in-yet); 0 otherwise
     * @param overdue     not in, and the expected time plus grace has passed
     */
    record Result(Status status, LocalTime expectedAt, int minutesLate, boolean overdue) {
    }

    private DayStatus() {
    }

    static Result of(Input in) {
        Hours h = in.hours() == null ? Hours.DEFAULT : in.hours();
        boolean expected = in.workingDay() && in.leave() != LeavePart.FULL;
        LocalTime expectedAt = !expected ? null : in.leave() == LeavePart.AM ? h.midday() : h.start();

        if (in.firstIn() != null) {
            if (expectedAt == null) return new Result(Status.IN, null, 0, false);
            int late = minutesAfter(in.firstIn(), expectedAt);
            return late > h.graceMin() ? new Result(Status.LATE, expectedAt, late, false) : new Result(Status.IN, expectedAt, 0, false);
        }
        if (in.leave() == LeavePart.FULL) return new Result(Status.ON_LEAVE, null, 0, false);
        if (!in.workingDay()) return new Result(Status.OFF, null, 0, false);

        LocalDateTime due = in.day().atTime(expectedAt).plusMinutes(h.graceMin());
        if (in.now().isAfter(due)) {
            // The day is over: count lateness up to the end of the working day only.
            LocalDateTime until = in.now().isAfter(in.day().atTime(h.end())) ? in.day().atTime(h.end()) : in.now();
            int late = (int) Math.max(0, Duration.between(in.day().atTime(expectedAt), until).toMinutes());
            return new Result(Status.NOT_IN, expectedAt, late, true);
        }
        return new Result(Status.NOT_IN, expectedAt, 0, false);
    }

    private static int minutesAfter(LocalTime t, LocalTime expected) {
        return (int) Math.max(0, Duration.between(expected, t).toMinutes());
    }
}
