package com.hrms.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Signs short-lived RS256 access tokens. The key pair is generated at startup, so a restart invalidates
 * access tokens; clients recover with the refresh cookie, which lives in the database.
 * TODO(identity): load the key from a secret manager (and publish old + new keys) before running more than one instance.
 */
@Component
class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    record Issued(String token, long expiresInSeconds) {
    }

    private final RSAKey key;
    private final JwtEncoder encoder;
    private final Duration ttl;
    private final String issuer;

    TokenService(@Value("${hrms.auth.access-token-minutes:15}") long ttlMinutes,
                 @Value("${hrms.auth.issuer:hrms-api}") String issuer) throws JOSEException {
        this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.issuer = issuer;
        log.info("Generated access-token signing key {} (in memory; tokens reset on restart)", key.getKeyID());
    }

    String issuer() {
        return issuer;
    }

    RSAPublicKey publicKey() {
        try {
            return key.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("Signing key has no RSA public key", e);
        }
    }

    /** Public half only, for other services and the mobile app to verify tokens. */
    Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    Issued issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(user.userId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim("tenant_id", user.tenantId().toString())
            .claim("company_code", user.companyCode())
            .claim("company_name", user.companyName())
            .claim("roles", user.roles())
            .claim("perms", user.permissions())
            .claim("scopes", scopeClaim(user))
            .claim("modules", user.modules());
        if (user.employeeId() != null) claims.claim("employee_id", user.employeeId().toString());
        if (user.name() != null) claims.claim("name", user.name());
        if (user.email() != null) claims.claim("email", user.email());

        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new Issued(token, ttl.toSeconds());
    }

    private static Map<String, String> scopeClaim(AuthenticatedUser user) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        user.scopes().forEach((perm, scope) -> out.put(perm, scope.name()));
        return out;
    }
}
