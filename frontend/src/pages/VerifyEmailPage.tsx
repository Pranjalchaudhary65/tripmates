import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useCountdown } from '../hooks/useCountdown';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { useAuthStore, type PendingVerification } from '../stores/authStore';
import { validateVerificationCode } from '../validation/identityRules';

/**
 * Mirrors tripmate.identity.verification.resend-cooldown. The server enforces
 * its own cooldown regardless (a resend inside it just returns the current
 * ID); this only stops the button offering something that would do nothing.
 */
export const RESEND_COOLDOWN_SECONDS = 60;

/** Mirrors tripmate.identity.verification.code-ttl; shown as guidance only. */
const CODE_TTL_MINUTES = 10;

const CODE_FIELDS = ['code'] as const;
const NO_FIELDS = [] as const;

export interface EmailVerifiedNotice {
  notice: 'email-verified';
}

export function VerifyEmailPage() {
  const pending = useAuthStore((state) => state.pendingVerification);
  if (pending === null) {
    return <NoPendingSignup />;
  }
  return <VerifyEmailForm pending={pending} />;
}

/**
 * The verificationId lives only in memory, so a reload, a new tab or a link
 * from elsewhere arrives here with nothing to verify. Signing up again with
 * the same details is the recovery path: the server replaces an unverified
 * registration and emails a fresh code.
 */
function NoPendingSignup() {
  return (
    <section className="mx-auto max-w-md">
      <h1 className="text-2xl font-bold text-teal-800">Verify your email</h1>
      <p className="mt-3 text-slate-600">
        There is no sign-up waiting for a code in this tab. This happens if the page was reloaded or opened in a new
        tab: for your security, the sign-up in progress is not kept anywhere the browser could leak it.
      </p>
      <p className="mt-3 text-slate-600">
        Sign up again with the same email address and we will send you a new code. If you have already verified your
        email, log in instead.
      </p>
      <div className="mt-6 flex gap-4">
        <Link to="/signup" className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800">
          Sign up again
        </Link>
        <Link to="/login" className="px-4 py-2 font-medium text-teal-700 underline">
          Log in
        </Link>
      </div>
    </section>
  );
}

function VerifyEmailForm({ pending }: { pending: PendingVerification }) {
  const navigate = useNavigate();
  const verifyEmail = useAuthStore((state) => state.verifyEmail);
  const resendVerification = useAuthStore((state) => state.resendVerification);

  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | undefined>(undefined);
  const [resent, setResent] = useState(false);

  const verifying = useSubmission();
  const verifyFailure = useRequestFailure(CODE_FIELDS);

  const resending = useSubmission();
  const resendFailure = useRequestFailure(NO_FIELDS);
  const cooldown = useCountdown(pending.codeRequestedAt + RESEND_COOLDOWN_SECONDS * 1000);

  function handleCodeChange(event: ChangeEvent<HTMLInputElement>) {
    setCode(event.target.value.replace(/\D/g, '').slice(0, 6));
    setCodeError(undefined);
    verifyFailure.clearField('code');
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (verifyFailure.retrySecondsLeft > 0) {
      return;
    }
    void verifying.run(async () => {
      const error = validateVerificationCode(code);
      setCodeError(error ?? undefined);
      if (error !== null) {
        verifyFailure.clear();
        return;
      }
      const result = await verifyEmail(code);
      if (result.ok) {
        const state: EmailVerifiedNotice = { notice: 'email-verified' };
        void navigate('/login', { replace: true, state });
      } else {
        verifyFailure.report(result.failure);
      }
    });
  }

  function handleResend() {
    if (cooldown.secondsLeft > 0 || resendFailure.retrySecondsLeft > 0) {
      return;
    }
    void resending.run(async () => {
      const result = await resendVerification();
      if (result.ok) {
        resendFailure.clear();
        setResent(true);
        setCode('');
        verifyFailure.clear();
        cooldown.start(RESEND_COOLDOWN_SECONDS);
      } else {
        setResent(false);
        resendFailure.report(result.failure);
      }
    });
  }

  const resendWait = Math.max(cooldown.secondsLeft, resendFailure.retrySecondsLeft);
  let resendLabel = 'Send a new code';
  if (resending.pending) {
    resendLabel = 'Sending…';
  } else if (resendWait > 0) {
    resendLabel = `Send a new code in ${String(resendWait)}s`;
  }

  return (
    <section className="mx-auto max-w-md">
      <h1 className="text-2xl font-bold text-teal-800">Verify your email</h1>
      <p className="mt-2 text-slate-600">{pending.message}</p>
      <p className="mt-2 text-sm text-slate-500">
        Email entered: <span className="font-medium text-slate-700">{pending.email}</span>. The code expires after{' '}
        {CODE_TTL_MINUTES} minutes. Check your spam folder if it has not arrived.
      </p>

      <form noValidate onSubmit={handleSubmit} className="mt-6 space-y-4">
        {verifyFailure.notice !== null && (
          <ErrorNotice
            message={verifyFailure.notice.message}
            correlationId={verifyFailure.notice.correlationId}
            retrySecondsLeft={verifyFailure.noticeRetrySeconds}
          />
        )}

        <FormField
          id="verify-code"
          label="Verification code"
          error={codeError ?? verifyFailure.fieldErrors.code}
          hint="The 6-digit code from the email."
        >
          {(control) => (
            <input
              {...control}
              type="text"
              name="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              maxLength={6}
              value={code}
              onChange={handleCodeChange}
              className={`${inputClassName} font-mono text-lg tracking-[0.5em]`}
            />
          )}
        </FormField>

        <SubmitButton
          label="Verify email"
          pendingLabel="Verifying…"
          pending={verifying.pending}
          waitSeconds={verifyFailure.retrySecondsLeft}
        />
      </form>

      <div className="mt-6 space-y-3 border-t border-slate-200 pt-4">
        {resent && (
          <p role="status" className="rounded-md bg-teal-50 px-4 py-3 text-sm text-teal-900">
            {pending.message} Use the newest email: codes sent before it no longer work.
          </p>
        )}
        {resendFailure.notice !== null && (
          <ErrorNotice
            message={resendFailure.notice.message}
            correlationId={resendFailure.notice.correlationId}
            retrySecondsLeft={resendFailure.noticeRetrySeconds}
          />
        )}
        <button
          type="button"
          onClick={handleResend}
          disabled={resending.pending || resendWait > 0}
          className="font-medium text-teal-700 underline disabled:cursor-not-allowed disabled:text-slate-400 disabled:no-underline"
        >
          {resendLabel}
        </button>
        <p className="text-sm text-slate-500">
          Wrong email address?{' '}
          <Link to="/signup" className="font-medium text-teal-700 underline">
            Sign up again
          </Link>
        </p>
      </div>
    </section>
  );
}
