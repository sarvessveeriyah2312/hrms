package com.hrms.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payroll dashboard for a year: head count at each month end, joiners and leavers (from join dates and last days),
 * payroll cost from payslips that carry amounts (generated ones: gross pay plus employer contributions), approved claims
 * and paid overtime hours. Months after this one are left out.
 */
@Service
class PayrollDashboard {

    /** cost / claims: currency → amount. overtimeHours: approved paid overtime, hours × rate. */
    record Month(String month, int headcount, int joiners, int leavers, Map<String, BigDecimal> cost, int payslips,
                 Map<String, BigDecimal> claims, BigDecimal overtimeHours) {
    }

    record Department(String name, int headcount) {
    }

    /** turnover: leavers this year ÷ average head count, as a percentage (null without anyone employed). */
    record Dashboard(int year, int headcountNow, int joiners, int leavers, BigDecimal turnover, List<Month> months,
                     List<Department> departments, Map<String, BigDecimal> costThisYear) {
    }

    private final JdbcClient jdbc;

    PayrollDashboard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Dashboard build(int year) {
        LocalDate today = LocalDate.now();
        int lastMonth = year < today.getYear() ? 12 : year > today.getYear() ? 0 : today.getMonthValue();
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);

        record Money(int month, String currency, BigDecimal amount, int count) {
        }
        List<Money> cost = jdbc.sql("""
                select extract(month from period)::int as month, currency, sum(coalesce(gross, 0) + coalesce(employer_cost, 0)) as amount,
                       count(*)::int as count
                from payroll_document where kind = 'PAYSLIP' and gross is not null and period between :from and :to
                group by 1, 2""").param("from", from).param("to", to).query(Money.class).list();
        List<Money> claims = jdbc.sql("""
                select extract(month from expense_date)::int as month, currency, sum(amount) as amount, count(*)::int as count
                from claim where status = 'APPROVED' and expense_date between :from and :to group by 1, 2""")
            .param("from", from).param("to", to).query(Money.class).list();
        record Hours(int month, BigDecimal hours) {
        }
        Map<Integer, BigDecimal> overtime = new LinkedHashMap<>();
        jdbc.sql("""
                select extract(month from work_date)::int as month, sum(hours * coalesce(rate, 1)) as hours
                from overtime_request where status = 'APPROVED' and compensation = 'PAY' and work_date between :from and :to
                group by 1""").param("from", from).param("to", to).query(Hours.class).list()
            .forEach(h -> overtime.put(h.month(), h.hours()));

        List<Month> months = new ArrayList<>();
        int joinersYear = 0;
        int leaversYear = 0;
        long headcountSum = 0;
        Map<String, BigDecimal> costYear = new TreeMap<>();
        for (int m = 1; m <= lastMonth; m++) {
            YearMonth ym = YearMonth.of(year, m);
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            record Counts(int headcount, int joiners, int leavers) {
            }
            Counts c = jdbc.sql("""
                    select count(*) filter (where join_date <= :end and (last_day is null or last_day > :end)
                                             and (status = 'ACTIVE' or last_day is not null))::int as headcount,
                           count(*) filter (where join_date between :start and :end)::int as joiners,
                           count(*) filter (where last_day between :start and :end)::int as leavers
                    from employee""").param("start", start).param("end", end).query(Counts.class).single();
            int month = m;
            Map<String, BigDecimal> monthCost = new TreeMap<>();
            int payslips = 0;
            for (Money x : cost) {
                if (x.month() != month) continue;
                monthCost.put(x.currency(), x.amount());
                costYear.merge(x.currency(), x.amount(), BigDecimal::add);
                payslips += x.count();
            }
            Map<String, BigDecimal> monthClaims = new TreeMap<>();
            claims.stream().filter(x -> x.month() == month).forEach(x -> monthClaims.put(x.currency(), x.amount()));
            months.add(new Month(ym.toString(), c.headcount(), c.joiners(), c.leavers(), monthCost, payslips, monthClaims,
                overtime.getOrDefault(m, BigDecimal.ZERO)));
            joinersYear += c.joiners();
            leaversYear += c.leavers();
            headcountSum += c.headcount();
        }
        BigDecimal turnover = months.isEmpty() || headcountSum == 0 ? null
            : BigDecimal.valueOf(leaversYear * 100L * months.size()).divide(BigDecimal.valueOf(headcountSum), 1, java.math.RoundingMode.HALF_UP);

        List<Department> departments = jdbc.sql("""
                select coalesce(d.name, 'No department') as name, count(*)::int as headcount
                from employee e left join department d on d.id = e.department_id
                where e.status = 'ACTIVE' and e.join_date <= current_date and (e.last_day is null or e.last_day >= current_date)
                group by 1 order by 2 desc, 1""").query(Department.class).list();
        int now = departments.stream().mapToInt(Department::headcount).sum();
        return new Dashboard(year, now, joinersYear, leaversYear, turnover, months, departments, costYear);
    }
}
