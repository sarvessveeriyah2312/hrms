package com.hrms.comms;

import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notices. Reading the ones meant for you: everyone. Announcements and memos are managed with comms:post, policies with
 * comms:policies.
 */
@RestController
@RequestMapping("/api/v1/notices")
class NoticesController {

    record Group(UUID id, String name) {
    }

    record Audiences(List<Group> departments, List<Group> entities) {
    }

    private final Notices notices;
    private final PermissionChecker perm;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    NoticesController(Notices notices, PermissionChecker perm, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.notices = notices;
        this.perm = perm;
        this.jdbc = jdbc;
    }

    /** The departments and companies a notice can be sent to (for people who manage notices). */
    @GetMapping("/audiences")
    Audiences audiences() {
        if (managed().isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_MANAGE", "You can't manage notices.");
        return new Audiences(jdbc.sql("select id, name from department order by name").query(Group.class).list(),
            jdbc.sql("select id, name from legal_entity order by name").query(Group.class).list());
    }

    private Set<String> managed() {
        Set<String> out = new LinkedHashSet<>();
        if (perm.has("comms:post")) out.addAll(List.of("ANNOUNCEMENT", "MEMO"));
        if (perm.has("comms:policies")) out.add("POLICY");
        return out;
    }

    private void requireManage(String kind) {
        if (!managed().contains(kind)) throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_MANAGE", "You can't manage these notices.");
    }

    /** manage=true: everything of the kinds you manage (drafts and archived too), with read counts. */
    @GetMapping
    List<Notices.Notice> list(@RequestParam(required = false) String kind, @RequestParam(defaultValue = "false") boolean manage,
                              @AuthenticationPrincipal Jwt jwt) {
        if (kind != null && !Notices.KINDS.contains(kind)) throw Notices.bad("BAD_KIND", "Unknown kind.");
        if (manage) {
            Set<String> kinds = managed();
            if (kind != null) kinds.retainAll(Set.of(kind));
            return notices.managed(CommsWeb.me(jwt), kinds);
        }
        return notices.mine(CommsWeb.me(jwt), kind);
    }

    @GetMapping("/counts")
    Notices.Counts counts(@AuthenticationPrincipal Jwt jwt) {
        return notices.counts(CommsWeb.me(jwt));
    }

    @GetMapping("/{id}")
    Notices.Notice open(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return notices.open(CommsWeb.me(jwt), id, managed());
    }

    @PostMapping("/{id}/acknowledge")
    Notices.Notice acknowledge(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return notices.acknowledge(CommsWeb.requireMe(jwt), id);
    }

    @GetMapping("/{id}/attachments/{fileId}")
    ResponseEntity<byte[]> attachment(@PathVariable UUID id, @PathVariable UUID fileId, @AuthenticationPrincipal Jwt jwt) {
        return CommsWeb.inline(notices.attachment(CommsWeb.me(jwt), id, fileId, managed()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Notices.Notice create(@RequestBody Notices.Input body, @AuthenticationPrincipal Jwt jwt) {
        requireManage(body == null || body.kind() == null ? "ANNOUNCEMENT" : body.kind());
        return notices.create(CommsWeb.me(jwt), CommsWeb.user(jwt), body);
    }

    @PutMapping("/{id}")
    Notices.Notice update(@PathVariable UUID id, @RequestBody Notices.Input body, @AuthenticationPrincipal Jwt jwt) {
        requireManage(notices.kindOf(id));
        return notices.update(CommsWeb.me(jwt), CommsWeb.user(jwt), id, body);
    }

    @PostMapping("/{id}/publish")
    Notices.Notice publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requireManage(notices.kindOf(id));
        return notices.publish(CommsWeb.me(jwt), CommsWeb.user(jwt), id);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requireManage(notices.kindOf(id));
        notices.archive(CommsWeb.user(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requireManage(notices.kindOf(id));
        notices.delete(CommsWeb.user(jwt), id);
    }

    /** Everyone in the audience, and who has read (and acknowledged) the current version. */
    @GetMapping("/{id}/receipts")
    Notices.Receipts receipts(@PathVariable UUID id) {
        requireManage(notices.kindOf(id));
        return notices.receipts(id);
    }
}
