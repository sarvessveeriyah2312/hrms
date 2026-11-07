package com.hrms.payroll;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which payslip layout the company uses ({@link PayslipTemplates}): a default HR picks, which each generation may
 * override. Also draws a sample payslip in any layout, so HR can see it before choosing.
 */
@Service
class PayslipSettings {

    record Choice(String defaultTemplate, List<PayslipTemplates.Template> templates) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    PayslipSettings(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    Choice choice() {
        return new Choice(defaultTemplate(), PayslipTemplates.ALL);
    }

    String defaultTemplate() {
        return jdbc.sql("select payslip_template from payroll_settings").query(String.class).optional()
            .filter(c -> PayslipTemplates.find(c).isPresent()).orElse(PayslipTemplates.DEFAULT);
    }

    /** The layout to use: the one asked for, or the company's default. */
    String resolve(String requested) {
        if (requested == null || requested.isBlank()) return defaultTemplate();
        return require(requested);
    }

    @Transactional
    Choice setDefault(UUID user, String code) {
        String c = require(code);
        jdbc.sql("""
                insert into payroll_settings (tenant_id, payslip_template, updated_at, updated_by) values (:tenant, :code, now(), :user)
                on conflict (tenant_id) do update set payslip_template = excluded.payslip_template, updated_at = now(),
                       updated_by = excluded.updated_by""")
            .param("tenant", TenantContext.require()).param("code", c).param("user", user).update();
        audit.record(user, "PAYSLIP_TEMPLATE_SET", "payroll_settings", null, Map.of("template", c));
        return choice();
    }

    /** A payslip in that layout with made-up figures (company name from the first legal entity). */
    byte[] sample(String code) {
        String c = require(code);
        record Entity(String name, String registrationNo, String currency) {
        }
        Entity e = jdbc.sql("select name, registration_no, currency from legal_entity order by created_at limit 1").query(Entity.class)
            .optional().orElse(new Entity("Demo Company Sdn Bhd", null, "MYR"));
        return PayslipTemplates.render(c, sampleSlip(e.name(), e.registrationNo(), e.currency()));
    }

    static PayslipTemplates.Slip sampleSlip(String company, String registrationNo, String currency) {
        List<PayslipPdf.Line> lines = List.of(
            line("EARNING", "BASIC", "Basic pay", "3000.00", "31", "3000"),
            line("EARNING", "ALLOWANCE", "Daily attendance", "23.07", null, null),
            line("EARNING", "ALLOWANCE", "Meal", "93.00", null, null),
            line("EARNING", "ALLOWANCE", "Night shift allowance", "10.00", null, null),
            line("EARNING", "OVERTIME", "Normal OT", "86.54", "4", "21.635"),
            line("EARNING", "OVERTIME", "Rest OT", "734.44", "38", "19.3274"),
            line("DEDUCTION", null, "Employee EPF", "319.00", null, null),
            line("DEDUCTION", null, "Employee SOCSO", "19.25", null, null),
            line("DEDUCTION", null, "Employee EIS", "7.70", null, null),
            line("DEDUCTION", null, "Employee PCB", "12.40", null, null),
            line("DEDUCTION", null, "Early out", "86.54", null, null),
            line("DEDUCTION", null, "Lateness", "14.40", null, null),
            line("DEDUCTION", null, "Time off", "1.15", null, null),
            line("EMPLOYER", null, "Employer EPF", "377.00", null, null),
            line("EMPLOYER", null, "Employer SOCSO", "67.35", null, null),
            line("EMPLOYER", null, "Employer EIS", "7.70", null, null));
        YearMonth period = YearMonth.now().minusMonths(1);
        BigDecimal gross = PayslipPdf.total(lines, "EARNING");
        BigDecimal deductions = PayslipPdf.total(lines, "DEDUCTION");
        return new PayslipTemplates.Slip(company, registrationNo, period, LocalDate.now(), currency, "JEFF001", "Jeffrey Toh",
            "900101-14-5678", "PRO", "Sales Executive", "12345677899", "123456789", "123456789",
            new PayslipTemplates.Attendance(22, 22, 6, 1, new BigDecimal("1.0"), 0),
            List.of(new PayslipTemplates.Leave("Annual leave", new BigDecimal("14"), new BigDecimal("2.5"), new BigDecimal("11.5")),
                new PayslipTemplates.Leave("Medical leave", new BigDecimal("14"), BigDecimal.ZERO, new BigDecimal("14"))),
            new PayslipTemplates.Ytd(new BigDecimal("3000.00"), new BigDecimal("126.07"), new BigDecimal("820.98"), gross, deductions,
                gross.subtract(deductions), new BigDecimal("452.05")),
            lines);
    }

    private static PayslipPdf.Line line(String kind, String category, String label, String amount, String quantity, String rate) {
        return new PayslipPdf.Line(kind, category, label, new BigDecimal(amount), quantity == null ? null : new BigDecimal(quantity),
            rate == null ? null : new BigDecimal(rate));
    }

    private static String require(String code) {
        return PayslipTemplates.find(code).map(PayslipTemplates.Template::code)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_TEMPLATE", "Choose one of the payslip templates."));
    }
}
