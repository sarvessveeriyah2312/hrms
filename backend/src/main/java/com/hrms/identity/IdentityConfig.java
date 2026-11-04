package com.hrms.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
class IdentityConfig {

    /** Verifies access tokens with our own public key (replaces the jwk-set-uri lookup). Checks expiry and issuer. */
    @Bean
    JwtDecoder jwtDecoder(TokenService tokens) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(tokens.publicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(tokens.issuer()));
        return decoder;
    }

    /** Argon2id, as the schema expects for app_user.password_hash. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
