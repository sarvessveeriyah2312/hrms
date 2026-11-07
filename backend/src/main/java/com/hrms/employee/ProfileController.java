package com.hrms.employee;

import com.hrms.files.FileStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee profiles. {id} is an employee id or "me". Who may see and change what is decided by {@link EmployeeAccess}:
 * yourself; employee:view for job, contact, emergency contacts and education; employee:manage for everything, plus
 * changing it directly. Your own changes go to HR as change requests (/me/changes), approved through Approvals by
 * people with employee:approve.
 */
@RestController
@RequestMapping("/api/v1/employees")
class ProfileController {

    record Photo(UUID fileId) {
    }

    private final ProfileService profiles;
    private final ProfileChangeService changes;
    private final ProfileSections sections;
    private final EmployeeRecords records;
    private final EmployeeAccess access;

    ProfileController(ProfileService profiles, ProfileChangeService changes, ProfileSections sections, EmployeeRecords records,
                      EmployeeAccess access) {
        this.profiles = profiles;
        this.changes = changes;
        this.sections = sections;
        this.records = records;
        this.access = access;
    }

    /** The sections and their fields, to build forms from. */
    @GetMapping("/profile-sections")
    List<ProfileSections.Section> sections() {
        return sections.specs();
    }

    @GetMapping("/{id}/profile")
    ProfileService.Profile profile(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID me = EmployeeAccess.me(jwt);
        UUID target = EmployeeAccess.target(id, jwt);
        List<ProfileChangeService.Change> open = access.canSeePrivate(me, target) ? changes.open(target) : List.of();
        return profiles.profile(me, target, open);
    }

    /** HR changes a section directly. Body: the section's fields ({"items": [...]} for list sections). */
    @PutMapping("/{id}/profile/{section}")
    ProfileService.Profile updateSection(@PathVariable String id, @PathVariable String section, @RequestBody Object body,
                                         @AuthenticationPrincipal Jwt jwt) {
        UUID target = EmployeeAccess.target(id, jwt);
        profiles.updateSection(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), target, section, body);
        return profile(target.toString(), jwt);
    }

    @PutMapping("/{id}/job")
    ProfileService.Profile updateJob(@PathVariable String id, @RequestBody ProfileService.Job body, @AuthenticationPrincipal Jwt jwt) {
        UUID target = EmployeeAccess.target(id, jwt);
        profiles.updateJob(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), target, body);
        return profile(target.toString(), jwt);
    }

    @PutMapping("/{id}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPhoto(@PathVariable String id, @RequestBody Photo body, @AuthenticationPrincipal Jwt jwt) {
        profiles.setPhoto(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt), body == null ? null : body.fileId());
    }

    @DeleteMapping("/{id}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePhoto(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        profiles.setPhoto(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt), null);
    }

    @GetMapping("/{id}/photo")
    ResponseEntity<byte[]> photo(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return inline(profiles.photo(EmployeeAccess.me(jwt), EmployeeAccess.target(id, jwt)), CacheControl.noCache());
    }

    // ---------- your own change requests ----------

    @GetMapping("/me/changes")
    List<ProfileChangeService.Change> myChanges(@AuthenticationPrincipal Jwt jwt) {
        return changes.mine(EmployeeAccess.requireMe(jwt));
    }

    @PostMapping("/me/changes")
    @ResponseStatus(HttpStatus.CREATED)
    ProfileChangeService.Change askForChange(@RequestBody ProfileChangeService.Input body, @AuthenticationPrincipal Jwt jwt) {
        return changes.submit(EmployeeAccess.requireMe(jwt), body);
    }

    @PutMapping("/changes/{changeId}")
    ProfileChangeService.Change resubmit(@PathVariable UUID changeId, @RequestBody ProfileChangeService.Input body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return changes.resubmit(EmployeeAccess.requireMe(jwt), changeId, body);
    }

    @PostMapping("/changes/{changeId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID changeId, @AuthenticationPrincipal Jwt jwt) {
        changes.cancel(EmployeeAccess.requireMe(jwt), changeId);
    }

    /** The request with current and new values: its owner, HR who manage them, or approvers whose scope covers them. */
    @GetMapping("/changes/{changeId}")
    ProfileChangeService.Detail change(@PathVariable UUID changeId, @AuthenticationPrincipal Jwt jwt) {
        ProfileChangeService.Detail d = changes.detail(changeId);
        UUID me = EmployeeAccess.me(jwt);
        UUID owner = d.change().employeeId();
        if (!owner.equals(me) && !access.canManage(me, owner) && !access.covers(me, "employee:approve", owner)) {
            throw ProfileChangeService.notFound();
        }
        return d;
    }

    // ---------- documents ----------

    @GetMapping("/{id}/documents")
    List<EmployeeRecords.Document> documents(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return records.documents(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt));
    }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    EmployeeRecords.Document addDocument(@PathVariable String id, @RequestBody EmployeeRecords.DocumentInput body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return records.addDocument(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt), body);
    }

    @GetMapping("/{id}/documents/{documentId}/file")
    ResponseEntity<byte[]> documentFile(@PathVariable String id, @PathVariable UUID documentId, @AuthenticationPrincipal Jwt jwt) {
        return inline(records.documentFile(EmployeeAccess.me(jwt), EmployeeAccess.target(id, jwt), documentId), CacheControl.noStore());
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDocument(@PathVariable String id, @PathVariable UUID documentId, @AuthenticationPrincipal Jwt jwt) {
        records.deleteDocument(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt), documentId);
    }

    // ---------- assets ----------

    @GetMapping("/{id}/assets")
    List<EmployeeRecords.Asset> assets(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return records.assets(EmployeeAccess.me(jwt), EmployeeAccess.target(id, jwt));
    }

    @PostMapping("/{id}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    EmployeeRecords.Asset addAsset(@PathVariable String id, @RequestBody EmployeeRecords.AssetInput body, @AuthenticationPrincipal Jwt jwt) {
        return records.addAsset(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt), body);
    }

    @PutMapping("/{id}/assets/{assetId}")
    EmployeeRecords.Asset updateAsset(@PathVariable String id, @PathVariable UUID assetId, @RequestBody EmployeeRecords.AssetInput body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return records.updateAsset(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt), assetId, body);
    }

    @DeleteMapping("/{id}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAsset(@PathVariable String id, @PathVariable UUID assetId, @AuthenticationPrincipal Jwt jwt) {
        records.deleteAsset(EmployeeAccess.me(jwt), EmployeeAccess.user(jwt), EmployeeAccess.target(id, jwt), assetId);
    }

    private static ResponseEntity<byte[]> inline(FileStore.StoredFile file, CacheControl cache) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.meta().contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.meta().name()).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(cache.cachePrivate())
            .body(file.data());
    }
}
