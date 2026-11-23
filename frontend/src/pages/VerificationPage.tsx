import { useEffect, useState, type ChangeEvent, type ReactNode, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { toApiFailure } from '../api/problem';
import {
  verificationApi,
  type PhoneCodeSent,
  type VerificationOverview,
  type VerificationRejection,
} from '../api/verificationApi';
import { TIER_ORDER } from '../auth/tiers';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SelfieCheck } from '../components/SelfieCheck';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useCountdown } from '../hooks/useCountdown';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { useAuthStore, type VerificationTier } from '../stores/authStore';
import type { FieldErrors } from '../validation/identityRules';
import {
  validatePhoneCode,
  validatePhoneNumber,
  validatePhoto,
  type PhoneCodeField,
  type PhoneField,
} from '../validation/verificationRules';

const PHONE_FIELDS: readonly PhoneField[] = ['phoneNumber'];
const CODE_FIELDS: readonly PhoneCodeField[] = ['code'];
const NO_FIELDS: readonly never[] = [];

const loadOverview = () => verificationApi.overview();

const TIER_LABELS: Record<VerificationTier, string> = {
  NONE: 'Not verified',
  EMAIL: 'Email',
  PHONE: 'Phone',
  SELFIE_MATCH: 'Selfie match',
};

const REJECTION_MESSAGES: Record<VerificationRejection, string> = {
  LIVENESS_FAILED: 'We could not see you move between the two photos. Try again, and follow the prompt.',
  LOW_SIMILARITY:
    'Your selfie did not match your profile photo. Check that your profile photo shows your face clearly, then try again.',
  REVIEWER_REJECTED: 'A reviewer could not match your selfie to your profile photo. You can try again.',
  PROFILE_PHOTO_CHANGED: 'You changed your profile photo, so take a new selfie to match it.',
  REVIEW_EXPIRED: 'Your selfie was not reviewed in time and has been deleted. You can try again.',
};

/**
 * Every verification step on one screen, loaded with one call and updated
 * from each step's own response. Each step is optional: verifying makes an
 * account visible to travellers who only match with verified people, and
 * nothing in the app is locked without it.
 */
export function VerificationPage() {
  const { state, reload } = useApiResource(loadOverview);
  const [latest, setLatest] = useState<VerificationOverview | null>(null);
  const setVerificationTier = useAuthStore((store) => store.setVerificationTier);
  const overview = latest ?? (state.kind === 'ready' ? state.data : null);
  const tier = overview?.tier;

  useEffect(() => {
    if (tier !== undefined) {
      setVerificationTier(tier);
    }
  }, [tier, setVerificationTier]);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Verification centre</h1>
      <p className="mt-2 text-sm text-slate-600">
        Every step is optional. Each one you complete makes you visible to travellers who only match with verified
        people. TripMates never asks for a government ID.
      </p>

      {overview === null && state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading your verification…
        </p>
      )}
      {overview === null && state.kind === 'failed' && (
        <div className="mt-6 space-y-3">
          <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
          <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {overview !== null && <Steps overview={overview} onUpdated={setLatest} />}
    </section>
  );
}

function Steps({
  overview,
  onUpdated,
}: {
  overview: VerificationOverview;
  onUpdated: (overview: VerificationOverview) => void;
}) {
  return (
    <>
      <TierProgress tier={overview.tier} />
      <Step title="Email">
        <p className="text-sm text-slate-700">{overview.emailVerified ? 'Verified.' : 'Not verified yet.'}</p>
      </Step>
      <Step title="Phone number">
        {overview.phone.verified ? (
          <p className="text-sm text-slate-700">Verified: {overview.phone.maskedNumber}</p>
        ) : (
          <PhoneStep onVerified={onUpdated} />
        )}
      </Step>
      <Step title="Profile photo">
        <PhotoStep overview={overview} onUploaded={onUpdated} />
      </Step>
      <Step title="Selfie check">
        <SelfieStep overview={overview} onSubmitted={onUpdated} />
      </Step>
      <Step title="Vouches">
        <VouchStep overview={overview} />
      </Step>
    </>
  );
}

/** The four tiers in order, the reached ones filled; the current one is announced as such. */
function TierProgress({ tier }: { tier: VerificationTier }) {
  const reached = TIER_ORDER.indexOf(tier);
  return (
    <div className="mt-6">
      <div
        role="progressbar"
        aria-label="Verification tier"
        aria-valuemin={0}
        aria-valuemax={TIER_ORDER.length - 1}
        aria-valuenow={reached}
        aria-valuetext={TIER_LABELS[tier]}
        className="h-2 w-full rounded bg-slate-200"
      >
        <div className="h-2 rounded bg-teal-600" style={{ width: `${String((reached / (TIER_ORDER.length - 1)) * 100)}%` }} />
      </div>
      <ol className="mt-2 grid grid-cols-4 text-center text-xs">
        {TIER_ORDER.map((step, index) => (
          <li
            key={step}
            aria-current={step === tier ? 'step' : undefined}
            className={index <= reached ? 'font-semibold text-teal-800' : 'text-slate-500'}
          >
            {TIER_LABELS[step]}
          </li>
        ))}
      </ol>
    </div>
  );
}

function Step({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mt-6 rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="font-semibold text-slate-900">{title}</h2>
      <div className="mt-2">{children}</div>
    </section>
  );
}

function PhoneStep({ onVerified }: { onVerified: (overview: VerificationOverview) => void }) {
  const [number, setNumber] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState<PhoneCodeSent | null>(null);
  const [numberErrors, setNumberErrors] = useState<FieldErrors<PhoneField>>({});
  const [codeErrors, setCodeErrors] = useState<FieldErrors<PhoneCodeField>>({});
  const sendServer = useRequestFailure(PHONE_FIELDS);
  const confirmServer = useRequestFailure(CODE_FIELDS);
  const sending = useSubmission();
  const confirming = useSubmission();
  const resend = useCountdown();

  function send(event?: SubmitEvent<HTMLFormElement>) {
    event?.preventDefault();
    const checked = validatePhoneNumber(number);
    setNumberErrors(checked.errors);
    sendServer.clear();
    if (checked.phoneNumber === null) {
      return;
    }
    const phoneNumber = checked.phoneNumber;
    void sending.run(async () => {
      try {
        const result = await verificationApi.sendPhoneCode(phoneNumber);
        setSent(result);
        setCode('');
        confirmServer.clear();
        resend.start(result.resendAfterSeconds);
      } catch (error) {
        sendServer.report(toApiFailure(error));
      }
    });
  }

  function confirm(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (sent === null) {
      return;
    }
    const checked = validatePhoneCode(code);
    setCodeErrors(checked.errors);
    confirmServer.clear();
    if (checked.code === null) {
      return;
    }
    const enteredCode = checked.code;
    void confirming.run(async () => {
      try {
        onVerified(await verificationApi.confirmPhone(sent.verificationId, enteredCode));
      } catch (error) {
        setCode('');
        confirmServer.report(toApiFailure(error));
      }
    });
  }

  if (sent === null) {
    return (
      <form noValidate onSubmit={send} className="space-y-3">
        <FormField
          id="phone-number"
          label="Mobile number"
          hint="With its country code. A 10-digit number is read as Indian."
          error={numberErrors.phoneNumber ?? sendServer.fieldErrors.phoneNumber}
        >
          {(control) => (
            <input
              {...control}
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              value={number}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                setNumber(event.target.value);
                setNumberErrors({});
                sendServer.clearField('phoneNumber');
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        {sendServer.notice !== null && (
          <ErrorNotice
            message={sendServer.notice.message}
            correlationId={sendServer.notice.correlationId}
            retrySecondsLeft={sendServer.noticeRetrySeconds}
          />
        )}
        <SubmitButton
          label="Send a code"
          pendingLabel="Sending…"
          pending={sending.pending}
          waitSeconds={sendServer.retrySecondsLeft}
        />
      </form>
    );
  }

  return (
    <form noValidate onSubmit={confirm} className="space-y-3">
      <p role="status" className="text-sm text-slate-700">
        We sent a 6-digit code to {sent.maskedNumber}. It expires in {Math.round(sent.expiresInSeconds / 60)} minutes.
      </p>
      <FormField id="phone-code" label="Code" error={codeErrors.code ?? confirmServer.fieldErrors.code}>
        {(control) => (
          <input
            {...control}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            value={code}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              setCode(event.target.value);
              setCodeErrors({});
              confirmServer.clearField('code');
            }}
            className={inputClassName}
          />
        )}
      </FormField>
      {confirmServer.notice !== null && (
        <ErrorNotice
          message={confirmServer.notice.message}
          correlationId={confirmServer.notice.correlationId}
          retrySecondsLeft={confirmServer.noticeRetrySeconds}
        />
      )}
      {sendServer.notice !== null && (
        <ErrorNotice
          message={sendServer.notice.message}
          correlationId={sendServer.notice.correlationId}
          retrySecondsLeft={sendServer.noticeRetrySeconds}
        />
      )}
      <SubmitButton
        label="Verify phone number"
        pendingLabel="Checking…"
        pending={confirming.pending}
        waitSeconds={confirmServer.retrySecondsLeft}
      />
      <div className="flex gap-4 text-sm">
        <button
          type="button"
          onClick={() => {
            send();
          }}
          disabled={resend.secondsLeft > 0 || sending.pending || sendServer.retrySecondsLeft > 0}
          className="font-medium text-teal-700 underline disabled:text-slate-400 disabled:no-underline"
        >
          {resend.secondsLeft > 0 ? `Send a new code in ${String(resend.secondsLeft)}s` : 'Send a new code'}
        </button>
        <button
          type="button"
          onClick={() => {
            setSent(null);
            confirmServer.clear();
            sendServer.clear();
          }}
          className="font-medium text-slate-700 underline"
        >
          Use a different number
        </button>
      </div>
    </form>
  );
}

function PhotoStep({
  overview,
  onUploaded,
}: {
  overview: VerificationOverview;
  onUploaded: (overview: VerificationOverview) => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const server = useRequestFailure(NO_FIELDS);
  const { pending, run } = useSubmission();

  function upload(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = validatePhoto(file);
    setLocalError(problem);
    server.clear();
    if (problem !== null || file === null) {
      return;
    }
    const chosen = file;
    const form = event.currentTarget;
    void run(async () => {
      try {
        onUploaded(await verificationApi.uploadProfilePhoto(chosen));
        setFile(null);
        form.reset();
      } catch (error) {
        server.report(toApiFailure(error));
      }
    });
  }

  return (
    <form noValidate onSubmit={upload} className="space-y-3">
      {overview.profilePhoto === null ? (
        <p className="text-sm text-slate-700">No profile photo yet. The selfie check compares against it.</p>
      ) : (
        <img
          src={overview.profilePhoto.src}
          alt="Your profile photo"
          className="h-32 w-32 rounded-md object-cover"
        />
      )}
      {overview.tier === 'SELFIE_MATCH' && (
        <p className="text-sm text-slate-600">
          A new photo means taking the selfie check again: your current match is for this photo.
        </p>
      )}
      <FormField id="profile-photo" label="Choose a photo" hint="JPEG or PNG, up to 5 MB." error={localError ?? undefined}>
        {(control) => (
          <input
            {...control}
            type="file"
            accept="image/jpeg,image/png"
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              setFile(event.target.files?.[0] ?? null);
              setLocalError(null);
            }}
            className="mt-1 block text-sm"
          />
        )}
      </FormField>
      {server.notice !== null && (
        <ErrorNotice
          message={server.notice.message}
          correlationId={server.notice.correlationId}
          retrySecondsLeft={server.noticeRetrySeconds}
        />
      )}
      <SubmitButton
        label={overview.profilePhoto === null ? 'Upload photo' : 'Replace photo'}
        pendingLabel="Uploading…"
        pending={pending}
        waitSeconds={server.retrySecondsLeft}
      />
    </form>
  );
}

function SelfieStep({
  overview,
  onSubmitted,
}: {
  overview: VerificationOverview;
  onSubmitted: (overview: VerificationOverview) => void;
}) {
  if (overview.tier === 'SELFIE_MATCH') {
    return <p className="text-sm text-slate-700">Your selfie matches your profile photo.</p>;
  }
  if (overview.selfie.status === 'PENDING') {
    return (
      <p role="status" className="text-sm text-slate-700">
        Your selfie is waiting for a reviewer. The result will show here; it usually takes less than a day.
      </p>
    );
  }
  if (!overview.phone.verified || overview.profilePhoto === null) {
    return (
      <p className="text-sm text-slate-700">
        Verify your phone number and add a profile photo first. The selfie is compared against your photo.
      </p>
    );
  }
  return (
    <>
      <p className="text-sm text-slate-700">
        Two quick photos from your camera, a few seconds apart. Your selfie is deleted after 7 days whatever the
        result.
      </p>
      {overview.selfie.status === 'REJECTED' && overview.selfie.rejectionReason !== null && (
        <p role="alert" className="mt-2 text-sm text-red-800">
          {REJECTION_MESSAGES[overview.selfie.rejectionReason]}
        </p>
      )}
      <SelfieCheck onSubmitted={onSubmitted} />
    </>
  );
}

function VouchStep({ overview }: { overview: VerificationOverview }) {
  const userId = useAuthStore((store) => store.user?.id ?? '');
  return (
    <div className="space-y-2 text-sm text-slate-700">
      <p>
        {overview.vouches.received === 1
          ? '1 person vouches for you.'
          : `${String(overview.vouches.received)} people vouch for you.`}
      </p>
      <p>
        Your TripMates ID, for a friend who wants to vouch for you:{' '}
        <code className="font-mono select-all text-slate-900">{userId}</code>
      </p>
      {overview.vouches.canVouch ? (
        <Link to="/account/vouches" className="font-medium text-teal-700 underline">
          Vouch for a friend ({overview.vouches.activeGiven} of {overview.vouches.limit} used)
        </Link>
      ) : (
        <p>Once your selfie matches, you can vouch for friends you know in person.</p>
      )}
    </div>
  );
}
