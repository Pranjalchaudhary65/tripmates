package com.tripmate.identity.service;

import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.audit.AuditEvent;
import com.tripmate.platform.audit.AuditLog;
import com.tripmate.platform.error.ApiProblemException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Consent records (DESIGN.md §9). Each grant or withdrawal is a new row and
 * none is ever changed, so what someone agreed to and when is always
 * answerable. The terms and privacy policy are agreed to by signing up and
 * can only be withdrawn by deleting the account; location sharing and
 * marketing are the user's to turn on and off.
 */
@Service
@EnableConfigurationProperties(ConsentService.PolicyVersions.class)
public class ConsentService {

    private final NamedParameterJdbcTemplate jdbc;

    private final AccountService accounts;

    private final AuditLog audit;

    private final PolicyVersions versions;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public ConsentService(NamedParameterJdbcTemplate jdbc, AccountService accounts, AuditLog audit,
                          PolicyVersions versions, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
        this.versions = versions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** The terms and privacy policy shown at sign-up, accepted by signing up. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSignUp(UUID userId, Instant now) {
        insert(userId, ConsentType.TOS, versions.of(ConsentType.TOS), true, now);
        insert(userId, ConsentType.PRIVACY_POLICY, versions.of(ConsentType.PRIVACY_POLICY), true, now);
    }

    public List<Consent> mine(SessionPrincipal caller) {
        return Objects.requireNonNull(transactions.execute(status -> {
            accounts.lockActive(caller);
            return current(caller.userId());
        }));
    }

    public List<Consent> set(SessionPrincipal caller, ConsentType type, boolean granted) {
        if (!type.optional()) {
            throw new ApiProblemException(IdentityProblem.CONSENT_NOT_OPTIONAL);
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            accounts.lockActive(caller);
            UUID me = caller.userId();
            boolean already = current(me).stream()
                    .anyMatch(consent -> consent.type() == type && consent.granted() == granted);
            if (!already) {
                Instant now = clock.instant();
                insert(me, type, versions.of(type), granted, now);
                audit.record(me, AuditEvent.CONSENT_CHANGED, "USER", me,
                        Map.of("type", type.name(), "granted", granted, "version", versions.of(type)));
            }
            return current(me);
        }));
    }

    /** The latest state of every consent type; never-recorded optional ones read as not granted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Consent> current(UUID userId) {
        List<Consent> found = jdbc.query("""
                SELECT DISTINCT ON (consent_type) consent_type, policy_version, granted, created_at
                FROM consent_records WHERE user_id = :user
                ORDER BY consent_type, created_at DESC, id DESC""", new MapSqlParameterSource("user", userId),
                (rs, row) -> new Consent(ConsentType.valueOf(rs.getString("consent_type")),
                        rs.getString("policy_version"), rs.getBoolean("granted"),
                        rs.getTimestamp("created_at").toInstant()));
        List<Consent> all = new ArrayList<>();
        for (ConsentType type : ConsentType.values()) {
            all.add(found.stream().filter(consent -> consent.type() == type).findFirst()
                    .orElse(new Consent(type, versions.of(type), false, null)));
        }
        return List.copyOf(all);
    }

    private void insert(UUID userId, ConsentType type, String version, boolean granted, Instant now) {
        jdbc.update("""
                INSERT INTO consent_records (user_id, consent_type, policy_version, granted, created_at)
                VALUES (:user, :type, :version, :granted, :now)""", new MapSqlParameterSource("user", userId)
                .addValue("type", type.name()).addValue("version", version).addValue("granted", granted)
                .addValue("now", Timestamp.from(now)));
    }

    /** Values match ck_consent_type. */
    public enum ConsentType {
        TOS(false), PRIVACY_POLICY(false), LOCATION_SHARING(true), MARKETING(true);

        private final boolean optional;

        ConsentType(boolean optional) {
            this.optional = optional;
        }

        public boolean optional() {
            return optional;
        }
    }

    /** @param recordedAt null for an optional consent never given */
    public record Consent(ConsentType type, String policyVersion, boolean granted, Instant recordedAt) {
    }

    /** The current version of each policy, as published; a new version is a new consent row when accepted. */
    @ConfigurationProperties("tripmate.consent")
    public record PolicyVersions(String tos, String privacyPolicy, String locationSharing, String marketing) {

        public PolicyVersions {
            for (String version : new String[] {tos, privacyPolicy, locationSharing, marketing}) {
                if (version == null || !version.matches("[0-9A-Za-z.-]{1,20}")) {
                    throw new IllegalArgumentException("tripmate.consent.* must each be a version of up to 20 "
                            + "letters, digits, dots or dashes");
                }
            }
        }

        String of(ConsentType type) {
            return switch (type) {
                case TOS -> tos;
                case PRIVACY_POLICY -> privacyPolicy;
                case LOCATION_SHARING -> locationSharing;
                case MARKETING -> marketing;
            };
        }
    }
}
