import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { useAuthStore } from '../stores/authStore';
import {
  EMAIL_MAX_LENGTH,
  PASSWORD_MAX_LENGTH,
  validateLogin,
  type FieldErrors,
  type LoginField,
} from '../validation/identityRules';

const LOGIN_FIELDS: readonly LoginField[] = ['email', 'password'];

function arrivedAfterVerification(state: unknown): boolean {
  return typeof state === 'object' && state !== null && 'notice' in state && state.notice === 'email-verified';
}

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const login = useAuthStore((state) => state.login);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [clientErrors, setClientErrors] = useState<FieldErrors<LoginField>>({});
  const { pending, run } = useSubmission();
  const server = useRequestFailure(LOGIN_FIELDS);
  const justVerified = arrivedAfterVerification(location.state);

  function handleEmailChange(event: ChangeEvent<HTMLInputElement>) {
    setEmail(event.target.value);
    setClientErrors((current) => ({ ...current, email: undefined }));
    server.clearField('email');
  }

  function handlePasswordChange(event: ChangeEvent<HTMLInputElement>) {
    setPassword(event.target.value);
    setClientErrors((current) => ({ ...current, password: undefined }));
    server.clearField('password');
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (server.retrySecondsLeft > 0) {
      return;
    }
    void run(async () => {
      const { errors, body } = validateLogin({ email, password });
      setClientErrors(errors);
      if (body === null) {
        server.clear();
        return;
      }
      const result = await login(body);
      if (result.ok) {
        void navigate('/', { replace: true });
        return;
      }
      if (result.failure.status === 401) {
        setPassword('');
      }
      server.report(result.failure);
    });
  }

  return (
    <section className="mx-auto max-w-md">
      <h1 className="text-2xl font-bold text-teal-800">Log in</h1>

      <form noValidate onSubmit={handleSubmit} className="mt-6 space-y-4">
        {justVerified && server.notice === null && (
          <p role="status" className="rounded-md bg-teal-50 px-4 py-3 text-sm text-teal-900">
            Your email address is verified. You can log in now.
          </p>
        )}
        {server.notice !== null && (
          <ErrorNotice
            message={server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
        )}

        <FormField id="login-email" label="Email" error={clientErrors.email ?? server.fieldErrors.email}>
          {(control) => (
            <input
              {...control}
              type="email"
              name="email"
              autoComplete="username"
              maxLength={EMAIL_MAX_LENGTH}
              value={email}
              onChange={handleEmailChange}
              className={inputClassName}
            />
          )}
        </FormField>

        <FormField id="login-password" label="Password" error={clientErrors.password ?? server.fieldErrors.password}>
          {(control) => (
            <input
              {...control}
              type="password"
              name="password"
              autoComplete="current-password"
              maxLength={PASSWORD_MAX_LENGTH}
              value={password}
              onChange={handlePasswordChange}
              className={inputClassName}
            />
          )}
        </FormField>

        <SubmitButton label="Log in" pendingLabel="Logging in…" pending={pending} waitSeconds={server.retrySecondsLeft} />
      </form>

      <p className="mt-6 text-sm text-slate-600">
        New to TripMates?{' '}
        <Link to="/signup" className="font-medium text-teal-700 underline">
          Create an account
        </Link>
      </p>
    </section>
  );
}
