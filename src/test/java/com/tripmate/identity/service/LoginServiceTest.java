package com.tripmate.identity.service;

import com.tripmate.identity.captcha.CaptchaVerifier;
import com.tripmate.identity.dto.LoginRequest;
import com.tripmate.identity.entity.Gender;
import com.tripmate.identity.entity.LoginAttempt;
import com.tripmate.identity.entity.LoginFailureReason;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.LoginHistoryRepository;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.ratelimit.RateLimit;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.platform.web.ClientAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The password check has to happen on every path, or response time tells an
 * unknown email from a known one. These tests pin down which hash it runs
 * against; the integration tests check the answers look the same.
 */
class LoginServiceTest {

    private static final String DUMMY_HASH = "$argon2id$dummy";

    private static final String REAL_HASH = "$argon2id$real";

    private static final String PASSWORD = "violet-kettle-harbour-91";

    private final UserRepository users = mock(UserRepository.class);

    private final LoginHistoryRepository history = mock(LoginHistoryRepository.class);

    private final LoginLockout lockout = mock(LoginLockout.class);

    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private LoginService service;

    private LoginClient client;

    @BeforeEach
    void setUp() throws Exception {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        when(lockout.charge(anyString(), any())).thenReturn(LoginLockout.Attempt.allowed());
        RateLimit generous = new RateLimit(1000, Duration.ofMinutes(1));
        service = new LoginService(users, history, lockout, mock(RateLimiter.class), mock(CaptchaVerifier.class),
                passwordEncoder,
                new LoginProperties(3, new LoginProperties.Lockout(5, Duration.ofMinutes(1),
                        Duration.ofMinutes(15), Duration.ofHours(24))),
                new AuthRateLimitProperties(generous, generous, generous, generous, generous, generous, generous,
                        generous),
                mock(LoginCompletion.class), mock(SecondFactorChallenges.class),
                Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));
        client = new LoginClient(new ClientAddress(InetAddress.getByName("203.0.113.5")), "test", null);
    }

    @Test
    void anUnknownEmailIsCheckedAgainstTheDummyHash() {
        when(users.findLiveByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertRefused(new LoginRequest("nobody@example.com", PASSWORD, null));

        verify(passwordEncoder, times(1)).matches(PASSWORD, DUMMY_HASH);
        assertThat(recordedReason()).isEqualTo(LoginFailureReason.UNKNOWN_EMAIL);
    }

    @Test
    void aLockedAccountStillHasItsRealHashChecked() {
        User user = User.register("asha@example.com", REAL_HASH, "Asha Verma",
                LocalDate.of(2000, 4, 15), Gender.FEMALE, Instant.EPOCH);
        when(users.findLiveByEmail("asha@example.com")).thenReturn(Optional.of(user));
        when(lockout.charge(anyString(), any()))
                .thenReturn(LoginLockout.Attempt.lockedUntil(Instant.parse("2026-11-15T10:01:00Z")));
        when(passwordEncoder.matches(PASSWORD, REAL_HASH)).thenReturn(true);

        assertRefused(new LoginRequest("asha@example.com", PASSWORD, null));

        verify(passwordEncoder, times(1)).matches(PASSWORD, REAL_HASH);
        assertThat(recordedReason()).isEqualTo(LoginFailureReason.LOCKED);
    }

    @Test
    void theCounterKeyIsTheSameWhateverTheEmailsCase() {
        assertThat(LoginService.counterKey("Asha@Example.com", null))
                .isEqualTo(LoginService.counterKey("asha@example.com", null))
                .doesNotContain("asha");
    }

    private void assertRefused(LoginRequest request) {
        assertThatThrownBy(() -> service.login(request, client))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.type()).isEqualTo(LoginProblem.INVALID_CREDENTIALS));
    }

    private LoginFailureReason recordedReason() {
        ArgumentCaptor<LoginAttempt> saved = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(history).save(saved.capture());
        return saved.getValue().getFailureReason();
    }
}
