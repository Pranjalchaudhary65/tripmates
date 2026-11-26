import { useCallback, useState, type ChangeEvent, type SubmitEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { formatWhen, UUID_PATTERN } from '../admin/adminLabels';
import { adminApi } from '../api/adminApi';
import { identityApi, type AdminUserView } from '../api/identityApi';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';
import { useAuthStore } from '../stores/authStore';

const ID_HINT = 'Enter an account ID, like 0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c.';

/**
 * Account lookup and suspension for moderators. The account looked at lives
 * in the address (?userId=), so the report queue can link straight to it.
 * RequireAdmin keeps the screen from rendering for anyone else, but the
 * server decides: every /admin route answers 403 to a non-admin, or to an
 * admin whose session skipped the second factor, whatever this page does.
 */
export function AdminUserLookupPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('userId');
  const requestedValid = requested !== null && UUID_PATTERN.test(requested);
  const [userId, setUserId] = useState(requested ?? '');
  const [clientError, setClientError] = useState<string | null>(
    requested !== null && !requestedValid ? ID_HINT : null,
  );

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const id = userId.trim();
    if (!UUID_PATTERN.test(id)) {
      setClientError(ID_HINT);
      return;
    }
    setClientError(null);
    setParams({ userId: id.toLowerCase() });
  }

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Find an account</h1>
      <form noValidate onSubmit={handleSubmit} className="mt-6 space-y-4">
        <FormField id="admin-user-id" label="Account ID" error={clientError ?? undefined}>
          {(control) => (
            <input
              {...control}
              type="text"
              name="userId"
              spellCheck={false}
              maxLength={36}
              value={userId}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                setUserId(event.target.value);
                setClientError(null);
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <SubmitButton label="Look up" pendingLabel="Looking up…" pending={false} />
      </form>

      {requestedValid && <AccountPanel key={requested} userId={requested} />}
    </section>
  );
}

function AccountPanel({ userId }: { userId: string }) {
  const load = useCallback(() => identityApi.adminFindUser(userId), [userId]);
  const { state, reload } = useApiResource(load);

  if (state.kind === 'loading') {
    return (
      <p role="status" className="mt-6 text-slate-600">
        Looking up the account…
      </p>
    );
  }
  if (state.kind === 'failed') {
    return (
      <div className="mt-6 space-y-3">
        <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
        <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
          Try again
        </button>
      </div>
    );
  }
  return <AccountDetails account={state.data} onChanged={reload} />;
}

type Action = 'suspend' | 'reinstate';

function AccountDetails({ account, onChanged }: { account: AdminUserView; onChanged: () => void }) {
  const me = useAuthStore((store) => store.user?.id);
  const [confirming, setConfirming] = useState<Action | null>(null);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();
  const own = me === account.id;
  const action: Action | null =
    account.accountStatus === 'ACTIVE' ? 'suspend' : account.accountStatus === 'SUSPENDED' ? 'reinstate' : null;

  function apply(chosen: Action) {
    setFailure(null);
    void run(async () => {
      try {
        await (chosen === 'suspend' ? adminApi.suspend(account.id) : adminApi.reinstate(account.id));
        setConfirming(null);
        // The server answers 204 whether or not the state changed; show what is really there now.
        onChanged();
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  return (
    <div className="mt-6 space-y-4">
      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 rounded-md border border-slate-200 bg-white p-4 text-sm">
        <dt className="font-medium text-slate-600">Name</dt>
        <dd>{account.fullName}</dd>
        <dt className="font-medium text-slate-600">Email</dt>
        <dd>{account.email}</dd>
        <dt className="font-medium text-slate-600">Role</dt>
        <dd>{account.role}</dd>
        <dt className="font-medium text-slate-600">Status</dt>
        <dd>{account.deletedAt === null ? account.accountStatus : `Deleted ${formatWhen(account.deletedAt)}`}</dd>
        <dt className="font-medium text-slate-600">Verification</dt>
        <dd>{account.verificationTier}</dd>
        <dt className="font-medium text-slate-600">Two-factor</dt>
        <dd>{account.twoFactorEnabled ? 'On' : 'Off'}</dd>
        <dt className="font-medium text-slate-600">Joined</dt>
        <dd>{formatWhen(account.createdAt)}</dd>
      </dl>

      <p className="text-sm">
        <Link to={`/admin/audit?entityId=${encodeURIComponent(account.id)}`} className="font-medium text-teal-700 underline">
          Audit trail for this account
        </Link>
      </p>

      {failure !== null && <ErrorNotice message={failure.message} correlationId={failure.correlationId} />}

      {own && <p className="text-sm text-slate-600">This is your own account. Another moderator has to act on it.</p>}

      {!own && action !== null && confirming === null && (
        <button
          type="button"
          onClick={() => {
            setConfirming(action);
          }}
          className={
            action === 'suspend'
              ? 'rounded-md border border-red-300 px-4 py-2 font-semibold text-red-800 hover:bg-red-50'
              : 'rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800'
          }
        >
          {action === 'suspend' ? 'Suspend account' : 'Reinstate account'}
        </button>
      )}

      {!own && confirming !== null && (
        <div className="space-y-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-3 text-sm">
          <p className="text-amber-900">
            {confirming === 'suspend'
              ? `Suspend ${account.fullName}? Every session they have ends now, they cannot sign in, and everyone vouching for them is penalised.`
              : `Reinstate ${account.fullName}? They can sign in again. Penalties already given to their vouchers stay.`}
          </p>
          <div className="flex gap-3">
            <button
              type="button"
              disabled={pending}
              onClick={() => {
                apply(confirming);
              }}
              className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800 disabled:bg-slate-400"
            >
              {pending ? 'Saving…' : confirming === 'suspend' ? 'Confirm suspension' : 'Confirm reinstatement'}
            </button>
            <button
              type="button"
              disabled={pending}
              onClick={() => {
                setConfirming(null);
              }}
              className="font-medium text-slate-700 underline"
            >
              Back
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
