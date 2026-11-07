package com.hrms.payroll;

import com.hrms.files.FileStore;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payroll. Your own payslips and tax forms: payroll:view. Uploading, generating, publishing and read receipts:
 * payroll:manage. The pay period export: payroll:export. The dashboard: payroll:dashboard.
 */
@RestController
@RequestMapping("/api/v1/payroll")
class PayrollController {

    record Password(String password) {
    }

    record Published(int published) {
    }

    record TemplateChoice(String template) {
    }

    private final PayrollDocuments documents;
    private final PayrollExport export;
    private final PayrollDashboard dashboard;
    private final PermissionChecker perm;
    private final PayslipSettings templates;

    PayrollController(PayrollDocuments documents, PayrollExport export, PayrollDashboard dashboard, PermissionChecker perm,
                      PayslipSettings templates) {
        this.templates = templates;
        this.documents = documents;
        this.export = export;
        this.dashboard = dashboard;
        this.perm = perm;
    }

    // ---------- your own ----------

    @GetMapping("/me/documents")
    @PreAuthorize("@perm.has('payroll:view')")
    List<PayrollDocuments.Doc> mine(@AuthenticationPrincipal Jwt jwt) {
        return documents.mine(requireMe(jwt));
    }

    /** Your own published document, or any for HR. Opening your own counts as read. */
    @GetMapping("/documents/{id}")
    @PreAuthorize("@perm.has('payroll:view') or @perm.has('payroll:manage')")
    PayrollDocuments.Detail detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return documents.detail(id, me(jwt), perm.has("payroll:manage"));
    }

    @GetMapping("/documents/{id}/file")
    @PreAuthorize("@perm.has('payroll:view') or @perm.has('payroll:manage')")
    ResponseEntity<byte[]> file(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        FileStore.StoredFile f = documents.file(id, me(jwt), perm.has("payroll:manage"));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(f.meta().contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.meta().name()).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(f.data());
    }

    /** A copy of your own document that asks for the password you choose, to share safely. */
    @PostMapping("/documents/{id}/protected")
    @PreAuthorize("@perm.has('payroll:view')")
    ResponseEntity<byte[]> protectedCopy(@PathVariable UUID id, @RequestBody Password body, @AuthenticationPrincipal Jwt jwt) {
        byte[] pdf = documents.protectedCopy(id, requireMe(jwt), body == null ? null : body.password());
        String name = documents.fileName(id).replaceAll("\\.pdf$", "") + " (protected).pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
            .cacheControl(CacheControl.noStore())
            .body(pdf);
    }

    // ---------- HR ----------

    @GetMapping("/periods")
    @PreAuthorize("@perm.has('payroll:manage')")
    List<PayrollDocuments.PeriodSummary> periods(@RequestParam String kind, @RequestParam(required = false) Integer year) {
        return documents.periods(kind, year == null ? LocalDate.now().getYear() : year);
    }

    @GetMapping("/documents")
    @PreAuthorize("@perm.has('payroll:manage')")
    PayrollDocuments.PeriodView period(@RequestParam String kind, @RequestParam String period,
                                       @RequestParam(required = false) String formCode) {
        return documents.period(kind, period, formCode);
    }

    /** The usual tax form for each country, to preselect. */
    @GetMapping("/tax-forms")
    @PreAuthorize("@perm.has('payroll:manage')")
    Map<String, String> taxForms() {
        return PayrollDocuments.FORM_BY_COUNTRY;
    }

    @PostMapping("/documents/upload")
    @PreAuthorize("@perm.has('payroll:manage')")
    PayrollDocuments.UploadResult upload(@RequestBody PayrollDocuments.UploadInput body, @AuthenticationPrincipal Jwt jwt) {
        return documents.upload(user(jwt), body);
    }

    @PostMapping("/payslips/generate")
    @PreAuthorize("@perm.has('payroll:manage')")
    PayrollDocuments.UploadResult generate(@RequestBody PayrollDocuments.GenerateInput body, @AuthenticationPrincipal Jwt jwt) {
        return documents.generate(user(jwt), body);
    }

    /** Draws a month's generated payslips again in a template (same figures; published ones stay published). */
    @PostMapping("/payslips/redraw")
    @PreAuthorize("@perm.has('payroll:manage')")
    PayrollDocuments.UploadResult redraw(@RequestBody PayrollDocuments.RedrawInput body, @AuthenticationPrincipal Jwt jwt) {
        return documents.redraw(user(jwt), body);
    }

    @PostMapping("/documents/publish")
    @PreAuthorize("@perm.has('payroll:manage')")
    Published publish(@RequestBody PayrollDocuments.PublishInput body, @AuthenticationPrincipal Jwt jwt) {
        return new Published(documents.publish(user(jwt), body));
    }

    @PostMapping("/documents/{id}/unpublish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('payroll:manage')")
    void unpublish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        documents.unpublish(user(jwt), id);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('payroll:manage')")
    void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        documents.delete(user(jwt), id);
    }

    // ---------- payslip templates ----------

    /** The layouts to choose from and the company's default. */
    @GetMapping("/templates")
    @PreAuthorize("@perm.has('payroll:manage')")
    PayslipSettings.Choice templates() {
        return templates.choice();
    }

    @PostMapping("/templates/default")
    @PreAuthorize("@perm.has('payroll:manage')")
    PayslipSettings.Choice setDefaultTemplate(@RequestBody TemplateChoice body, @AuthenticationPrincipal Jwt jwt) {
        return templates.setDefault(user(jwt), body == null ? null : body.template());
    }

    /** A sample payslip in that layout, with made-up figures. */
    @GetMapping("/templates/{code}/sample")
    @PreAuthorize("@perm.has('payroll:manage')")
    ResponseEntity<byte[]> sample(@PathVariable String code) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("sample-" + code.toLowerCase() + ".pdf").build().toString())
            .cacheControl(CacheControl.noStore())
            .body(templates.sample(code));
    }

    // ---------- export ----------

    @GetMapping("/export")
    @PreAuthorize("@perm.has('payroll:export')")
    PayrollExport.Summary exportPreview(@RequestParam(required = false) String month) {
        return export.build(month(month));
    }

    @GetMapping(value = "/export/csv", produces = "text/csv")
    @PreAuthorize("@perm.has('payroll:export')")
    ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String month) {
        YearMonth m = month(month);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("payroll-" + m + ".csv").build().toString())
            .cacheControl(CacheControl.noStore())
            .body(PayrollExport.csv(export.build(m)).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- dashboard ----------

    @GetMapping("/dashboard")
    @PreAuthorize("@perm.has('payroll:dashboard')")
    PayrollDashboard.Dashboard dashboard(@RequestParam(required = false) Integer year) {
        int y = year == null ? LocalDate.now().getYear() : year;
        if (y < 2000 || y > 2100) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_YEAR", "Choose a year between 2000 and 2100.");
        return dashboard.build(y);
    }

    // ---------- helpers ----------

    private static YearMonth month(String value) {
        if (value == null || value.isBlank()) return YearMonth.now().minusMonths(1);
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH", "Give the month as YYYY-MM.");
        }
    }

    private static UUID me(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }

    private static UUID requireMe(Jwt jwt) {
        UUID me = me(jwt);
        if (me == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record.");
        return me;
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
