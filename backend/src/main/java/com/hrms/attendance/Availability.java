package com.hrms.attendance;

import com.hrms.shared.tenancy.TenantContext;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When people can work: a weekly pattern, per weekday ANY (any time, the default), NONE, or up to three windows.
 * Planners see it on the roster, and publishing warns about shifts outside it.
 */
@Service
class Availability {

    static final int MAX_WINDOWS = 3;

    record Window(LocalTime start, LocalTime end) {
    }

    /** weekday: ISO 1 (Monday) to 7. */
    record Day(int weekday, String mode, List<Window> windows) {
        static Day any(int weekday) {
            return new Day(weekday, "ANY", List.of());
        }

        /** Whether a shift from start to end fits: any time, or inside one window. */
        boolean fits(LocalTime start, LocalTime end) {
            return switch (mode) {
                case "NONE" -> false;
                case "WINDOWS" -> windows.stream().anyMatch(w -> !start.isBefore(w.start()) && !end.isAfter(w.end()));
                default -> true;
            };
        }
    }

    private final JdbcClient jdbc;

    Availability(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All seven days for one person (unset days are ANY). */
    List<Day> of(UUID employeeId) {
        Map<Integer, Day> set = forPeople(List.of(employeeId)).getOrDefault(employeeId, Map.of());
        List<Day> out = new ArrayList<>();
        for (int d = 1; d <= 7; d++) out.add(set.getOrDefault(d, Day.any(d)));
        return out;
    }

    /** The days people have set, by person and weekday (unset days are missing: treat them as ANY). */
    Map<UUID, Map<Integer, Day>> forPeople(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Map<Integer, Day>> out = new HashMap<>();
        jdbc.sql("select employee_id, weekday, mode, starts, ends from availability_day where employee_id in (:ids)")
            .param("ids", ids)
            .query((rs, i) -> {
                List<Window> windows = windows(rs.getArray("starts"), rs.getArray("ends"));
                int weekday = rs.getInt("weekday");
                out.computeIfAbsent(rs.getObject("employee_id", UUID.class), k -> new HashMap<>())
                    .put(weekday, new Day(weekday, rs.getString("mode"), windows));
                return null;
            }).list();
        return out;
    }

    @Transactional
    List<Day> save(UUID employeeId, List<Day> days) {
        if (days == null) throw ShiftTemplates.bad("DAYS_REQUIRED", "Send your availability for each day.");
        for (Day d : days) check(d);
        for (Day d : days) {
            if ("ANY".equals(d.mode())) {
                jdbc.sql("delete from availability_day where employee_id = :emp and weekday = :day")
                    .param("emp", employeeId).param("day", d.weekday()).update();
                continue;
            }
            List<Window> w = "WINDOWS".equals(d.mode()) ? sorted(d.windows()) : List.of();
            jdbc.sql("""
                    insert into availability_day (tenant_id, employee_id, weekday, mode, starts, ends)
                    values (:tenant, :emp, :day, :mode, cast(:starts as time[]), cast(:ends as time[]))
                    on conflict (employee_id, weekday) do update set mode = excluded.mode, starts = excluded.starts,
                        ends = excluded.ends, updated_at = now()""")
                .param("tenant", TenantContext.require()).param("emp", employeeId).param("day", d.weekday())
                .param("mode", d.mode()).param("starts", literal(w.stream().map(Window::start).toList()))
                .param("ends", literal(w.stream().map(Window::end).toList())).update();
        }
        return of(employeeId);
    }

    static void check(Day d) {
        if (d == null || d.weekday() < 1 || d.weekday() > 7) throw ShiftTemplates.bad("BAD_DAY", "Days are 1 (Monday) to 7 (Sunday).");
        if (!List.of("ANY", "NONE", "WINDOWS").contains(d.mode())) throw ShiftTemplates.bad("BAD_MODE", "Choose any time, not available, or times.");
        if (!"WINDOWS".equals(d.mode())) return;
        List<Window> w = d.windows() == null ? List.of() : d.windows();
        if (w.isEmpty() || w.size() > MAX_WINDOWS) {
            throw ShiftTemplates.bad("BAD_WINDOWS", "Give one to " + MAX_WINDOWS + " times for a day.");
        }
        List<Window> s = sorted(w);
        for (int i = 0; i < s.size(); i++) {
            Window x = s.get(i);
            if (x.start() == null || x.end() == null || !x.end().isAfter(x.start())) {
                throw ShiftTemplates.bad("BAD_WINDOWS", "Each time must end after it starts, on the same day.");
            }
            if (i > 0 && x.start().isBefore(s.get(i - 1).end())) throw ShiftTemplates.bad("BAD_WINDOWS", "The times overlap.");
        }
    }

    private static List<Window> sorted(List<Window> w) {
        return w.stream().sorted(Comparator.comparing(Window::start, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    private static String literal(List<LocalTime> times) {
        return "{" + String.join(",", times.stream().map(LocalTime::toString).toList()) + "}";
    }

    private static List<Window> windows(Array starts, Array ends) throws SQLException {
        Object[] s = (Object[]) starts.getArray();
        Object[] e = (Object[]) ends.getArray();
        List<Window> out = new ArrayList<>();
        for (int i = 0; i < Math.min(s.length, e.length); i++) out.add(new Window(time(s[i]), time(e[i])));
        return out;
    }

    private static LocalTime time(Object o) {
        if (o instanceof java.sql.Time t) return t.toLocalTime();
        if (o instanceof LocalTime t) return t;
        return LocalTime.parse(o.toString());
    }
}
