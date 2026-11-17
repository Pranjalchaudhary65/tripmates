import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { GENDERS, type Gender } from '../api/identityApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { useAuthStore } from '../stores/authStore';
import {
  FULL_NAME_MAX_LENGTH,
  LEGAL_TIME_ZONE,
  latestAdultBirthDate,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  todayIn,
  validateSignup,
  type FieldErrors,
  type SignupField,
  type SignupForm,
} from '../validation/identityRules';

const SIGNUP_FIELDS: readonly SignupField[] = ['email', 'password', 'fullName', 'dateOfBirth', 'gender'];

const GENDER_LABELS: Record<Gender, string> = {
  MALE: 'Male',
  FEMALE: 'Female',
  NON_BINARY: 'Non-binary',
  PREFER_NOT_TO_SAY: 'Prefer not to say',
};

const EMPTY_FORM: SignupForm = { email: '', password: '', fullName: '', dateOfBirth: '', gender: '' };

export function SignupPage() {
  const navigate = useNavigate();
  const register = useAuthStore((state) => state.register);
  const [form, setForm] = useState<SignupForm>(EMPTY_FORM);
  const [clientErrors, setClientErrors] = useState<FieldErrors<SignupField>>({});
  const [latestBirthDate] = useState(() => latestAdultBirthDate(todayIn(LEGAL_TIME_ZONE)));
  const { pending, run } = useSubmission();
  const server = useRequestFailure(SIGNUP_FIELDS);

  function update(field: SignupField) {
    return (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      setForm((current) => ({ ...current, [field]: event.target.value }));
      setClientErrors((current) => ({ ...current, [field]: undefined }));
      server.clearField(field);
    };
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (server.retrySecondsLeft > 0) {
      return;
    }
    void run(async () => {
      const { errors, body } = validateSignup(form, todayIn(LEGAL_TIME_ZONE));
      setClientErrors(errors);
      if (body === null) {
        server.clear();
        return;
      }
      const result = await register(body);
      if (result.ok) {
        void navigate('/verify-email');
      } else {
        server.report(result.failure);
      }
    });
  }

  const errorFor = (field: SignupField) => clientErrors[field] ?? server.fieldErrors[field];

  return (
    <section className="mx-auto max-w-md">
      <h1 className="text-2xl font-bold text-teal-800">Create your account</h1>
      <p className="mt-2 text-sm text-slate-600">
        Every TripMates member is a verified adult. We will email you a code to confirm your address.
      </p>

      <form noValidate onSubmit={handleSubmit} className="mt-6 space-y-4">
        {server.notice !== null && (
          <ErrorNotice
            message={server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
        )}

        <FormField id="signup-email" label="Email" error={errorFor('email')}>
          {(control) => (
            <input
              {...control}
              type="email"
              name="email"
              autoComplete="email"
              value={form.email}
              onChange={update('email')}
              className={inputClassName}
            />
          )}
        </FormField>

        <FormField id="signup-full-name" label="Full name" error={errorFor('fullName')}>
          {(control) => (
            <input
              {...control}
              type="text"
              name="fullName"
              autoComplete="name"
              maxLength={FULL_NAME_MAX_LENGTH}
              value={form.fullName}
              onChange={update('fullName')}
              className={inputClassName}
            />
          )}
        </FormField>

        <FormField
          id="signup-password"
          label="Password"
          error={errorFor('password')}
          hint={`At least ${String(PASSWORD_MIN_LENGTH)} characters. A few unrelated words make a strong one. Avoid your name or email.`}
        >
          {(control) => (
            <input
              {...control}
              type="password"
              name="password"
              autoComplete="new-password"
              maxLength={PASSWORD_MAX_LENGTH}
              value={form.password}
              onChange={update('password')}
              className={inputClassName}
            />
          )}
        </FormField>

        <FormField
          id="signup-date-of-birth"
          label="Date of birth"
          error={errorFor('dateOfBirth')}
          hint="You must be 18 or older."
        >
          {(control) => (
            <input
              {...control}
              type="date"
              name="dateOfBirth"
              autoComplete="bday"
              max={latestBirthDate}
              value={form.dateOfBirth}
              onChange={update('dateOfBirth')}
              className={inputClassName}
            />
          )}
        </FormField>

        <FormField id="signup-gender" label="Gender" error={errorFor('gender')}>
          {(control) => (
            <select
              {...control}
              name="gender"
              value={form.gender}
              onChange={update('gender')}
              className={inputClassName}
            >
              <option value="" disabled>
                Choose one
              </option>
              {GENDERS.map((gender) => (
                <option key={gender} value={gender}>
                  {GENDER_LABELS[gender]}
                </option>
              ))}
            </select>
          )}
        </FormField>

        <SubmitButton
          label="Create account"
          pendingLabel="Creating account…"
          pending={pending}
          waitSeconds={server.retrySecondsLeft}
        />
      </form>

      <p className="mt-6 text-sm text-slate-600">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-teal-700 underline">
          Log in
        </Link>
      </p>
    </section>
  );
}
