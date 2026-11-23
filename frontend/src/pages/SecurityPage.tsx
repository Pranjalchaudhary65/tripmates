import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { identityApi, type TwoFactorSetup, type TwoFactorStatus } from '../api/identityApi';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import {
  PASSWORD_MAX_LENGTH,
  SECOND_FACTOR_CODE_MAX_LENGTH,
  validateAnySecondFactorCode,
  validateCurrentPassword,
  validateTotpCode,
} from '../validation/identityRules';

const loadStatus = () => identityApi.twoFactorStatus();

type Outcome = { ok: true } | { ok: false; failure: ApiFailure };

type Attempt<T> = { ok: true; value: T } | { ok: false; failure: ApiFailure };

async function attempt<T>(task: () => Promise<T>): Promise<Attempt<T>> {
  try {
    return { ok: true, value: await task() };
  } catch (error) {
    return { ok: false, failure: toApiFailure(error) };
  }
}

/**
 * Two-factor settings. The secret and the backup codes exist on this screen
 * only while it shows them, in component state: they are never put in the
 * auth store, storage or the URL, and the server never returns them again.
 */
export function SecurityPage() {
  const { state, reload } = useApiResource(loadStatus);
  const [backupCodes, setBackupCodes] = useState<readonly string[] | null>(null);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Two-factor authentication</h1>

      {backupCodes !== null ? (
        <BackupCodesView
          codes={backupCodes}
          onDone={() => {
            setBackupCodes(null);
            reload();
          }}
        />
      ) : (
        <>
          {state.kind === 'loading' && (
            <p role="status" className="mt-6 text-slate-600">
              Loading…
            </p>
          )}
          {state.kind === 'failed' && (
            <div className="mt-6 space-y-3">
              <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
              <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
                Try again
              </button>
            </div>
          )}
          {state.kind === 'ready' &&
            (state.data.enabled ? (
              <EnabledView status={state.data} onBackupCodes={setBackupCodes} onDisabled={reload} />
            ) : (
              <SetupFlow status={state.data} onBackupCodes={setBackupCodes} />
            ))}
        </>
      )}
    </section>
  );
}

function SetupFlow({
  status,
  onBackupCodes,
}: {
  status: TwoFactorStatus;
  onBackupCodes: (codes: readonly string[]) => void;
}) {
  const [setup, setSetup] = useState<TwoFactorSetup | null>(null);
  const [started, setStarted] = useState(false);

  if (setup !== null) {
    return <ConfirmSetup setup={setup} onConfirmed={onBackupCodes} />;
  }
  return (
    <div className="mt-6">
      <p className="text-slate-700">
        Two-factor authentication is <strong>off</strong>. Turn it on to ask for a code from an authenticator app
        (Google Authenticator, Microsoft Authenticator, Aegis, 2FAS…) every time you log in.
      </p>
      {status.setupPending && (
        <p className="mt-3 rounded-md bg-amber-50 px-4 py-3 text-sm text-amber-900">
          A setup was started but not finished. Start again to get a new key; the old one will stop working.
        </p>
      )}
      {started ? (
        <PasswordForm
          title="Confirm it is you"
          submitLabel="Continue"
          pendingLabel="Checking…"
          onSubmit={async (password) => {
            const outcome = await attempt(() => identityApi.startTwoFactorSetup(password));
            if (outcome.ok) {
              setSetup(outcome.value);
            }
            return outcome;
          }}
        />
      ) : (
        <button
          type="button"
          onClick={() => {
            setStarted(true);
          }}
          className="mt-4 rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800"
        >
          Set up two-factor authentication
        </button>
      )}
    </div>
  );
}

/**
 * The key is shown for entering by hand; most apps accept the otpauth URI
 * too. No QR code: it would need a library and draw the same secret on a
 * screen that may be shared or recorded, and typing the key works everywhere.
 */
function ConfirmSetup({ setup, onConfirmed }: { setup: TwoFactorSetup; onConfirmed: (codes: readonly string[]) => void }) {
  const [code, setCode] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const { pending, run } = useSubmission();
  const server = useRequestFailure(['code'] as const);

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    void run(async () => {
      const error = validateTotpCode(code);
      setClientError(error);
      if (error !== null) {
        server.clear();
        return;
      }
      const outcome = await attempt(() => identityApi.confirmTwoFactor(code.trim()));
      if (outcome.ok) {
        onConfirmed(outcome.value);
      } else {
        setCode('');
        server.report(outcome.failure);
      }
    });
  }

  return (
    <div className="mt-6 space-y-4">
      <ol className="list-decimal space-y-2 pl-5 text-slate-700">
        <li>In your authenticator app, add an account and choose to enter a setup key.</li>
        <li>
          Account name: <strong>{setup.issuer}</strong> ({setup.accountName}). Type: time based.
        </li>
        <li>Enter this key, then type the 6-digit code the app shows below.</li>
      </ol>
      <div className="rounded-md border border-slate-200 bg-white px-4 py-3">
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Setup key</p>
        <p className="mt-1 font-mono text-lg tracking-wider break-all select-all" data-testid="totp-secret">
          {groupInFours(setup.secret)}
        </p>
        <p className="mt-3 text-xs font-medium uppercase tracking-wide text-slate-500">Or this link, if your app accepts one</p>
        <p className="mt-1 font-mono text-xs break-all select-all text-slate-700">{setup.otpauthUri}</p>
      </div>
      <p className="text-sm text-amber-900">Keep this key private. Anyone who has it can produce your codes.</p>

      <form noValidate onSubmit={handleSubmit} className="space-y-4">
        {server.notice !== null && (
          <ErrorNotice
            message={server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
        )}
        <FormField id="setup-code" label="Code from the app" error={clientError ?? server.fieldErrors.code}>
          {(control) => (
            <input
              {...control}
              type="text"
              name="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              value={code}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                setCode(event.target.value);
                setClientError(null);
                server.clearField('code');
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <SubmitButton
          label="Turn on two-factor authentication"
          pendingLabel="Turning on…"
          pending={pending}
          waitSeconds={server.retrySecondsLeft}
        />
      </form>
    </div>
  );
}

function BackupCodesView({ codes, onDone }: { codes: readonly string[]; onDone: () => void }) {
  return (
    <div className="mt-6 space-y-4">
      <div role="alert" className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
        <p className="font-semibold">Save these backup codes now. They will not be shown again.</p>
        <p className="mt-1">
          Each one works once, in place of a code from your app, if you lose your phone. Keep them somewhere safe
          and private. Any codes you had before no longer work.
        </p>
      </div>
      <ul aria-label="Backup codes" className="grid grid-cols-2 gap-2 rounded-md border border-slate-200 bg-white p-4 font-mono">
        {codes.map((code) => (
          <li key={code} className="select-all">
            {code}
          </li>
        ))}
      </ul>
      <button type="button" onClick={onDone} className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800">
        I have saved my backup codes
      </button>
    </div>
  );
}

function EnabledView({
  status,
  onBackupCodes,
  onDisabled,
}: {
  status: TwoFactorStatus;
  onBackupCodes: (codes: readonly string[]) => void;
  onDisabled: () => void;
}) {
  const [action, setAction] = useState<'none' | 'regenerate' | 'disable'>('none');

  return (
    <div className="mt-6 space-y-4">
      <p className="text-slate-700">
        Two-factor authentication is <strong>on</strong>. Backup codes left:{' '}
        <strong>{String(status.backupCodesRemaining)}</strong>
        {status.backupCodesRemaining <= 3 && ' — consider creating new ones.'}
      </p>
      <div className="flex gap-3">
        <button
          type="button"
          onClick={() => {
            setAction('regenerate');
          }}
          className="rounded-md border border-teal-300 px-4 py-2 font-semibold text-teal-800 hover:bg-teal-50"
        >
          Create new backup codes
        </button>
        <button
          type="button"
          onClick={() => {
            setAction('disable');
          }}
          className="rounded-md border border-red-300 px-4 py-2 font-semibold text-red-800 hover:bg-red-50"
        >
          Turn off
        </button>
      </div>

      {action === 'regenerate' && (
        <PasswordAndCodeForm
          key="regenerate"
          title="Create new backup codes"
          description="Your current backup codes will stop working."
          submitLabel="Create new codes"
          pendingLabel="Creating…"
          onSubmit={async (password, code) => {
            const outcome = await attempt(() => identityApi.regenerateBackupCodes(password, code));
            if (outcome.ok) {
              onBackupCodes(outcome.value);
            }
            return outcome;
          }}
        />
      )}
      {action === 'disable' && (
        <PasswordAndCodeForm
          key="disable"
          title="Turn off two-factor authentication"
          description="Your password alone will sign in to your account, and every other device will be signed out."
          submitLabel="Turn off two-factor authentication"
          pendingLabel="Turning off…"
          onSubmit={async (password, code) => {
            const outcome = await attempt(() => identityApi.disableTwoFactor(password, code));
            if (outcome.ok) {
              onDisabled();
            }
            return outcome;
          }}
        />
      )}
    </div>
  );
}

const PASSWORD_FIELDS = ['password'] as const;

function PasswordForm({
  title,
  submitLabel,
  pendingLabel,
  onSubmit,
}: {
  title: string;
  submitLabel: string;
  pendingLabel: string;
  onSubmit: (password: string) => Promise<Outcome>;
}) {
  const [password, setPassword] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const { pending, run } = useSubmission();
  const server = useRequestFailure(PASSWORD_FIELDS);

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    void run(async () => {
      const error = validateCurrentPassword(password);
      setClientError(error);
      if (error !== null) {
        server.clear();
        return;
      }
      const outcome = await onSubmit(password);
      if (!outcome.ok) {
        setPassword('');
        server.report(outcome.failure);
      }
    });
  }

  return (
    <form noValidate onSubmit={handleSubmit} className="mt-4 space-y-4 rounded-md border border-slate-200 bg-white p-4">
      <h2 className="font-semibold text-slate-900">{title}</h2>
      {server.notice !== null && (
        <ErrorNotice
          message={server.notice.message}
          correlationId={server.notice.correlationId}
          retrySecondsLeft={server.noticeRetrySeconds}
        />
      )}
      <PasswordInput
        id="reauth-password"
        value={password}
        error={clientError ?? server.fieldErrors.password}
        onChange={(value) => {
          setPassword(value);
          setClientError(null);
          server.clearField('password');
        }}
      />
      <SubmitButton label={submitLabel} pendingLabel={pendingLabel} pending={pending} waitSeconds={server.retrySecondsLeft} />
    </form>
  );
}

const CHANGE_FIELDS = ['password', 'code'] as const;

function PasswordAndCodeForm({
  title,
  description,
  submitLabel,
  pendingLabel,
  onSubmit,
}: {
  title: string;
  description: string;
  submitLabel: string;
  pendingLabel: string;
  onSubmit: (password: string, code: string) => Promise<Outcome>;
}) {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [errors, setErrors] = useState<{ password?: string; code?: string }>({});
  const { pending, run } = useSubmission();
  const server = useRequestFailure(CHANGE_FIELDS);

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    void run(async () => {
      const passwordError = validateCurrentPassword(password);
      const codeError = validateAnySecondFactorCode(code);
      const next: { password?: string; code?: string } = {};
      if (passwordError !== null) {
        next.password = passwordError;
      }
      if (codeError !== null) {
        next.code = codeError;
      }
      setErrors(next);
      if (passwordError !== null || codeError !== null) {
        server.clear();
        return;
      }
      const outcome = await onSubmit(password, code.trim());
      if (!outcome.ok) {
        setPassword('');
        setCode('');
        server.report(outcome.failure);
      }
    });
  }

  return (
    <form noValidate onSubmit={handleSubmit} className="space-y-4 rounded-md border border-slate-200 bg-white p-4">
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <p className="text-sm text-slate-600">{description}</p>
      {server.notice !== null && (
        <ErrorNotice
          message={server.notice.message}
          correlationId={server.notice.correlationId}
          retrySecondsLeft={server.noticeRetrySeconds}
        />
      )}
      <PasswordInput
        id="change-password"
        value={password}
        error={errors.password ?? server.fieldErrors.password}
        onChange={(value) => {
          setPassword(value);
          setErrors((current) => ({ ...current, password: undefined }));
          server.clearField('password');
        }}
      />
      <FormField
        id="change-code"
        label="Code from your app, or a backup code"
        error={errors.code ?? server.fieldErrors.code}
      >
        {(control) => (
          <input
            {...control}
            type="text"
            name="code"
            autoComplete="one-time-code"
            spellCheck={false}
            maxLength={SECOND_FACTOR_CODE_MAX_LENGTH}
            value={code}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              setCode(event.target.value);
              setErrors((current) => ({ ...current, code: undefined }));
              server.clearField('code');
            }}
            className={inputClassName}
          />
        )}
      </FormField>
      <SubmitButton label={submitLabel} pendingLabel={pendingLabel} pending={pending} waitSeconds={server.retrySecondsLeft} />
    </form>
  );
}

function PasswordInput({
  id,
  value,
  error,
  onChange,
}: {
  id: string;
  value: string;
  error: string | undefined;
  onChange: (value: string) => void;
}) {
  return (
    <FormField id={id} label="Password" error={error}>
      {(control) => (
        <input
          {...control}
          type="password"
          name="password"
          autoComplete="current-password"
          maxLength={PASSWORD_MAX_LENGTH}
          value={value}
          onChange={(event: ChangeEvent<HTMLInputElement>) => {
            onChange(event.target.value);
          }}
          className={inputClassName}
        />
      )}
    </FormField>
  );
}

function groupInFours(secret: string): string {
  return secret.replace(/(.{4})(?=.)/g, '$1 ');
}
