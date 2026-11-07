package com.hrms.comms;

import com.hrms.shared.security.PermissionChecker;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Letters. Your own: everyone with an employee record. Letterhead, templates and issuing: comms:letters. */
@RestController
@RequestMapping("/api/v1/letters")
class LettersController {

    private final Letters letters;
    private final PermissionChecker perm;

    LettersController(Letters letters, PermissionChecker perm) {
        this.letters = letters;
        this.perm = perm;
    }

    @GetMapping("/mine")
    List<Letters.Letter> mine(@AuthenticationPrincipal Jwt jwt) {
        return letters.mine(CommsWeb.requireMe(jwt));
    }

    @GetMapping("/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return CommsWeb.inline(letters.file(CommsWeb.me(jwt), id, perm.has("comms:letters")));
    }

    @GetMapping
    @PreAuthorize("@perm.has('comms:letters')")
    List<Letters.Letter> all() {
        return letters.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('comms:letters')")
    Letters.Letter issue(@RequestBody Letters.IssueInput body, @AuthenticationPrincipal Jwt jwt) {
        return letters.issue(CommsWeb.user(jwt), body);
    }

    /** The letter as it would be issued (not saved). */
    @PostMapping("/preview")
    @PreAuthorize("@perm.has('comms:letters')")
    ResponseEntity<byte[]> preview(@RequestBody Letters.IssueInput body) {
        return CommsWeb.pdf(letters.preview(body), "preview.pdf", MediaType.APPLICATION_PDF);
    }

    @PostMapping("/{id}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('comms:letters')")
    void withdraw(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        letters.withdraw(CommsWeb.user(jwt), id);
    }

    @GetMapping("/letterhead")
    @PreAuthorize("@perm.has('comms:letters')")
    Letters.LetterheadView letterhead() {
        return letters.letterhead();
    }

    @PutMapping("/letterhead")
    @PreAuthorize("@perm.has('comms:letters')")
    Letters.LetterheadView saveLetterhead(@RequestBody Letters.LetterheadInput body, @AuthenticationPrincipal Jwt jwt) {
        return letters.saveLetterhead(CommsWeb.user(jwt), body);
    }

    @GetMapping("/templates")
    @PreAuthorize("@perm.has('comms:letters')")
    List<Letters.Template> templates() {
        return letters.templates();
    }

    /** Placeholders filled in from the person's record (anything else is typed in when issuing). */
    @GetMapping("/templates/fields")
    @PreAuthorize("@perm.has('comms:letters')")
    List<String> autoFields() {
        return Letters.AUTO_FIELDS;
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('comms:letters')")
    Letters.Template createTemplate(@RequestBody Letters.TemplateInput body, @AuthenticationPrincipal Jwt jwt) {
        return letters.createTemplate(CommsWeb.user(jwt), body);
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("@perm.has('comms:letters')")
    Letters.Template updateTemplate(@PathVariable UUID id, @RequestBody Letters.TemplateInput body, @AuthenticationPrincipal Jwt jwt) {
        return letters.updateTemplate(CommsWeb.user(jwt), id, body);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('comms:letters')")
    void deleteTemplate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        letters.deleteTemplate(CommsWeb.user(jwt), id);
    }
}
