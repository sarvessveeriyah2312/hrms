package com.hrms.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Every payslip layout draws, shows the money that matters, and copes with long lists. */
class PayslipTemplatesTests {

    @Test
    void everyTemplateShowsNetPayAndTheEmployee() throws Exception {
        var slip = PayslipSettings.sampleSlip("Demo Company Sdn Bhd", null, "MYR");
        String outDir = System.getenv("PAYSLIP_SAMPLES");
        for (PayslipTemplates.Template t : PayslipTemplates.ALL) {
            byte[] pdf = PayslipTemplates.render(t.code(), slip);
            try (var doc = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(doc);
                assertThat(text).as(t.code()).contains("Jeffrey Toh", "3,486.61");
                if (!t.code().equals("SIMPLE") && !t.code().equals("MODERN")) assertThat(text).as(t.code()).contains("377.00");
                if (outDir != null) {
                    BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 110);
                    ImageIO.write(img, "png", new File(outDir, t.code().toLowerCase() + ".png"));
                }
            }
        }
    }

    @Test
    void longListsAreSqueezedOntoThePage() throws Exception {
        var sample = PayslipSettings.sampleSlip("Demo", null, "MYR");
        List<PayslipPdf.Line> many = new java.util.ArrayList<>(sample.lines());
        for (int i = 1; i <= 20; i++) many.add(new PayslipPdf.Line("DEDUCTION", null, "Loan instalment " + i, new BigDecimal("1.00"), null, null));
        var slip = new PayslipTemplates.Slip(sample.company(), null, sample.period(), sample.date(), "MYR", sample.employeeNo(), sample.name(),
            null, null, null, null, null, null, null, List.of(), null, many);
        for (PayslipTemplates.Template t : PayslipTemplates.ALL) {
            try (var doc = Loader.loadPDF(PayslipTemplates.render(t.code(), slip))) {
                assertThat(doc.getNumberOfPages()).isEqualTo(1);
                assertThat(new PDFTextStripper().getText(doc)).as(t.code()).contains("Loan instalment 20");
            }
        }
    }

    @Test
    void shortLabels() {
        assertThat(PayslipTemplates.shortLabel("Employer EPF (13%)")).isEqualTo("EPF");
        assertThat(PayslipTemplates.shortLabel("SOCSO 'YER")).isEqualTo("SOCSO");
    }
}
