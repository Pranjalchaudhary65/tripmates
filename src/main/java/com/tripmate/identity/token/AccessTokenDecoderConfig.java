package com.tripmate.identity.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.tripmate.identity.entity.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.security.Key;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * How an incoming access token is checked. Each rule below rejects a token
 * someone could otherwise forge or replay:
 *
 * <ul>
 *   <li>{@code alg} must be exactly HS256. The key selector returns no key
 *       for any other algorithm, so {@code none}, HS512 with the right key,
 *       or an RS256 token that tries to use our HMAC key as an RSA public
 *       key (algorithm confusion) all fail signature verification.
 *       {@link NimbusJwtDecoder} also refuses unsigned tokens outright.</li>
 *   <li>{@code kid} must name a configured key; a token without one is
 *       refused rather than tried against every key.</li>
 *   <li>{@code typ} must be {@code at+jwt}.</li>
 *   <li>{@code iss}, {@code aud}, {@code exp}, {@code iat}, {@code sub},
 *       {@code sid}, {@code jti}, {@code role} and {@code amr} must all be
 *       present and well-formed, and {@code exp} not passed by the
 *       application clock.</li>
 * </ul>
 *
 * <p>Whether the session behind {@code sid} is still alive is checked
 * separately, per request, by {@link SessionLivenessFilter}.
 */
@Configuration(proxyBeanMethods = false)
public class AccessTokenDecoderConfig {

    @Bean
    JwtDecoder accessTokenDecoder(AccessTokenKeys keys, AccessTokenProperties properties, Clock clock) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(AccessTokenClaims.TYPE));
        processor.setJWSKeySelector(keySelector(keys));
        // Claims are checked by Spring's validators below, against the
        // application clock, instead of Nimbus's checks against the system clock.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    private static JWSKeySelector<SecurityContext> keySelector(AccessTokenKeys keys) {
        return (header, context) -> {
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())) {
                return List.of();
            }
            return keys.forVerification(header.getKeyID())
                    .<List<Key>>map(key -> List.of(key.secret()))
                    .orElse(List.of());
        };
    }

    private static OAuth2TokenValidator<Jwt> validator(AccessTokenProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtClaimValidator<>(JwtClaimNames.EXP, exp -> exp != null),
                new JwtClaimValidator<>(JwtClaimNames.IAT, iat -> iat != null),
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience())),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, AccessTokenDecoderConfig::isUuid),
                new JwtClaimValidator<String>(JwtClaimNames.JTI, jti -> jti != null && !jti.isBlank()),
                new JwtClaimValidator<String>(AccessTokenClaims.SESSION_ID, AccessTokenDecoderConfig::isUuid),
                new JwtClaimValidator<String>(AccessTokenClaims.ROLE, AccessTokenDecoderConfig::isRole),
                new JwtClaimValidator<Object>(AccessTokenClaims.AUTH_METHODS, AccessTokenDecoderConfig::isAuthMethods)));
    }

    /**
     * The authenticated principal for a valid token: its subject as the
     * name, its role as a granted authority ({@code ROLE_USER} or
     * {@code ROLE_ADMIN}), and one {@code AMR_*} authority per amr value.
     */
    public static Converter<Jwt, AbstractAuthenticationToken> authenticationConverter() {
        return jwt -> {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString(AccessTokenClaims.ROLE)));
            for (String method : jwt.getClaimAsStringList(AccessTokenClaims.AUTH_METHODS)) {
                authorities.add(new SimpleGrantedAuthority(AccessTokenClaims.AUTH_METHOD_AUTHORITY_PREFIX + method));
            }
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    /**
     * A list that holds {@code pwd} and nothing but known values, each once.
     * Signed tokens can only come from this service, so this mostly guards
     * against a future issuer bug quietly granting {@code otp}.
     */
    private static boolean isAuthMethods(Object value) {
        if (!(value instanceof Collection<?> methods) || !methods.contains(AccessTokenClaims.PASSWORD_METHOD)) {
            return false;
        }
        boolean allKnown = methods.stream().allMatch(
                method -> AccessTokenClaims.PASSWORD_METHOD.equals(method) || AccessTokenClaims.OTP_METHOD.equals(method));
        return allKnown && methods.size() == Set.copyOf(methods).size();
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isRole(String value) {
        return value != null && Arrays.stream(Role.values()).anyMatch(role -> role.name().equals(value));
    }
}
