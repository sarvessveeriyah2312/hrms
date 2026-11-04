package com.hrms.files;

import com.hrms.shared.web.ApiException;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload only. There's no general download: each module serves its own documents after its own access
 * check (e.g. GET /leave/requests/{id}/attachment), and only the uploader can attach a file.
 */
@RestController
@RequestMapping("/api/v1/files")
class FilesController {

    private final DatabaseFileStore store;

    FilesController(DatabaseFileStore store) {
        this.store = store;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    FileStore.FileMeta upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        try {
            return store.store(UUID.fromString(jwt.getSubject()), file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_UNREADABLE", "That file couldn't be read. Try again.");
        }
    }
}
