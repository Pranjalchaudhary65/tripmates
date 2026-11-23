import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { identityApi, type AdminUserView } from '../api/identityApi';
import { toApiFailure } from '../api/problem';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const NO_FIELDS: readonly never[] = [];

/**
 * Account lookup for moderators. RequireAdmin keeps the screen from
 * rendering for anyone else, but the server decides: /admin/users answers
 * 403 to a non-admin, or to an admin whose session skipped the second
 * factor, whatever this page does.
 */
export function AdminUserLookupPage() {
  const [userId, setUserId] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const [account, setAccount] = useState<AdminUserView | null>(null);
  const { pending, run } = useSubmission();
  const server = useRequestFailure(NO_FIELDS);

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    void run(async () => {
      const id = userId.trim();
      const error = UUID.test(id) ? null : 'Enter an account ID, like 0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c.';
      setClientError(error);
      setAccount(null);
      if (error !== null) {
        server.clear();
        return;
      }
      try {
        setAccount(await identityApi.adminFindUser(id));
        server.clear();
      } catch (failure) {
        server.report(toApiFailure(failure));
      }
    });
  }

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Find an account</h1>
      <form noValidate onSubmit={handleSubmit} className="mt-6 space-y-4">
        {server.notice !== null && (
          <ErrorNotice
            message={server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
        )}
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
        <SubmitButton label="Look up" pendingLabel="Looking up…" pending={pending} waitSeconds={server.retrySecondsLeft} />
      </form>

      {account !== null && (
        <dl className="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 rounded-md border border-slate-200 bg-white p-4 text-sm">
          <dt className="font-medium text-slate-600">Name</dt>
          <dd>{account.fullName}</dd>
          <dt className="font-medium text-slate-600">Email</dt>
          <dd>{account.email}</dd>
          <dt className="font-medium text-slate-600">Role</dt>
          <dd>{account.role}</dd>
          <dt className="font-medium text-slate-600">Status</dt>
          <dd>{account.deletedAt === null ? account.accountStatus : `Deleted ${account.deletedAt}`}</dd>
          <dt className="font-medium text-slate-600">Verification</dt>
          <dd>{account.verificationTier}</dd>
          <dt className="font-medium text-slate-600">Two-factor</dt>
          <dd>{account.twoFactorEnabled ? 'On' : 'Off'}</dd>
          <dt className="font-medium text-slate-600">Joined</dt>
          <dd>{account.createdAt}</dd>
        </dl>
      )}
    </section>
  );
}
