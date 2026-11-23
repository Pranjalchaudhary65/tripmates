import { useState, type ChangeEvent, type ReactNode, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useCountdown } from '../hooks/useCountdown';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { useAuthStore, type PendingSecondFactor } from '../stores/authStore';
import {
  SECOND_FACTOR_CODE_MAX_LENGTH,
  validateSecondFactorCode,
  type SecondFactorMethod,
} from '../validation/identityRules';

const CODE_FIELDS = ['code'] as const;

/**
 * The second half of a login on a 2FA account. The challenge lives only in
 * the auth store, so a reload, a new tab or a bookmark lands on the empty
 * state, and the way on is the password again: nothing here can be resumed
 * from the URL or from storage.
 */
export function SecondFactorPage() {
  const pending = useAuthStore((state) => state.pendingSecondFactor);

  if (pending === null) {
    return (
      <SecondFactorShell>
        <p className="mt-4 text-slate-600">
          There is no sign-in waiting for a code in this tab. Enter your email and password again.
        </p>
        <BackToLogin />
      </SecondFactorShell>
    );
  }
  // Keyed on the challenge so a fresh login starts with a fresh form and countdown.
  return <SecondFactorForm key={pending.challengeId} pending={pending} />;
}

function SecondFactorForm({ pending }: { pending: PendingSecondFactor }) {
  const completeSecondFactor = useAuthStore((state) => state.completeSecondFactor);
  const abandonSecondFactor = useAuthStore((state) => state.abandonSecondFactor);
  const [method, setMethod] = useState<SecondFactorMethod>('totp');
  const [code, setCode] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const [challengeExpired, setChallengeExpired] = useState(false);
  const { pending: submitting, run } = useSubmission();
  const server = useRequestFailure(CODE_FIELDS);
  const { secondsLeft } = useCountdown(pending.expiresAt);

  if (secondsLeft === 0 || challengeExpired) {
    return (
      <SecondFactorShell>
        <div role="alert" className="mt-4 rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          This sign-in has expired. Enter your email and password again.
        </div>
        <BackToLogin onClick={abandonSecondFactor} />
      </SecondFactorShell>
    );
  }

  function handleCodeChange(event: ChangeEvent<HTMLInputElement>) {
    setCode(event.target.value);
    setClientError(null);
    server.clearField('code');
  }

  function switchMethod(next: SecondFactorMethod) {
    setMethod(next);
    setCode('');
    setClientError(null);
    server.clear();
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (server.retrySecondsLeft > 0) {
      return;
    }
    void run(async () => {
      const error = validateSecondFactorCode(code, method);
      setClientError(error);
      if (error !== null) {
        server.clear();
        return;
      }
      const result = await completeSecondFactor(code.trim());
      // On success the session exists and GuestOnly moves on.
      if (!result.ok) {
        setCode('');
        if (result.failure.code === 'login_challenge_expired') {
          setChallengeExpired(true);
          return;
        }
        server.report(result.failure);
      }
    });
  }

  const isTotp = method === 'totp';

  return (
    <SecondFactorShell>
      <p className="mt-2 text-sm text-slate-600" aria-live="polite">
        {isTotp
          ? 'Enter the 6-digit code your authenticator app shows for TripMates.'
          : 'Enter one of the backup codes you saved when you turned on two-factor authentication. Each works once.'}{' '}
        This sign-in expires in {formatRemaining(secondsLeft)}.
      </p>

      <form noValidate onSubmit={handleSubmit} className="mt-6 space-y-4">
        {server.notice !== null && (
          <ErrorNotice
            message={server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
        )}

        <FormField
          id="second-factor-code"
          label={isTotp ? 'Authenticator code' : 'Backup code'}
          error={clientError ?? server.fieldErrors.code}
        >
          {(control) => (
            <input
              {...control}
              type="text"
              name="code"
              autoComplete="one-time-code"
              inputMode={isTotp ? 'numeric' : 'text'}
              autoCapitalize={isTotp ? 'off' : 'characters'}
              spellCheck={false}
              maxLength={isTotp ? 6 : SECOND_FACTOR_CODE_MAX_LENGTH}
              value={code}
              onChange={handleCodeChange}
              className={inputClassName}
            />
          )}
        </FormField>

        <SubmitButton label="Verify" pendingLabel="Verifying…" pending={submitting} waitSeconds={server.retrySecondsLeft} />
      </form>

      <p className="mt-6 text-sm text-slate-600">
        <button
          type="button"
          onClick={() => {
            switchMethod(isTotp ? 'backup' : 'totp');
          }}
          className="font-medium text-teal-700 underline"
        >
          {isTotp ? 'Use a backup code instead' : 'Use the authenticator app instead'}
        </button>
      </p>
      <BackToLogin onClick={abandonSecondFactor} label="Cancel and start again" />
    </SecondFactorShell>
  );
}

function SecondFactorShell({ children }: { children: ReactNode }) {
  return (
    <section className="mx-auto max-w-md">
      <h1 className="text-2xl font-bold text-teal-800">Two-factor authentication</h1>
      {children}
    </section>
  );
}

function BackToLogin({ onClick, label = 'Back to log in' }: { onClick?: () => void; label?: string }) {
  return (
    <p className="mt-4 text-sm">
      <Link to="/login" onClick={onClick} className="font-medium text-teal-700 underline">
        {label}
      </Link>
    </p>
  );
}

function formatRemaining(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return minutes > 0 ? `${String(minutes)}:${String(rest).padStart(2, '0')}` : `${String(rest)} seconds`;
}
