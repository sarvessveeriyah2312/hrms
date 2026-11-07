package com.hrms.payroll;

import com.hrms.shared.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;

/**
 * Payslip PDFs: drawing one from pay lines, and making a password-protected copy of any PDF for sharing.
 * Uses the standard PDF fonts, so characters they can't show (e.g. Chinese names) are replaced with "?".
 */
final class PayslipPdf {

    /**
     * One pay line. kind: EARNING, DEDUCTION or EMPLOYER. category (earnings only): BASIC, ALLOWANCE or OVERTIME.
     * quantity and rate are optional (overtime hours and hourly rate, or basic days and rate).
     */
    record Line(String kind, String category, String label, BigDecimal amount, BigDecimal quantity, BigDecimal rate) {
        Line(String kind, String label, BigDecimal amount) {
            this(kind, "EARNING".equals(kind) ? PayLines.categoryOf(label) : null, label, amount, null, null);
        }
    }

    record Header(String company, String registrationNo, String employeeName, String employeeNo, String department,
                  String jobTitle, YearMonth period, String currency) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final float MARGIN = 50;

    private PayslipPdf() {
    }

    static byte[] generate(Header h, List<Line> lines, LocalDate generatedOn) {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float width = page.getMediaBox().getWidth();
            float right = width - MARGIN;
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;
                text(c, bold, 16, MARGIN, y, h.company());
                if (h.registrationNo() != null) text(c, regular, 9, MARGIN, y - 14, "Registration no. " + h.registrationNo());
                textRight(c, bold, 14, right, y, "PAYSLIP");
                textRight(c, regular, 10, right, y - 14, h.period().format(MONTH));
                y -= 44;
                rule(c, MARGIN, right, y);
                y -= 18;
                y = pair(c, regular, bold, MARGIN, y, "Employee", h.employeeName() + " (" + h.employeeNo() + ")");
                if (h.department() != null) y = pair(c, regular, bold, MARGIN, y, "Department", h.department());
                if (h.jobTitle() != null) y = pair(c, regular, bold, MARGIN, y, "Position", h.jobTitle());
                y = pair(c, regular, bold, MARGIN, y, "Pay period", h.period().atDay(1).format(DAY) + " - " + h.period().atEndOfMonth().format(DAY));
                y -= 8;

                BigDecimal gross = total(lines, "EARNING");
                BigDecimal deductions = total(lines, "DEDUCTION");
                BigDecimal employer = total(lines, "EMPLOYER");
                y = table(c, regular, bold, MARGIN, right, y, "Earnings", lines, "EARNING", "Gross pay", gross, h.currency());
                y = table(c, regular, bold, MARGIN, right, y, "Deductions", lines, "DEDUCTION", "Total deductions", deductions, h.currency());

                y -= 6;
                c.setNonStrokingColor(0.93f, 0.95f, 0.98f);
                c.addRect(MARGIN, y - 10, right - MARGIN, 30);
                c.fill();
                c.setNonStrokingColor(0f, 0f, 0f);
                text(c, bold, 12, MARGIN + 10, y, "Net pay");
                textRight(c, bold, 12, right - 10, y, money(gross.subtract(deductions), h.currency()));
                y -= 44;

                if (employer.signum() != 0) {
                    y = table(c, regular, bold, MARGIN, right, y, "Employer contributions (not deducted from your pay)", lines, "EMPLOYER",
                        "Total employer contributions", employer, h.currency());
                }
                text(c, regular, 8, MARGIN, MARGIN, "Computer-generated on " + generatedOn.format(DAY) + ". No signature is required.");
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't draw the payslip", e);
        }
    }

    /** A copy of the PDF that asks for the password to open (AES-256); printing and copying stay allowed. */
    static byte[] protect(byte[] pdf, String password) {
        try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            AccessPermission permissions = new AccessPermission();
            permissions.setCanModify(false);
            byte[] owner = new byte[24];
            RANDOM.nextBytes(owner);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(HexFormat.of().formatHex(owner), password, permissions);
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);
            doc.save(out);
            return out.toByteArray();
        } catch (InvalidPasswordException e) {
            throw new ApiException(HttpStatus.CONFLICT, "PDF_ALREADY_PROTECTED", "This PDF already has a password, so it can't be protected again.");
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PDF_UNREADABLE", "This file isn't a PDF that can be protected.");
        }
    }

    /** Checks an uploaded file is a PDF that opens without a password (so employees can read it). */
    static void requireOpenable(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.getNumberOfPages() == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "PDF_EMPTY", "That PDF has no pages.");
        } catch (InvalidPasswordException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PDF_PROTECTED", "Upload PDFs without a password: employees can add one when they share it.");
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PDF_UNREADABLE", "That PDF couldn't be read.");
        }
    }

    static BigDecimal total(List<Line> lines, String kind) {
        return lines.stream().filter(l -> l.kind().equals(kind)).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String money(BigDecimal amount, String currency) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return (currency == null ? "" : currency + " ") + f.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    // ---------- drawing ----------

    private static float table(PDPageContentStream c, PDType1Font regular, PDType1Font bold, float left, float right, float y,
                               String title, List<Line> lines, String kind, String totalLabel, BigDecimal total, String currency)
        throws IOException {
        text(c, bold, 11, left, y, title);
        y -= 6;
        rule(c, left, right, y);
        y -= 15;
        boolean any = false;
        for (Line l : lines) {
            if (!l.kind().equals(kind)) continue;
            any = true;
            text(c, regular, 10, left, y, l.label());
            textRight(c, regular, 10, right, y, money(l.amount(), currency));
            y -= 15;
        }
        if (!any) {
            text(c, regular, 10, left, y, "None");
            y -= 15;
        }
        rule(c, left, right, y + 9);
        text(c, bold, 10, left, y - 4, totalLabel);
        textRight(c, bold, 10, right, y - 4, money(total, currency));
        return y - 30;
    }

    private static float pair(PDPageContentStream c, PDType1Font regular, PDType1Font bold, float x, float y, String label, String value)
        throws IOException {
        text(c, regular, 10, x, y, label);
        text(c, bold, 10, x + 90, y, value);
        return y - 15;
    }

    private static void rule(PDPageContentStream c, float left, float right, float y) throws IOException {
        c.setStrokingColor(0.8f, 0.82f, 0.86f);
        c.setLineWidth(0.6f);
        c.moveTo(left, y);
        c.lineTo(right, y);
        c.stroke();
    }

    private static void text(PDPageContentStream c, PDType1Font font, float size, float x, float y, String s) throws IOException {
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(safe(font, s));
        c.endText();
    }

    private static void textRight(PDPageContentStream c, PDType1Font font, float size, float right, float y, String s) throws IOException {
        String t = safe(font, s);
        float w = font.getStringWidth(t) / 1000 * size;
        text(c, font, size, right - w, y, t);
    }

    /** Replaces what the standard font can't encode, and cuts very long values. */
    static String safe(PDType1Font font, String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.length() > 90 ? out.substring(0, 87) + "..." : out.toString();
    }
}
