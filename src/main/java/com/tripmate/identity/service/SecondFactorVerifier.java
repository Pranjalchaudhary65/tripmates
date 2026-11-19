package com.tripmate.identity.service;

import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.TotpBackupCode;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.TotpBackupCodeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Checks a TOTP or backup code against an account, and spends it.
 *
 * <p>Every method runs inside the caller's transaction, which must hold the
 * user row lock: that lock is what makes "check the step is new, then record
 * it" and "check the backup code is unused, then mark it used" atomic. Two
 * requests racing with the same code queue on the lock, and the second sees
 * the step or the code already spent.
 */
@Component
public class SecondFactorVerifier {

    private final FieldEncryption encryption;

    private final TotpBackupCodeRepository backupCodes;

    private final BackupCodeHasher backupCodeHasher;

    public SecondFactorVerifier(FieldEncryption encryption, TotpBackupCodeRepository backupCodes,
                                BackupCodeHasher backupCodeHasher) {
        this.encryption = encryption;
        this.backupCodes = backupCodes;
        this.backupCodeHasher = backupCodeHasher;
    }

    /**
     * A six-digit code is tried as TOTP, anything else as a backup code. An
     * account without 2FA on has nothing to check against, so every code fails.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Verified> verify(User user, String code, Instant now) {
        if (!user.isTotpEnabled()) {
            return Optional.empty();
        }
        String trimmed = code == null ? "" : code.strip();
        if (Totp.isWellFormed(trimmed)) {
            OptionalLong step = matchTotp(user, trimmed, now, user.getTotpLastUsedStep());
            if (step.isEmpty()) {
                return Optional.empty();
            }
            user.recordTotpStep(step.getAsLong(), now);
            return Optional.of(new Verified(Method.TOTP, remainingBackupCodes(user)));
        }
        return BackupCodes.normalise(trimmed).flatMap(normalised -> spendBackupCode(user, normalised, now));
    }

    /** The first code from a secret that is not confirmed yet; nothing is spent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OptionalLong verifyPendingTotp(User user, String code, Instant now) {
        if (!user.hasPendingTotpEnrolment()) {
            return OptionalLong.empty();
        }
        return matchTotp(user, code == null ? "" : code.strip(), now, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int remainingBackupCodes(User user) {
        return Math.toIntExact(backupCodes.countByUserIdAndUsedAtIsNull(user.getId()));
    }

    private OptionalLong matchTotp(User user, String code, Instant now, Long lastUsedStep) {
        if (!Totp.isWellFormed(code)) {
            return OptionalLong.empty();
        }
        byte[] secret = encryption.decrypt(user, EncryptedField.TOTP_SECRET, user.getTotpSecretCiphertext());
        try {
            return Totp.verify(secret, code, now, lastUsedStep);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * Compares against every unused code without stopping at a match, so the
     * time taken does not depend on where in the set the right code sits.
     */
    private Optional<Verified> spendBackupCode(User user, String normalised, Instant now) {
        List<TotpBackupCode> unused = backupCodes.findByUserIdAndUsedAtIsNull(user.getId());
        TotpBackupCode match = null;
        for (TotpBackupCode candidate : unused) {
            if (backupCodeHasher.matches(user.getId(), normalised, candidate.getCodeHash()) && match == null) {
                match = candidate;
            }
        }
        if (match == null) {
            return Optional.empty();
        }
        match.markUsed(now);
        return Optional.of(new Verified(Method.BACKUP_CODE, unused.size() - 1));
    }

    public enum Method {
        TOTP, BACKUP_CODE
    }

    /** @param backupCodesLeft unused backup codes after this one */
    public record Verified(Method method, int backupCodesLeft) {
    }
}
