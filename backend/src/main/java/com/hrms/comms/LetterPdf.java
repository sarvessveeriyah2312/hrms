package com.hrms.comms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * A letter on the company letterhead (A4 portrait): logo and company details at the top, date and recipient, subject,
 * the body with paragraphs wrapped across pages, a signature block and the footer on every page. Uses the standard PDF
 * fonts, so characters they can't show are replaced with "?".
 */
final class LetterPdf {

    record Letterhead(String companyName, String address, String phone, String email, String website, String registrationNo,
                      byte[] logo, String signatoryName, String signatoryTitle, String footer) {
    }

    record Content(String date, List<String> recipient, String subject, String body) {
    }

    private static final float MARGIN = 60;
    private static final float BODY_SIZE = 11;
    private static final float LEADING = 15.5f;

    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private PDDocument doc;
    private PDPageContentStream c;
    private float y;
    private Letterhead head;

    private LetterPdf() {
    }

    static byte[] render(Letterhead head, Content content) {
        try {
            return new LetterPdf().draw(head, content);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't draw the letter", e);
        }
    }

    private byte[] draw(Letterhead h, Content content) throws IOException {
        this.head = h;
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            this.doc = d;
            newPage(true);
            text(regular, BODY_SIZE, MARGIN, content.date());
            y -= LEADING * 2;
            for (String line : content.recipient()) {
                if (line == null || line.isBlank()) continue;
                text(line == content.recipient().get(0) ? bold : regular, BODY_SIZE, MARGIN, line);
                y -= LEADING;
            }
            y -= LEADING;
            for (String line : wrap(bold, BODY_SIZE, content.subject().toUpperCase(), width())) {
                ensure(LEADING);
                text(bold, BODY_SIZE, MARGIN, line);
                y -= LEADING;
            }
            y -= LEADING * 0.6f;
            for (String paragraph : content.body().replace("\r", "").split("\n")) {
                if (paragraph.isBlank()) {
                    y -= LEADING * 0.7f;
                    continue;
                }
                for (String line : wrap(regular, BODY_SIZE, paragraph, width())) {
                    ensure(LEADING);
                    text(regular, BODY_SIZE, MARGIN, line);
                    y -= LEADING;
                }
            }
            ensure(LEADING * 7);
            y -= LEADING * 1.5f;
            text(regular, BODY_SIZE, MARGIN, "Yours sincerely,");
            y -= LEADING * 3.5f;
            line(MARGIN, MARGIN + 170, y + 10, 0.6f);
            if (h.signatoryName() != null) {
                text(bold, BODY_SIZE, MARGIN, h.signatoryName());
                y -= LEADING;
            }
            if (h.signatoryTitle() != null) text(regular, BODY_SIZE, MARGIN, h.signatoryTitle());
            c.close();
            d.save(out);
            return out.toByteArray();
        }
    }

    private float width() {
        return PDRectangle.A4.getWidth() - MARGIN * 2;
    }

    private void ensure(float needed) throws IOException {
        if (y - needed < MARGIN + 30) {
            c.close();
            newPage(false);
        }
    }

    private void newPage(boolean first) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        c = new PDPageContentStream(doc, page);
        float top = page.getMediaBox().getHeight() - 45;
        float right = page.getMediaBox().getWidth() - MARGIN;
        if (first) {
            float logoBottom = top;
            if (head.logo() != null) {
                try {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, head.logo(), "logo");
                    float h = 52;
                    float w = img.getWidth() * h / img.getHeight();
                    if (w > 170) {
                        w = 170;
                        h = img.getHeight() * w / img.getWidth();
                    }
                    c.drawImage(img, MARGIN, top - h + 8, w, h);
                    logoBottom = top - h;
                } catch (IOException | IllegalArgumentException e) {
                    // an unreadable logo is left out
                }
            }
            float ty = top;
            if (head.companyName() != null) {
                right(bold, 14, right, ty, head.companyName());
                ty -= 16;
            }
            List<String> details = new ArrayList<>();
            if (head.registrationNo() != null) details.add("(" + head.registrationNo() + ")");
            if (head.address() != null) for (String l : head.address().split("\n")) if (!l.isBlank()) details.add(l.strip());
            List<String> contact = new ArrayList<>();
            if (head.phone() != null) contact.add("Tel: " + head.phone());
            if (head.email() != null) contact.add(head.email());
            if (head.website() != null) contact.add(head.website());
            if (!contact.isEmpty()) details.add(String.join("  ·  ", contact));
            for (String l : details) {
                right(regular, 8.5f, right, ty, l);
                ty -= 11;
            }
            float ruleY = Math.min(ty, logoBottom) - 6;
            line(MARGIN, right, ruleY, 1f);
            y = ruleY - 30;
        } else {
            if (head.companyName() != null) right(regular, 8.5f, right, top, head.companyName());
            y = top - 30;
        }
        if (head.footer() != null && !head.footer().isBlank()) {
            String f = safe(regular, head.footer());
            float w = regular.getStringWidth(f) / 1000 * 8;
            c.beginText();
            c.setFont(regular, 8);
            c.newLineAtOffset((page.getMediaBox().getWidth() - w) / 2, 32);
            c.showText(f);
            c.endText();
        }
    }

    private void text(PDType1Font f, float size, float x, String s) throws IOException {
        c.beginText();
        c.setFont(f, size);
        c.newLineAtOffset(x, y);
        c.showText(safe(f, s));
        c.endText();
    }

    private void right(PDType1Font f, float size, float rightX, float atY, String s) throws IOException {
        String t = safe(f, s);
        float w = f.getStringWidth(t) / 1000 * size;
        c.beginText();
        c.setFont(f, size);
        c.newLineAtOffset(rightX - w, atY);
        c.showText(t);
        c.endText();
    }

    private void line(float x1, float x2, float atY, float width) throws IOException {
        c.setStrokingColor(0.7f, 0.72f, 0.76f);
        c.setLineWidth(width);
        c.moveTo(x1, atY);
        c.lineTo(x2, atY);
        c.stroke();
        c.setStrokingColor(0f, 0f, 0f);
    }

    /** Word-wraps a paragraph to a width; very long words are broken. */
    static List<String> wrap(PDType1Font font, float size, String text, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : safe(font, text).split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size <= width) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) lines.add(current.toString());
            current.setLength(0);
            String w = word;
            while (font.getStringWidth(w) / 1000 * size > width && w.length() > 1) {
                int cut = w.length() - 1;
                while (cut > 1 && font.getStringWidth(w.substring(0, cut)) / 1000 * size > width) cut--;
                lines.add(w.substring(0, cut));
                w = w.substring(cut);
            }
            current.append(w);
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    /** Replaces characters the standard font can't encode, and tabs, with something it can. */
    static String safe(PDType1Font font, String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        s.replace('\t', ' ').codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.toString();
    }
}
