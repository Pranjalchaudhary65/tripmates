package com.tripmate.identity.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.tripmate.identity.entity.Role;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Signs access tokens. HS256 with a server-held key: this application is
 * the only party that issues tokens and the only one that checks them, so
 * a public key that others could verify with would buy nothing, and a
 * private key would be one more secret to protect for no gain.
 */
@Component
public class AccessTokenIssuer {

    private final AccessTokenKeys keys;

    private final AccessTokenProperties properties;

    private final Clock clock;

    public AccessTokenIssuer(AccessTokenKeys keys, AccessTokenProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param sessionExpiresAt the family's absolute expiry; the token never
     *                         outlives the session it belongs to
     */
    public AccessToken issue(UUID userId, Role role, UUID sessionId, Instant sessionExpiresAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant ttlExpiry = now.plus(properties.ttl());
        Instant expiresAt = (ttlExpiry.isBefore(sessionExpiresAt) ? ttlExpiry : sessionExpiresAt)
                .truncatedTo(ChronoUnit.SECONDS);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(properties.audience())
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim(AccessTokenClaims.SESSION_ID, sessionId.toString())
                .claim(AccessTokenClaims.ROLE, role.name())
                .claim(AccessTokenClaims.AUTH_METHODS, List.of(AccessTokenClaims.PASSWORD_METHOD))
                .build();

        SigningKey key = keys.current();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(AccessTokenClaims.TYPE)
                .keyID(key.keyId())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new MACSigner(key.secret()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Access token could not be signed", e);
        }
        return new AccessToken(jwt.serialize(), now, expiresAt);
    }
}
