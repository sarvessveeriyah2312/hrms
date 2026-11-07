package com.hrms.comms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Letter placeholders, letter PDFs and the uuid[] text form notices use. */
class CommsTests {

    @Test
    void fillsPlaceholdersAndLeavesUnknownOnes() {
        String out = Letters.fill("Dear {{name}}, from {{ effectiveDate }} {{unknown}} $1", Map.of("name", "Wei Ling", "effectiveDate", "1 Jan"));
        assertThat(out).isEqualTo("Dear Wei Ling, from 1 Jan {{unknown}} $1");
    }

    @Test
    void listsOnlyTheFieldsHrTypesIn() {
        assertThat(Letters.fields("Offer as {{position}}", "Dear {{name}}, salary {{salary}} from {{startDate}}, {{salary}} again"))
            .containsExactly("salary", "startDate");
    }

    @Test
    void drawsALetterAcrossPages() throws Exception {
        String body = String.join("\n\n", java.util.Collections.nCopies(40, "This paragraph is long enough to wrap across more than one line of the letter body text."));
        byte[] pdf = LetterPdf.render(new LetterPdf.Letterhead("Demo Sdn Bhd", "Level 1, Jalan Ampang\n50450 Kuala Lumpur", "+60 3 1234 5678",
                "hr@demo.example", null, "202301000001", null, "Priya Raman", "HR Manager", "Demo Sdn Bhd · Private and confidential"),
            new LetterPdf.Content("7 November 2026", List.of("Wei Ling Tan", "Operations Executive", "Operations"), "Salary increment", body));
        try (var doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Demo Sdn Bhd", "SALARY INCREMENT", "Wei Ling Tan", "Yours sincerely", "Priya Raman", "Private and confidential");
        }
    }

    @Test
    void wrapsLongWords() throws Exception {
        var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        List<String> lines = LetterPdf.wrap(font, 11, "short " + "x".repeat(200), 200);
        assertThat(lines).hasSizeGreaterThan(2);
        for (String l : lines) assertThat(font.getStringWidth(l) / 1000 * 11).isLessThanOrEqualTo(200);
    }

    @Test
    void uuidArraysAsText() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(Notices.uuids(Notices.pgArray(List.of(a, b)))).containsExactly(a, b);
        assertThat(Notices.uuids("{}")).isEmpty();
        assertThat(Notices.pgArray(List.of())).isEqualTo("{}");
    }
}
