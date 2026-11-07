package com.hrms.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Pay lines, matching files to people, and payslip PDFs. */
class PayrollTests {

    private static void refused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void readsPayLinesByPerson() {
        var out = PayLines.parse("""
            ﻿Employee No,Type,Item,Amount
            E0001,EARNING,Basic salary,"5,200.00"
            E0001,deduction,EPF (11%),572
            e0001,EMPLOYER,EPF (13%),676.00
            E0002,E,Basic,3000
            """);
        assertThat(out).containsOnlyKeys("E0001", "E0002");
        assertThat(out.get("E0001")).extracting(PayslipPdf.Line::kind).containsExactly("EARNING", "DEDUCTION", "EMPLOYER");
        assertThat(out.get("E0001").get(0).amount()).isEqualByComparingTo("5200.00");
    }

    @Test
    void refusesBadPayLines() {
        refused(() -> PayLines.parse("name,amount\nA,1\n"), "CSV_HEADER");
        refused(() -> PayLines.parse("employee_no,type,item,amount\nE1,BONUS,X,1\n"), "CSV_ERRORS");
        refused(() -> PayLines.parse("employee_no,type,item,amount\nE1,EARNING,X,1.234\n"), "CSV_ERRORS");
        refused(() -> PayLines.parse("employee_no,type,item,amount\nE1,DEDUCTION,Tax,10\n"), "CSV_ERRORS");
        refused(() -> PayLines.parse("employee_no,type,item,amount\nE1,EARNING,Pay,10\nE1,DEDUCTION,Tax,11\n"), "CSV_ERRORS");
    }

    @Test
    void matchesFilesByEmployeeNumber() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var people = List.of(new PayrollDocuments.Person(a, "E001", "A", null), new PayrollDocuments.Person(b, "E0012", "B", null));
        assertThat(PayrollDocuments.match("payslip_E0012_2026-10.pdf", people)).isEqualTo(b);
        assertThat(PayrollDocuments.match("e001.pdf", people)).isEqualTo(a);
        refused(() -> PayrollDocuments.match("E00123.pdf", people), "NO_MATCH");
        refused(() -> PayrollDocuments.match("october.pdf", people), "NO_MATCH");
    }

    @Test
    void periods() {
        assertThat(PayrollDocuments.period("PAYSLIP", "2026-10")).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(PayrollDocuments.period("TAX_FORM", "2025")).isEqualTo(LocalDate.of(2025, 1, 1));
        refused(() -> PayrollDocuments.period("PAYSLIP", "Oct 2026"), "BAD_PERIOD");
        refused(() -> PayrollDocuments.form("TAX_FORM", "W2"), "BAD_FORM");
    }

    @Test
    void generatesAPayslipAndProtectsIt() throws Exception {
        var lines = List.of(new PayslipPdf.Line("EARNING", "Basic salary", new BigDecimal("5200")),
            new PayslipPdf.Line("DEDUCTION", "EPF", new BigDecimal("572")),
            new PayslipPdf.Line("EMPLOYER", "EPF employer", new BigDecimal("676")));
        byte[] pdf = PayslipPdf.generate(new PayslipPdf.Header("Demo Sdn Bhd", "202301000001", "陈伟玲 Wei Ling", "E0004", "Engineering",
            "Developer", YearMonth.of(2026, 10), "MYR"), lines, LocalDate.of(2026, 11, 1));
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("PAYSLIP", "October 2026", "Wei Ling", "MYR 4,628.00", "MYR 676.00");
        }
        PayslipPdf.requireOpenable(pdf);

        byte[] locked = PayslipPdf.protect(pdf, "secret-123");
        assertThatThrownBy(() -> Loader.loadPDF(locked).close()).isInstanceOf(InvalidPasswordException.class);
        try (var doc = Loader.loadPDF(locked, "secret-123")) {
            assertThat(doc.isEncrypted()).isTrue();
        }
        refused(() -> PayslipPdf.requireOpenable(locked), "PDF_PROTECTED");
    }
}
