package com.hrms.comms;

import com.hrms.files.FileStore;
import com.hrms.shared.web.ApiException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/** Small helpers shared by the communication controllers. */
final class CommsWeb {

    private CommsWeb() {
    }

    static UUID me(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }

    static UUID requireMe(Jwt jwt) {
        UUID me = me(jwt);
        if (me == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record.");
        return me;
    }

    static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    static ResponseEntity<byte[]> inline(FileStore.StoredFile f) {
        return pdf(f.data(), f.meta().name(), MediaType.parseMediaType(f.meta().contentType()));
    }

    static ResponseEntity<byte[]> pdf(byte[] data, String name, MediaType type) {
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name, java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(data);
    }
}
