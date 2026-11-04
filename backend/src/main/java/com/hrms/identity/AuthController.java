package com.hrms.identity;

import com.hrms.shared.web.ApiException;
import com.hrms.shared.web.ApiExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in for the web app. The access token goes in the response body (the client keeps it in memory);
 * the refresh token is an HttpOnly, SameSite=Strict cookie scoped to /api/v1/auth.
 * Refresh and logout also require an {@code X-Requested-With} header, which a cross-site form can't send.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String COOKIE = "hrms_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    record LoginRequest(@NotBlank @Size(max = 254) String username, @NotBlank @Size(max = 200) String password,
                        @Size(max = 20) String companyCode) {
    }

    /** Same shape as GET /me. */
    record UserView(UUID userId, UUID employeeId, UUID tenantId, String companyCode, String companyName,
                    String name, String email, List<String> roles, List<String> permissions,
                    Map<String, String> scopes, List<String> modules) {
        static UserView of(AuthenticatedUser u) {
            Map<String, String> scopes = new java.util.LinkedHashMap<>();
            u.scopes().forEach((k, v) -> scopes.put(k, v.name()));
            return new UserView(u.userId(), u.employeeId(), u.tenantId(), u.companyCode(), u.companyName(),
                u.name(), u.email(), u.roles(), u.permissions(), scopes, u.modules());
        }
    }

    record SessionResponse(String accessToken, long expiresIn, UserView user) {
    }

    private final AuthService auth;
    private final TokenService tokens;
    private final boolean secureCookie;
    private final Duration cookieTtl;

    AuthController(AuthService auth, TokenService tokens,
                   @Value("${hrms.auth.cookie-secure:true}") boolean secureCookie,
                   @Value("${hrms.auth.refresh-token-days:30}") long refreshDays) {
        this.auth = auth;
        this.tokens = tokens;
        this.secureCookie = secureCookie;
        this.cookieTtl = Duration.ofDays(refreshDays);
    }

    @PostMapping("/login")
    ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest req) {
        return session(auth.login(req.username(), req.password(), req.companyCode()));
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(@CookieValue(name = COOKIE, required = false) String token,
                                            @RequestHeader(name = "X-Requested-With", required = false) String requestedWith) {
        requireXhr(requestedWith);
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NO_SESSION", "You're not signed in.");
        }
        try {
            return session(auth.refresh(token));
        } catch (ApiException e) {
            // Same Problem Details as the exception handler, plus clearing the dead cookie.
            ProblemDetail pd = ApiExceptionHandler.toProblem(e);
            return ResponseEntity.status(e.status())
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .body(pd);
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String token,
                                @RequestHeader(name = "X-Requested-With", required = false) String requestedWith) {
        requireXhr(requestedWith);
        if (token != null && !token.isBlank()) auth.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return tokens.jwks();
    }

    private ResponseEntity<SessionResponse> session(AuthService.Session s) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), cookieTtl).toString())
            .body(new SessionResponse(s.access().token(), s.access().expiresInSeconds(), UserView.of(s.user())));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Strict")
            .path(COOKIE_PATH)
            .maxAge(maxAge)
            .build();
    }

    private static void requireXhr(String requestedWith) {
        if (requestedWith == null || requestedWith.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CSRF_HEADER_MISSING", "Send the X-Requested-With header.");
        }
    }
}
