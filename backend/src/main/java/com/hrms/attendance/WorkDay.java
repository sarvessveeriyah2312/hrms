package com.hrms.attendance;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One person's working day from their clock-ins: in/out pairs, time worked, late arrival, early leaving and a missed
 * clock-out. Pure (local times only), so it can be tested without a database. The day's status comes from
 * {@link DayStatus}.
 *
 * <ul>
 *   <li>A clock-in opens a session and the next clock-out closes it. A second clock-in while one is open, or a
 *       clock-out with nothing open, is ignored.</li>
 *   <li>A session still open when an earlier day ended is a missed clock-out: it counts no time, because the end is
 *       unknown. Today's open session counts up to now ("still in").</li>
 *   <li>Left early: the last clock-out is more than the grace minutes before the expected end (midday with afternoon
 *       leave). Not judged today until the expected end has passed.</li>
 * </ul>
 */
final class WorkDay {

    record Clocking(LocalTime at, Punch.Type type) {
    }

    /** out is null while the session is open (still in, or a missed clock-out). */
    record Session(LocalTime in, LocalTime out) {
    }

    record Input(LocalDate day, boolean workingDay, DayStatus.LeavePart leave, DayStatus.Hours hours,
                 List<Clocking> clockings, LocalDateTime now) {
    }

    record Result(DayStatus.Result status, LocalTime expectedOut, List<Session> sessions, LocalTime firstIn,
                  LocalTime lastOut, int workedMin, int lateMin, int earlyMin, boolean missedOut, boolean stillIn) {
    }

    private WorkDay() {
    }

    static Result of(Input in) {
        DayStatus.Hours h = in.hours() == null ? DayStatus.Hours.DEFAULT : in.hours();
        List<Clocking> sorted = in.clockings().stream().sorted(Comparator.comparing(Clocking::at)).toList();
        boolean past = in.now().toLocalDate().isAfter(in.day());

        List<Session> sessions = new ArrayList<>();
        LocalTime open = null;
        LocalTime lastOut = null;
        int worked = 0;
        for (Clocking c : sorted) {
            if (c.type() == Punch.Type.IN) {
                if (open == null) open = c.at();
            } else if (open != null) {
                sessions.add(new Session(open, c.at()));
                worked += minutes(open, c.at());
                lastOut = c.at();
                open = null;
            }
        }
        boolean missedOut = open != null && past;
        boolean stillIn = open != null && !past;
        if (open != null) {
            sessions.add(new Session(open, null));
            if (stillIn && in.now().toLocalDate().equals(in.day())) worked += minutes(open, in.now().toLocalTime());
        }

        LocalTime firstIn = sorted.stream().filter(c -> c.type() == Punch.Type.IN).map(Clocking::at).findFirst().orElse(null);
        DayStatus.Result status = DayStatus.of(new DayStatus.Input(in.workingDay(), in.leave(), firstIn, h, in.now(), in.day()));

        LocalTime expectedOut = status.expectedAt() == null ? null : in.leave() == DayStatus.LeavePart.PM ? h.midday() : h.end();
        int early = 0;
        boolean dayOver = past || (expectedOut != null && !in.now().toLocalTime().isBefore(expectedOut));
        if (expectedOut != null && lastOut != null && open == null && dayOver && lastOut.isBefore(expectedOut)) {
            int m = minutes(lastOut, expectedOut);
            if (m > h.graceMin()) early = m;
        }
        int late = status.status() == DayStatus.Status.LATE ? status.minutesLate() : 0;
        return new Result(status, expectedOut, List.copyOf(sessions), firstIn, lastOut, worked, late, early, missedOut, stillIn);
    }

    private static int minutes(LocalTime from, LocalTime to) {
        return (int) Math.max(0, Duration.between(from, to).toMinutes());
    }
}
