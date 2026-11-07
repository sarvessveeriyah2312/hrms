package com.hrms.payroll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * The payslip layouts HR can choose from (the designs in /paysliptemplate), drawn on A4 landscape. Each layout is laid
 * out on the same 1278 × 827 grid as its reference design and scaled to the page, so positions read straight off the
 * design. Lists that are longer than a box holds are squeezed to fit.
 */
final class PayslipTemplates {

    record Template(String code, String name, String description, String preview) {
    }

    /** The shipped layouts, in the order shown to HR. The first is the default. */
    static final List<Template> ALL = List.of(
        new Template("CLASSIC", "Classic", "Payment summary on the left; allowances, deductions, overtime and employer contributions in boxes.", "payslip-1.jpg"),
        new Template("DETAILED", "Detailed", "Statutory numbers and attendance, earnings and deductions side by side, employer contributions and leave.", "payslip-2.jpg"),
        new Template("SIMPLE", "Simple", "A plain summary without boxes: basic pay, allowances, overtime and deductions.", "payslip-3.jpg"),
        new Template("STANDARD", "Earnings and deductions", "Two columns with totals, employer contributions and sign-off lines.", "payslip-4.jpg"),
        new Template("YEAR_TO_DATE", "Year to date", "Earnings, deductions, year-to-date totals and attendance, with leave balances.", "payslip-5.jpg"),
        new Template("THREE_COLUMN", "Three columns", "Earnings, deductions and attendance figures, with employer contributions and leave balances.", "payslip-6.jpg"),
        new Template("LEDGER", "Ledger", "Overtime with hourly rates and hours, and a one-line summary along the bottom.", "payslip-7.jpg"),
        new Template("MODERN", "Modern", "A4 portrait with a shaded net pay band.", null));

    static final String DEFAULT = "CLASSIC";

    static Optional<Template> find(String code) {
        return ALL.stream().filter(t -> t.code().equals(code)).findFirst();
    }

    // ---------- what a payslip shows ----------

    record Attendance(int workingDays, int daysWorked, int earlyOut, int late, BigDecimal leaveDays, int absent) {
    }

    record Leave(String name, BigDecimal entitled, BigDecimal used, BigDecimal available) {
    }

    record Ytd(BigDecimal basic, BigDecimal allowances, BigDecimal overtime, BigDecimal gross, BigDecimal deductions,
               BigDecimal net, BigDecimal employer) {
    }

    /** Everything a layout may print. Missing values print blank. */
    record Slip(String company, String registrationNo, YearMonth period, LocalDate date, String currency,
                String employeeNo, String name, String idNumber, String department, String position,
                String epfNo, String socsoNo, String taxNo, Attendance attendance, List<Leave> leave, Ytd ytd,
                List<PayslipPdf.Line> lines) {

        List<PayslipPdf.Line> of(String kind, String category) {
            return lines.stream().filter(l -> l.kind().equals(kind) && (category == null || category.equals(l.category()))).toList();
        }

        List<PayslipPdf.Line> basicLines() { return of("EARNING", "BASIC"); }
        List<PayslipPdf.Line> allowances() { return of("EARNING", "ALLOWANCE"); }
        List<PayslipPdf.Line> overtime() { return of("EARNING", "OVERTIME"); }
        List<PayslipPdf.Line> earnings() { return of("EARNING", null); }
        List<PayslipPdf.Line> deductions() { return of("DEDUCTION", null); }
        List<PayslipPdf.Line> employer() { return of("EMPLOYER", null); }
        BigDecimal basic() { return sum(basicLines()); }
        BigDecimal allowance() { return sum(allowances()); }
        BigDecimal overtimePay() { return sum(overtime()); }
        BigDecimal gross() { return sum(earnings()); }
        BigDecimal deduction() { return sum(deductions()); }
        BigDecimal net() { return gross().subtract(deduction()); }

        /** The monthly basic rate: the rate on the basic pay line, else the basic pay itself. */
        BigDecimal basicRate() {
            return basicLines().stream().map(PayslipPdf.Line::rate).filter(r -> r != null).findFirst().orElse(basic());
        }

        /** Working days: the days on the basic pay line, else the attendance's working days. */
        BigDecimal workingDays() {
            return basicLines().stream().map(PayslipPdf.Line::quantity).filter(q -> q != null).findFirst()
                .orElse(attendance == null ? null : BigDecimal.valueOf(attendance.workingDays()));
        }

        /** Unpaid leave deducted (lines whose item mentions unpaid leave). */
        BigDecimal unpaidLeave() {
            return sum(deductions().stream().filter(l -> l.label().toLowerCase(Locale.ROOT).matches(".*(unpaid|upl|no.?pay).*")).toList());
        }

        String title() {
            return "END PAYMENT  -  " + period.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)).toUpperCase(Locale.ENGLISH);
        }

        String dateText() {
            return date.format(DateTimeFormatter.ofPattern("d/M/yyyy"));
        }
    }

    static BigDecimal sum(List<PayslipPdf.Line> lines) {
        return lines.stream().map(PayslipPdf.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PayslipTemplates() {
    }

    // ---------- drawing ----------

    static byte[] render(String code, Slip s) {
        if ("MODERN".equals(code)) {
            return PayslipPdf.generate(new PayslipPdf.Header(s.company(), s.registrationNo(), s.name(), s.employeeNo(), s.department(),
                s.position(), s.period(), s.currency()), s.lines(), s.date());
        }
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                Canvas k = new Canvas(c, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                switch (code) {
                    case "DETAILED" -> detailed(k, s);
                    case "SIMPLE" -> simple(k, s);
                    case "STANDARD" -> standard(k, s);
                    case "YEAR_TO_DATE" -> yearToDate(k, s);
                    case "THREE_COLUMN" -> threeColumn(k, s);
                    case "LEDGER" -> ledger(k, s);
                    default -> classic(k, s);
                }
                k.text(57, 800, "Computer-generated payslip. Amounts in " + (s.currency() == null ? "local currency" : s.currency()) + ".", 11, false);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't draw the payslip", e);
        }
    }

    /** 1. Classic: payment summary left; allowance / deduction / overtime / other boxes. */
    private static void classic(Canvas k, Slip s) throws IOException {
        k.box(57, 45, 1190, 758, 1.2f);
        k.hline(57, 1190, 95, 1.6f);
        k.text(72, 77, s.company(), 17, true);
        k.text(686, 77, "DATE :  " + s.dateText(), 15, false);
        k.right(1175, 77, s.title(), 16, true);
        k.vline(633, 95, 758);
        k.vline(911, 95, 758);

        k.text(72, 122, "NAME :   " + nz(s.name()), 15, false);
        String[][] info = {{"I/C NO.", s.idNumber()}, {"EMPLOYEE", s.employeeNo()}, {"DEPARTMENT", s.department()}, {"POSITION", s.position()}};
        for (int i = 0; i < info.length; i++) {
            float y = 174 + i * 25.5f;
            k.text(72, y, info[i][0], 15, false);
            k.text(205, y, ":", 15, false);
            k.fit(235, y, info[i][1], 15, false, 390);
        }
        k.hline(57, 633, 262);
        k.text(72, 287, "PAYMENT", 15, true);
        k.hline(57, 633, 299);
        k.text(72, 329, "BASIC RATE", 15, false);
        k.right(368, 329, money(s.basicRate()), 15, false);
        k.text(375, 329, "/  Monthly", 15, false);
        k.text(72, 354, "WORKING DAYS", 15, false);
        k.right(366, 354, num(s.workingDays()), 15, false);
        k.text(72, 405, "BASIC PAY", 15, false);
        k.right(366, 405, money(s.basic()), 15, false);
        k.text(72, 453, "OVERTIME", 15, false);
        k.right(366, 453, money(s.overtimePay()), 15, false);
        k.text(72, 480, "ALLOWANCE", 15, false);
        k.right(366, 480, money(s.allowance()), 15, false);
        k.hline(219, 368, 502);
        k.text(72, 554, "GROSS PAY", 15, false);
        k.right(366, 554, money(s.gross()), 15, false);
        k.text(72, 579, "DEDUCTION", 15, false);
        k.right(366, 579, money(s.deduction()), 15, false);
        k.hline(219, 370, 597);
        k.text(72, 624, "NET PAY", 15, false);
        k.right(366, 624, money(s.net()), 15, false);
        k.hline(219, 370, 633);
        k.hline(219, 370, 645);
        k.hline(392, 615, 706);
        k.text(395, 729, "EMPLOYEE'S SIGNATURE", 15, false);

        k.text(640, 119, "ALLOWANCE", 15, true);
        k.text(918, 119, "DEDUCTION", 15, true);
        k.hline(633, 1190, 131);
        k.rows(640, 900, 152, 470, 25, s.allowances(), 14);
        k.rows(918, 1180, 152, 470, 25, s.deductions(), 14);
        k.hline(633, 1190, 476);
        k.text(640, 502, "OVERTIME", 15, true);
        k.text(918, 502, "OTHER", 15, true);
        k.hline(633, 1190, 514);
        k.rows(640, 900, 536, 752, 25, s.overtime(), 14);
        List<PayslipPdf.Line> other = new ArrayList<>();
        other.add(new PayslipPdf.Line("OTHER", null, "MONTHLY GROSS", s.gross(), null, null));
        other.addAll(s.employer());
        k.rows(918, 1180, 545, 752, 24, other, 14);
    }

    /** 2. Detailed: statutory numbers, attendance, earnings and deductions side by side. */
    private static void detailed(Canvas k, Slip s) throws IOException {
        k.box(68, 40, 1197, 242, 1.2f);
        k.hline(68, 1197, 80);
        k.text(80, 63, s.company(), 21, false);
        k.right(1185, 58, s.title(), 15, true);
        String[][] left = {{"EMPLOYEE NO.", s.employeeNo()}, {"NAME", s.name()}, {"IC NO.", s.idNumber()}, {"DEPARTMENT", s.department()}};
        for (int i = 0; i < left.length; i++) {
            k.text(80, 100 + i * 25, left[i][0], 15, false);
            k.fit(292, 100 + i * 25, left[i][1], 15, false, 300);
        }
        k.text(80, 200, "BASE RATE", 15, false);
        k.right(423, 202, money(s.basicRate()), 15, false);
        k.text(80, 225, "WORKING DAYS", 15, false);
        k.right(423, 227, num(s.workingDays()), 15, false);
        String[][] right = {{"POSITION", s.position()}, {"EPF NO.", s.epfNo()}, {"SOCSO NO.", s.socsoNo()}, {"TAX NO.", s.taxNo()}};
        for (int i = 0; i < right.length; i++) {
            k.text(862, 100 + i * 25, right[i][0], 15, false);
            k.fit(963, 100 + i * 25, right[i][1], 15, false, 225);
        }
        attendanceStrip(k, s, new float[] {630, 700, 761, 830}, 200, 225, true);

        k.box(68, 242, 1197, 610, 1.2f);
        k.text(80, 264, "EARNINGS / INCOME", 15, false);
        k.text(683, 264, "DEDUCTION", 15, false);
        k.hline(68, 1197, 279);
        k.vline(672, 242, 610);
        k.rows(78, 657, 302, 540, 25.5f, s.earnings(), 15);
        k.rows(681, 1185, 302, 540, 25.5f, s.deductions(), 15);
        k.hline(68, 1197, 550);
        k.text(88, 570, "GROSS PAY", 15, false);
        k.right(657, 570, money(s.gross()), 15, false);
        k.text(680, 570, "TOTAL DEDUCTION", 15, false);
        k.right(1185, 570, money(s.deduction()), 15, false);
        k.hline(68, 1197, 580);
        k.text(680, 600, "NET PAY", 15, false);
        k.right(1185, 600, money(s.net()), 15, true);

        k.box(68, 610, 480, 707, 1.2f);
        k.vline(272, 610, 707);
        k.rows(80, 465, 630, 700, 25.5f, s.employer(), 15);
        leaveLines(k, s, 673, 650, 885, 2);
        k.text(87, 752, "APPROVED BY :", 15, false);
        k.text(736, 752, "RECEIVED BY :", 15, false);
    }

    /** 3. Simple: no boxes, summary on the right. */
    private static void simple(Canvas k, Slip s) throws IOException {
        k.text(62, 68, s.company(), 22, false);
        k.text(62, 103, "PAYSLIP", 18, false);
        String[][] info = {{"NAME :", s.name()}, {"NRIC :", s.idNumber()}, {"POSITION :", s.position()}, {"EMPLOYEE NO :", s.employeeNo()}};
        for (int i = 0; i < info.length; i++) {
            k.text(62, 135 + i * 26.5f, info[i][0], 15, false);
            k.fit(200, 135 + i * 26.5f, info[i][1], 15, false, 420);
        }
        k.right(1183, 63, s.title(), 16, true);
        k.text(721, 98, "BASIC PAY", 15, false);
        k.right(1183, 98, money(s.basic()), 15, false);
        k.text(721, 124, "ALLOWANCE", 15, false);
        k.right(1183, 124, money(s.allowance()), 15, false);
        k.text(721, 149, "OVERTIME", 15, false);
        k.right(1183, 149, money(s.overtimePay()), 15, false);
        k.hline(721, 1183, 195, 1.5f);
        k.text(721, 224, "GROSS PAY", 15, true);
        k.right(1183, 224, money(s.gross()), 15, true);
        k.rows(721, 1183, 263, 470, 26, s.deductions(), 15);
        k.hline(721, 1183, 488, 1.5f);
        k.text(721, 517, "DEDUCTION", 15, true);
        k.right(1183, 517, money(s.deduction()), 15, true);
        k.hline(721, 1183, 545, 1.5f);
        k.text(721, 574, "NET PAY", 15, true);
        k.right(1183, 574, money(s.net()), 15, true);
        k.hline(721, 1183, 591, 1.2f);
        k.hline(721, 1183, 597, 1.2f);
        k.text(58, 472, "APPROVED BY :", 15, false);
        k.text(297, 472, "RECEIVED BY :", 15, false);
        k.hline(55, 231, 560);
        k.hline(293, 469, 560);
    }

    /** 4. Earnings and deductions: two columns with totals and sign-off lines. */
    private static void standard(Canvas k, Slip s) throws IOException {
        k.box(57, 47, 1185, 172, 1.2f);
        k.text(73, 83, s.company(), 21, false);
        k.right(1125, 77, s.title(), 15, true);
        k.text(73, 126, "NAME :", 15, false);
        k.fit(210, 126, s.name(), 15, false, 500);
        k.text(73, 151, "NRIC :", 15, false);
        k.fit(210, 151, s.idNumber(), 15, false, 500);
        k.text(747, 126, "EMPLOYEE NO :  " + nz(s.employeeNo()), 15, false);
        k.text(747, 151, "POSITION :", 15, false);
        k.fit(886, 151, s.position(), 15, false, 290);

        k.box(57, 190, 1185, 700, 1.2f);
        k.hline(57, 1185, 238);
        k.center(328, 219, "EARNINGS", 15, true);
        k.center(891, 219, "DEDUCTIONS", 15, true);
        k.vline(598, 190, 700);
        k.rows(72, 583, 259, 550, 25.5f, s.earnings(), 15);
        k.rows(608, 1170, 259, 550, 25.5f, s.deductions(), 15);
        k.hline(57, 1185, 560);
        k.text(72, 582, "TOTAL EARNINGS", 15, false);
        k.right(585, 582, money(s.gross()), 15, false);
        k.text(614, 582, "TOTAL DEDUCTIONS", 15, false);
        k.right(1170, 582, money(s.deduction()), 15, false);
        k.hline(57, 1185, 617);
        k.rows(73, 583, 634, 696, 25, s.employer(), 15);
        k.text(617, 640, "NET SALARY", 15, false);
        k.right(1167, 640, money(s.net()), 15, true);
        k.text(73, 727, "CHECKED BY :", 15, false);
        k.hline(207, 420, 733);
        k.text(460, 727, "APPROVED BY :", 15, false);
        k.hline(597, 809, 733);
        k.text(836, 727, "RECEIVED BY :", 15, false);
        k.hline(972, 1184, 733);
    }

    /** 5. Year to date: four columns, employer contributions and leave. */
    private static void yearToDate(Canvas k, Slip s) throws IOException {
        float right = 1212;
        boxedHeader(k, s, right, 876, 983);
        k.text(68, 278, "EARNINGS / INCOME", 15, false);
        k.text(374, 278, "DEDUCTION", 15, false);
        k.text(660, 278, "YEAR-TO-DATE", 15, false);
        k.text(955, 278, "OTHERS", 15, false);
        k.hline(57, right, 288);
        k.vline(363, 258, 585);
        k.vline(653, 258, 698);
        k.vline(946, 258, 533);
        k.rows(66, 355, 308, 525, 25.5f, s.earnings(), 13);
        k.rows(372, 647, 308, 525, 25.5f, s.deductions(), 13);
        k.rows(661, 940, 308, 525, 25.5f, ytdLines(s), 13);
        k.rows(955, 1205, 308, 525, 25.5f, others(s), 13, true);
        k.hline(57, right, 533);
        k.text(75, 556, "TOTAL", 14, false);
        k.right(352, 556, money(s.gross()), 14, false);
        k.text(375, 556, "TOTAL", 14, false);
        k.right(645, 556, money(s.deduction()), 14, false);
        k.text(661, 556, "NET PAY", 14, false);
        k.right(1202, 556, money(s.net()), 14, true);
        k.hline(57, right, 585);
        employerCells(k, s, 57, 363, 653, 585, 632, 698, new float[] {57, 207, 363});
        k.hline(57, right, 698);
        k.vline(57, 585, 698);
        k.vline(right, 585, 698);
        leaveTable(k, s, 873, 1098, 1205, 623);
        k.text(141, 764, "APPROVED BY :", 15, false);
        k.hline(276, 490, 768);
        k.text(825, 764, "RECEIVED BY :", 15, false);
        k.hline(958, 1170, 768);
    }

    /** 6. Three columns: earnings, deductions and attendance; employer contributions and leave. */
    private static void threeColumn(Canvas k, Slip s) throws IOException {
        float right = 1185;
        boxedHeader(k, s, right, 848, 948);
        k.text(68, 278, "EARNINGS / INCOME", 15, false);
        k.text(483, 278, "DEDUCTION", 15, false);
        k.text(905, 278, "OTHERS", 15, false);
        k.hline(57, right, 288);
        k.vline(471, 258, 757);
        k.vline(896, 258, 643);
        k.rows(66, 455, 308, 580, 25.5f, s.earnings(), 15);
        k.rows(481, 883, 308, 580, 25.5f, s.deductions(), 15);
        k.rows(903, 1175, 308, 580, 25.5f, others(s), 15, true);
        k.hline(57, right, 590);
        k.vline(264, 590, 643);
        k.text(75, 618, "TOTAL", 15, false);
        k.right(440, 618, money(s.gross()), 15, false);
        k.text(483, 618, "TOTAL", 15, false);
        k.right(883, 618, money(s.deduction()), 15, false);
        k.text(905, 618, "NET PAY", 15, false);
        k.right(1175, 618, money(s.net()), 15, true);
        k.hline(57, right, 643);
        employerCells(k, s, 57, 471, 471, 643, 690, 757, new float[] {57, 190, 338});
        k.hline(57, right, 757);
        k.vline(57, 643, 757);
        k.vline(right, 643, 757);
        leaveTable(k, s, 845, 1082, 1175, 672);
    }

    /** 7. Ledger: overtime with rates and hours, summary line along the bottom. */
    private static void ledger(Canvas k, Slip s) throws IOException {
        k.text(58, 60, s.company(), 16, true);
        k.right(1187, 60, s.title(), 16, true);
        k.right(1187, 85, "MONTHLY  /  BANK", 16, true);
        k.right(1166, 108, "DATE :  " + s.dateText(), 15, false);
        String[][] info = {{"EMPLOYEE / LINE NO.", s.employeeNo()}, {"DEPARTMENT", s.department()}};
        for (int i = 0; i < info.length; i++) {
            k.text(58, 109 + i * 25.5f, info[i][0], 15, false);
            k.text(280, 109 + i * 25.5f, ":", 15, false);
            k.fit(292, 109 + i * 25.5f, info[i][1], 15, false, 150);
        }
        k.text(58, 160, "BASIC RATE", 15, false);
        k.text(280, 160, ":  " + money(s.basicRate()), 15, false);
        k.text(415, 160, "/  Monthly", 15, false);
        k.text(58, 186, "WORKING DAYS", 15, false);
        k.text(280, 186, ":  " + num(s.workingDays()), 15, false);
        k.right(515, 109, "NAME :", 15, false);
        k.fit(540, 109, s.name(), 15, false, 330);
        k.right(520, 134, "I/C NO. :", 15, false);
        k.fit(540, 134, s.idNumber(), 15, false, 330);

        k.hline(56, 618, 213, 2f);
        attendanceStrip(k, s, new float[] {58, 106, 179, 243}, 240, 266, false);
        k.hline(56, 618, 290, 2f);
        k.text(58, 316, "BASIC PAY", 15, false);
        k.text(285, 316, money(s.basic()), 15, false);
        k.text(58, 377, "OVERTIME", 15, false);
        k.text(330, 377, "HOURLY", 15, false);
        k.text(330, 396, "RATE", 15, false);
        k.text(418, 377, "HOURS", 15, false);
        k.text(541, 377, "AMOUNT", 15, false);
        k.hline(56, 618, 410, 1.5f);
        List<PayslipPdf.Line> ot = s.overtime();
        float pitch = k.pitch(428, 520, 26, ot.size());
        for (int i = 0; i < ot.size(); i++) {
            PayslipPdf.Line l = ot.get(i);
            float y = 428 + i * pitch;
            k.fit(58, y, l.label(), 15, false, 250);
            k.right(412, y, l.rate() == null ? "" : money(l.rate()), 15, false);
            k.right(489, y, l.quantity() == null ? "" : money(l.quantity()), 15, false);
            k.right(620, y, money(l.amount()), 15, false);
        }

        k.text(634, 216, "ALLOWANCE", 15, false);
        k.hline(631, 905, 228, 1.5f);
        k.text(916, 216, "DEDUCTION", 15, false);
        k.hline(912, 1175, 228, 1.5f);
        k.rows(632, 900, 248, 510, 26, s.allowances(), 15);
        k.rows(914, 1175, 248, 510, 26, s.deductions(), 15);
        k.hline(631, 905, 524, 1.5f);
        k.hline(912, 1175, 524, 1.5f);
        k.text(634, 556, "MONTHLY GROSS", 15, false);
        k.right(902, 556, money(s.gross()), 15, false);
        List<PayslipPdf.Line> employer = s.employer();
        for (int i = 0; i < Math.min(employer.size(), 4); i++) {
            boolean left = i < 2;
            float y = 582 + (i % 2) * 25.5f;
            k.fit(left ? 634 : 916, y, employer.get(i).label(), 15, false, 190);
            k.right(left ? 902 : 1175, y, money(employer.get(i).amount()), 15, false);
        }
        leaveLines(k, s, 634, 633, 902, 2);

        String[] heads = {"BASIC PAY", "OVERTIME", "ALLOWANCE", "GROSS PAY", "DEDUCTION", "UPL", "GROSS DEDT.", "NET PAY"};
        float[] xs = {58, 160, 263, 377, 507, 617, 728, 859};
        BigDecimal upl = s.unpaidLeave();
        String[] values = {money(s.basic()), money(s.overtimePay()), money(s.allowance()), money(s.gross()),
            money(s.deduction().subtract(upl)), money(upl), money(s.deduction()), money(s.net())};
        for (int i = 0; i < heads.length; i++) {
            k.text(xs[i], 698, heads[i], 15, false);
            k.text(xs[i], 736, values[i], 15, i == heads.length - 1);
        }
        k.hline(56, 955, 712, 1.5f);
        k.hline(968, 1197, 712, 1.5f);
        k.text(975, 736, "EMPLOYEE'S SIGNATURE", 15, false);
    }

    // ---------- shared parts ----------

    /** The header block of the year-to-date and three-column layouts. */
    private static void boxedHeader(Canvas k, Slip s, float right, float labelX, float valueX) throws IOException {
        k.box(57, 46, right, 258, 1.2f);
        k.text(68, 78, s.company(), 21, false);
        k.text(68, 113, "PAYSLIP", 18, false);
        k.right(right - 54, 77, s.title(), 15, true);
        k.right(right - 42, 109, "DATE :  " + s.dateText(), 15, false);
        k.hline(57, right, 133);
        String[][] left = {{"EMPLOYEE NO.", s.employeeNo()}, {"NAME", s.name()}, {"IC NO.", s.idNumber()}, {"DEPARTMENT", s.department()}};
        for (int i = 0; i < left.length; i++) {
            k.text(68, 159 + i * 25.3f, left[i][0], 15, false);
            k.fit(281, 159 + i * 25.3f, left[i][1], 15, false, 420);
        }
        String[][] stat = {{"EPF NO.", s.epfNo()}, {"SOCSO NO.", s.socsoNo()}, {"TAX NO.", s.taxNo()}};
        for (int i = 0; i < stat.length; i++) {
            k.text(labelX, 185 + i * 25.3f, stat[i][0], 15, false);
            k.fit(valueX, 185 + i * 25.3f, stat[i][1], 15, false, right - valueX - 10);
        }
    }

    /** DW (days worked), EO (early outs), LAT (late), TOF (time off: leave days). */
    private static void attendanceStrip(Canvas k, Slip s, float[] xs, float headY, float valueY, boolean centered) throws IOException {
        String[] heads = {"DW", "EO", "LAT", "TOF"};
        Attendance a = s.attendance();
        String[] values = a == null ? new String[] {"", "", "", ""}
            : new String[] {money(BigDecimal.valueOf(a.daysWorked())), money(BigDecimal.valueOf(a.earlyOut())),
                money(BigDecimal.valueOf(a.late())), money(a.leaveDays())};
        for (int i = 0; i < heads.length; i++) {
            if (centered) {
                k.center(xs[i], headY, heads[i], 15, false);
                k.center(xs[i], valueY, values[i], 15, false);
            } else {
                k.text(xs[i], headY, heads[i], 15, false);
                k.text(xs[i], valueY, values[i], 15, false);
            }
        }
    }

    /** "YTD Annual leave   2.50 DAYS [ 11.50]": days taken this year and what's left. */
    private static void leaveLines(Canvas k, Slip s, float x, float y, float valueRight, int max) throws IOException {
        List<Leave> leave = s.leave() == null ? List.of() : s.leave();
        for (int i = 0; i < Math.min(max, leave.size()); i++) {
            Leave l = leave.get(i);
            float yy = y + i * 25.5f;
            k.fit(x, yy, "YTD " + initials(l.name()), 15, false, valueRight - x - 150);
            k.right(valueRight, yy, money(l.used()) + " DAYS  [ " + money(l.available()) + "]", 15, false);
        }
    }

    /** LEAVE / ENTITLEMENT / BALANCE table. */
    private static void leaveTable(Canvas k, Slip s, float x, float entRight, float balRight, float y) throws IOException {
        List<Leave> leave = s.leave() == null ? List.of() : s.leave();
        if (leave.isEmpty()) return;
        k.text(x, y, "LEAVE", 15, false);
        k.right(entRight + 12, y, "ENTITLEMENT", 15, false);
        k.right(balRight, y, "BALANCE", 15, false);
        k.hline(x - 3, balRight, y + 7);
        for (int i = 0; i < Math.min(2, leave.size()); i++) {
            Leave l = leave.get(i);
            float yy = y + 24 + i * 25;
            k.fit(x, yy, l.name().toUpperCase(Locale.ENGLISH), 13, false, entRight - x - 80);
            k.right(entRight, yy, money(l.entitled()), 13, false);
            k.right(balRight, yy, money(l.available()), 13, false);
        }
    }

    /** "EMPLOYER CONTRIBUTIONS" with up to three cells: "EPF : 377.00". */
    private static void employerCells(Canvas k, Slip s, float left, float headRight, float right, float top, float mid, float bottom,
                                      float[] cellXs) throws IOException {
        k.center((left + headRight) / 2, (top + mid) / 2 + 6, "EMPLOYER CONTRIBUTIONS", 15, false);
        k.hline(left, headRight, mid);
        k.vline(right, top, bottom);
        List<PayslipPdf.Line> employer = s.employer();
        for (int i = 0; i < Math.min(3, employer.size()); i++) {
            if (i > 0) k.vline(cellXs[i], mid, bottom);
            float cellRight = i + 1 < cellXs.length ? cellXs[i + 1] : right;
            k.fit(cellXs[i] + 12, (mid + bottom) / 2 + 6, shortLabel(employer.get(i).label()) + " :  " + money(employer.get(i).amount()),
                15, false, cellRight - cellXs[i] - 18);
        }
    }

    /** "Annual leave" → "AL", as payslips usually print leave types. */
    static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String w : name.trim().split("\\s+")) if (!w.isEmpty() && out.length() < 3) out.append(Character.toUpperCase(w.charAt(0)));
        return out.toString();
    }

    /** "Employer EPF (13%)" → "EPF". */
    static String shortLabel(String label) {
        String l = label.replaceAll("(?i)employer|e'?yer|'yer|\\(.*?\\)", "").trim();
        return l.isEmpty() ? label : l.toUpperCase(Locale.ENGLISH);
    }

    private static List<PayslipPdf.Line> ytdLines(Slip s) {
        Ytd y = s.ytd();
        if (y == null) return List.of();
        return List.of(line("BASIC PAY", y.basic()), line("ALLOWANCES", y.allowances()), line("OVERTIME", y.overtime()),
            line("GROSS PAY", y.gross()), line("DEDUCTIONS", y.deductions()), line("NET PAY", y.net()),
            line("EMPLOYER CONTRIBUTIONS", y.employer()));
    }

    /** Attendance figures and overtime hours for the "Others" column. */
    private static List<PayslipPdf.Line> others(Slip s) {
        List<PayslipPdf.Line> out = new ArrayList<>();
        BigDecimal wd = s.workingDays();
        if (wd != null) out.add(line("WORKING DAYS", wd));
        Attendance a = s.attendance();
        if (a != null) out.add(line("DAY WORK", BigDecimal.valueOf(a.daysWorked())));
        for (PayslipPdf.Line ot : s.overtime()) if (ot.quantity() != null) out.add(line(ot.label().toUpperCase(Locale.ENGLISH), ot.quantity()));
        if (a != null) {
            out.add(line("EARLY OUT", BigDecimal.valueOf(a.earlyOut())));
            out.add(line("LATENESS", BigDecimal.valueOf(a.late())));
            out.add(line("LEAVE DAYS", a.leaveDays()));
        }
        return out;
    }

    private static PayslipPdf.Line line(String label, BigDecimal amount) {
        return new PayslipPdf.Line("OTHER", null, label, amount == null ? BigDecimal.ZERO : amount, null, null);
    }

    static String money(BigDecimal v) {
        if (v == null) return "";
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String num(BigDecimal v) {
        return v == null ? "" : money(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Draws on the reference designs' 1278 × 827 grid (y down, baselines), scaled to the page and centred vertically.
     * Font sizes are in grid units too.
     */
    static final class Canvas {
        private static final float GRID_W = 1278;
        private static final float GRID_H = 827;
        private final PDPageContentStream c;
        private final float scale;
        private final float pageH;
        private final float offsetY;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        Canvas(PDPageContentStream c, float pageW, float pageH) {
            this.c = c;
            this.scale = Math.min(pageW / GRID_W, pageH / GRID_H);
            this.pageH = pageH;
            this.offsetY = (pageH - GRID_H * scale) / 2;
        }

        private float x(float gx) { return gx * scale; }
        private float y(float gy) { return pageH - offsetY - gy * scale; }

        void text(float gx, float gy, String s, float size, boolean isBold) throws IOException {
            if (s == null || s.isEmpty()) return;
            PDType1Font f = isBold ? bold : regular;
            c.beginText();
            c.setFont(f, size * scale);
            c.newLineAtOffset(x(gx), y(gy));
            c.showText(PayslipPdf.safe(f, s));
            c.endText();
        }

        /** Text cut with "..." to fit a width (grid units). */
        void fit(float gx, float gy, String s, float size, boolean isBold, float maxWidth) throws IOException {
            if (s == null) return;
            PDType1Font f = isBold ? bold : regular;
            String t = PayslipPdf.safe(f, s);
            if (width(f, t, size) > maxWidth) {
                while (t.length() > 1 && width(f, t + "...", size) > maxWidth) t = t.substring(0, t.length() - 1);
                t = t.stripTrailing() + "...";
            }
            text(gx, gy, t, size, isBold);
        }

        void right(float gxRight, float gy, String s, float size, boolean isBold) throws IOException {
            if (s == null || s.isEmpty()) return;
            PDType1Font f = isBold ? bold : regular;
            text(gxRight - width(f, PayslipPdf.safe(f, s), size), gy, s, size, isBold);
        }

        void center(float gxCenter, float gy, String s, float size, boolean isBold) throws IOException {
            if (s == null || s.isEmpty()) return;
            PDType1Font f = isBold ? bold : regular;
            text(gxCenter - width(f, PayslipPdf.safe(f, s), size) / 2, gy, s, size, isBold);
        }

        private float width(PDType1Font f, String s, float size) throws IOException {
            return f.getStringWidth(s) / 1000 * size;
        }

        void hline(float gx1, float gx2, float gy) throws IOException {
            hline(gx1, gx2, gy, 1f);
        }

        void hline(float gx1, float gx2, float gy, float width) throws IOException {
            c.setLineWidth(width * scale);
            c.moveTo(x(gx1), y(gy));
            c.lineTo(x(gx2), y(gy));
            c.stroke();
        }

        void vline(float gx, float gy1, float gy2) throws IOException {
            c.setLineWidth(scale);
            c.moveTo(x(gx), y(gy1));
            c.lineTo(x(gx), y(gy2));
            c.stroke();
        }

        void box(float gx1, float gy1, float gx2, float gy2, float width) throws IOException {
            c.setLineWidth(width * scale);
            c.addRect(x(gx1), y(gy2), (gx2 - gx1) * scale, (gy2 - gy1) * scale);
            c.stroke();
        }

        /** The row pitch that fits n rows between two baselines (never more than the design's pitch). */
        float pitch(float firstY, float lastY, float designPitch, int n) {
            if (n <= 1) return designPitch;
            return Math.min(designPitch, (lastY - firstY) / (n - 1));
        }

        /** Label on the left, amount on the right, one per row, squeezed to fit between firstY and lastY. */
        void rows(float labelX, float amountRight, float firstY, float lastY, float designPitch, List<PayslipPdf.Line> lines, float size)
            throws IOException {
            rows(labelX, amountRight, firstY, lastY, designPitch, lines, size, false);
        }

        void rows(float labelX, float amountRight, float firstY, float lastY, float designPitch, List<PayslipPdf.Line> lines, float size,
                  boolean upper) throws IOException {
            float p = pitch(firstY, lastY, designPitch, lines.size());
            float s = p < designPitch ? Math.max(9, size * p / designPitch) : size;
            for (int i = 0; i < lines.size(); i++) {
                PayslipPdf.Line l = lines.get(i);
                float y = firstY + i * p;
                String amount = money(l.amount());
                float amountWidth = width(regular, amount, s) + 12;
                fit(labelX, y, upper ? l.label().toUpperCase(Locale.ENGLISH) : l.label(), s, false, amountRight - labelX - amountWidth);
                right(amountRight, y, amount, s, false);
            }
        }
    }
}
