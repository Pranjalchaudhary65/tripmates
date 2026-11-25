package com.tripmate.identity.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.UpiId;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UpiIdRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.ProblemType;
import com.tripmate.platform.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The UPI ID the caller wants to be paid at, self-declared and optional.
 * Nothing is checked against a bank: the person paying sees it in their own
 * UPI app with the account holder's name before sending anything, which is
 * the check that matters. Set, read and removed only on the caller's own
 * account; other modules get it only for someone the caller owes money to.
 */
@Service
public class UpiIdService {

    static final String BUCKET = "upi-id";

    /**
     * name@bank: letters, digits, dots, hyphens and underscores before the @,
     * a bank handle of letters and digits after it. Nothing that could break
     * out of a upi:// link survives this.
     */
    static final Pattern VPA = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,63}@[a-z][a-z0-9]{1,31}$");

    private final UserRepository users;

    private final UpiIdRepository upiIds;

    private final FieldEncryption encryption;

    private final RateLimiter rateLimiter;

    private final VerificationLimitProperties limits;

    private final Clock clock;

    public UpiIdService(UserRepository users, UpiIdRepository upiIds, FieldEncryption encryption,
                        RateLimiter rateLimiter, VerificationLimitProperties limits, Clock clock) {
        this.users = users;
        this.upiIds = upiIds;
        this.encryption = encryption;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<OwnUpiId> mine(SessionPrincipal caller) {
        User user = users.findById(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        return upiIds.findById(user.getId()).flatMap(upiId -> read(user, upiId)
                .map(vpa -> new OwnUpiId(vpa, upiId.getUpdatedAt())));
    }

    @Transactional
    public OwnUpiId set(SessionPrincipal caller, String vpa) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), limits.upiId());
        String normalised = normalise(vpa).orElseThrow(() -> new ApiProblemException(Problem.INVALID_UPI_ID));
        User user = lockActive(caller);
        byte[] ciphertext = encryption.encrypt(user, EncryptedField.UPI_ID, normalised.getBytes(StandardCharsets.UTF_8));
        Instant now = clock.instant();
        UpiId upiId = upiIds.findById(user.getId())
                .map(existing -> {
                    existing.replace(ciphertext, now);
                    return existing;
                })
                .orElseGet(() -> UpiId.create(user.getId(), ciphertext, now));
        upiIds.save(upiId);
        return new OwnUpiId(normalised, now);
    }

    @Transactional
    public void remove(SessionPrincipal caller) {
        rateLimiter.consume(BUCKET, caller.userId().toString(), limits.upiId());
        User user = lockActive(caller);
        upiIds.findById(user.getId()).ifPresent(upiIds::delete);
    }

    /**
     * Where to pay this user, for the module building a payment link for
     * someone who owes them. Empty when they set none, when their account is
     * no longer active, or when it can no longer be read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PayeeUpiId> forPayee(UUID userId) {
        return users.findById(userId).filter(User::isActive).flatMap(user -> upiIds.findById(userId)
                .flatMap(upiId -> read(user, upiId).map(vpa -> new PayeeUpiId(vpa, upiId.getUpdatedAt()))));
    }

    /** Lower-cased and trimmed, or empty when it is not a UPI ID at all. */
    static Optional<String> normalise(String vpa) {
        if (vpa == null) {
            return Optional.empty();
        }
        String candidate = vpa.strip().toLowerCase(Locale.ROOT);
        return VPA.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    private Optional<String> read(User user, UpiId upiId) {
        return encryption.tryDecrypt(user, EncryptedField.UPI_ID, null, upiId.getVpaCiphertext())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    private User lockActive(SessionPrincipal caller) {
        return users.findByIdForUpdate(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
    }

    public record OwnUpiId(String upiId, Instant updatedAt) {
    }

    /** @param updatedAt so a payer can be warned about an ID changed just before they pay */
    public record PayeeUpiId(String upiId, Instant updatedAt) {
    }

    enum Problem implements ProblemType {

        INVALID_UPI_ID("invalid_upi_id", "Enter a UPI ID like name@bank.");

        private final String code;

        private final String detail;

        Problem(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.BAD_REQUEST;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String detail() {
            return detail;
        }

        @Override
        public String field() {
            return "upiId";
        }
    }
}
