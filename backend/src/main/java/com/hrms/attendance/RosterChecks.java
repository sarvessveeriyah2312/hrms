package com.hrms.attendance;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Problems in a site's week before it's published. Errors stop publishing (overlapping shifts, more than three a day);
 * warnings need a confirmation (approved leave, public holidays, rest days, outside their availability, too little
 * rest between days). Open shifts (nobody on them yet) aren't checked.
 * Pure, so it can be tested without a database.
 */
final class RosterChecks {

    /** The most shifts one person can have on one day, across all sites. */
    static final int MAX_PER_DAY = 3;
    /** Less rest than this between one day's last shift and the next day's first is flagged. */
    static final int MIN_REST_MIN = 8 * 60;

    /** thisSite: on the week being checked (others are other sites' published shifts, for overlaps and rest). */
    record Shift(UUID id, UUID employeeId, String employeeName, String site, LocalDate date, LocalTime start, LocalTime end,
                 boolean thisSite) {
    }

    /** severity: ERROR or WARNING. kind: OVERLAP, TOO_MANY, ON_LEAVE, HOLIDAY, REST_DAY, UNAVAILABLE, SHORT_REST. */
    record Problem(String kind, String severity, UUID shiftId, UUID employeeId, String employeeName, LocalDate date, String detail) {
    }

    private RosterChecks() {
    }

    static List<Problem> check(List<Shift> shifts, Map<UUID, Set<DayOfWeek>> workDays,
                               Map<UUID, List<AttendanceFacts.Leave>> leave, Map<LocalDate, String> holidays) {
        return check(shifts, workDays, leave, holidays, Map.of());
    }

    static List<Problem> check(List<Shift> shifts, Map<UUID, Set<DayOfWeek>> workDays,
                               Map<UUID, List<AttendanceFacts.Leave>> leave, Map<LocalDate, String> holidays,
                               Map<UUID, Map<Integer, Availability.Day>> availability) {
        List<Problem> out = new ArrayList<>();
        Map<UUID, List<Shift>> byPerson = shifts.stream().filter(s -> s.employeeId() != null)
            .collect(Collectors.groupingBy(Shift::employeeId));
        for (List<Shift> mine : byPerson.values()) {
            List<Shift> sorted = mine.stream().sorted(Comparator.comparing(Shift::date).thenComparing(Shift::start)).toList();
            Map<LocalDate, List<Shift>> byDay = sorted.stream().collect(Collectors.groupingBy(Shift::date));
            for (var day : byDay.entrySet()) {
                List<Shift> list = day.getValue();
                boolean concerns = list.stream().anyMatch(Shift::thisSite);
                if (!concerns) continue;
                Shift first = list.stream().filter(Shift::thisSite).findFirst().orElseThrow();
                if (list.size() > MAX_PER_DAY) {
                    out.add(new Problem("TOO_MANY", "ERROR", first.id(), first.employeeId(), first.employeeName(), day.getKey(),
                        list.size() + " shifts"));
                }
                for (int i = 0; i < list.size(); i++) {
                    for (int j = i + 1; j < list.size(); j++) {
                        Shift a = list.get(i), b = list.get(j);
                        if ((a.thisSite() || b.thisSite()) && b.start().isBefore(a.end())) {
                            Shift mineHere = b.thisSite() ? b : a;
                            Shift other = mineHere == b ? a : b;
                            out.add(new Problem("OVERLAP", "ERROR", mineHere.id(), a.employeeId(), a.employeeName(), day.getKey(),
                                other.start() + "–" + other.end() + (other.thisSite() ? "" : " at " + other.site())));
                        }
                    }
                }
                var l = AttendanceFacts.leaveOn(leave.get(first.employeeId()), day.getKey());
                if (l != null) {
                    out.add(new Problem("ON_LEAVE", "WARNING", first.id(), first.employeeId(), first.employeeName(), day.getKey(),
                        l.type() + (l.part() == DayStatus.LeavePart.FULL ? "" : " (" + l.part() + ")")));
                }
                String holiday = holidays.get(day.getKey());
                if (holiday != null) {
                    out.add(new Problem("HOLIDAY", "WARNING", first.id(), first.employeeId(), first.employeeName(), day.getKey(), holiday));
                } else {
                    Set<DayOfWeek> week = workDays.get(first.employeeId());
                    if (week != null && !week.contains(day.getKey().getDayOfWeek())) {
                        out.add(new Problem("REST_DAY", "WARNING", first.id(), first.employeeId(), first.employeeName(), day.getKey(), null));
                    }
                }
                Availability.Day avail = availability.getOrDefault(first.employeeId(), Map.of()).get(day.getKey().getDayOfWeek().getValue());
                if (avail != null) {
                    for (Shift s : list) {
                        if (s.thisSite() && !avail.fits(s.start(), s.end())) {
                            out.add(new Problem("UNAVAILABLE", "WARNING", s.id(), s.employeeId(), s.employeeName(), day.getKey(),
                                "NONE".equals(avail.mode()) ? null : avail.windows().stream().map(w -> w.start() + "–" + w.end())
                                    .collect(Collectors.joining(", "))));
                        }
                    }
                }
                List<Shift> before = byDay.get(day.getKey().minusDays(1));
                if (before != null) {
                    LocalTime lastEnd = before.stream().map(Shift::end).max(Comparator.naturalOrder()).orElseThrow();
                    LocalTime firstStart = list.stream().map(Shift::start).min(Comparator.naturalOrder()).orElseThrow();
                    long rest = 24 * 60 - lastEnd.toSecondOfDay() / 60 + firstStart.toSecondOfDay() / 60;
                    if (rest < MIN_REST_MIN) {
                        out.add(new Problem("SHORT_REST", "WARNING", first.id(), first.employeeId(), first.employeeName(), day.getKey(),
                            rest / 60 + "h " + rest % 60 + "m"));
                    }
                }
            }
        }
        out.sort(Comparator.comparing((Problem p) -> !"ERROR".equals(p.severity())).thenComparing(Problem::date)
            .thenComparing(p -> p.employeeName() == null ? "" : p.employeeName()));
        return out;
    }
}
