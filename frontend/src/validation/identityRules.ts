import { GENDERS, type Gender, type LoginBody, type RegistrationBody } from '../api/identityApi';

/**
 * Client-side copies of the backend's identity rules, for instant feedback
 * only. The server checks every one of them again, plus the breached-password
 * check, which deliberately has no copy here.
 *
 * Lengths use String.length, which counts UTF-16 code units exactly as
 * Java's String.length() does, so a limit means the same thing on both sides.
 */

/** RegistrationRequest/LoginRequest: @Size(max = 254) on email. */
export const EMAIL_MAX_LENGTH = 254;
/** RegistrationRequest: @Size(min = 12, max = 128) on password. */
export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_LENGTH = 128;
/** RegistrationRequest: @Size(max = 100) on fullName. */
export const FULL_NAME_MAX_LENGTH = 100;
/** AgePolicy.MINIMUM_AGE_YEARS. */
export const MINIMUM_AGE_YEARS = 18;
/** AgePolicy.LEGAL_ZONE: "today" is the date in India, wherever the browser is. */
export const LEGAL_TIME_ZONE = 'Asia/Kolkata';
/** PasswordPolicy.MIN_PERSONAL_TOKEN_LENGTH. */
const MIN_PERSONAL_TOKEN_LENGTH = 4;
/** PasswordPolicy.NAME_SEPARATORS. */
const NAME_SEPARATORS = /[\s.\-_'+]+/;

/**
 * Deliberately looser than the server's @Email: one @, something either side,
 * no spaces. A stricter client rule would reject addresses the server
 * accepts; anything this lets through, the server's 400 catches.
 */
const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+$/;
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const VERIFICATION_CODE = /^\d{6}$/;

/** Server wording, so the client and server never disagree about the same mistake. */
export const UNDERAGE_MESSAGE = 'You must be at least 18 years old to use TripMates.';
export const PERSONAL_INFO_MESSAGE = 'The password must not contain your name or email address.';

export type FieldErrors<F extends string> = Partial<Record<F, string>>;

/** The signup form as typed; gender is empty until one is chosen. */
export interface SignupForm {
  email: string;
  password: string;
  fullName: string;
  dateOfBirth: string;
  gender: Gender | '';
}

export type SignupField = keyof SignupForm;
export type LoginField = keyof LoginBody;

export function validateSignup(
  form: SignupForm,
  todayInLegalZone: string,
): { errors: FieldErrors<SignupField>; body: RegistrationBody | null } {
  const email = form.email.trim();
  const fullName = form.fullName.trim();
  const errors: FieldErrors<SignupField> = {};

  const emailError = checkEmail(email);
  if (emailError !== null) {
    errors.email = emailError;
  }

  if (fullName === '') {
    errors.fullName = 'Enter your full name.';
  } else if (fullName.length > FULL_NAME_MAX_LENGTH) {
    errors.fullName = `Use at most ${String(FULL_NAME_MAX_LENGTH)} characters.`;
  } else if (hasControlCharacter(fullName)) {
    errors.fullName = 'The name contains a character that is not allowed.';
  }

  if (form.password.length < PASSWORD_MIN_LENGTH) {
    errors.password = `Use at least ${String(PASSWORD_MIN_LENGTH)} characters.`;
  } else if (form.password.length > PASSWORD_MAX_LENGTH) {
    errors.password = `Use at most ${String(PASSWORD_MAX_LENGTH)} characters.`;
  } else if (containsPersonalInfo(form.password, email, fullName)) {
    errors.password = PERSONAL_INFO_MESSAGE;
  }

  if (form.dateOfBirth === '') {
    errors.dateOfBirth = 'Enter your date of birth.';
  } else if (!isRealIsoDate(form.dateOfBirth)) {
    errors.dateOfBirth = 'Enter a valid date.';
  } else if (form.dateOfBirth > latestAdultBirthDate(todayInLegalZone)) {
    errors.dateOfBirth = UNDERAGE_MESSAGE;
  }

  const gender = GENDERS.find((candidate) => candidate === form.gender);
  if (gender === undefined) {
    errors.gender = 'Choose an option.';
  }

  if (Object.keys(errors).length > 0 || gender === undefined) {
    return { errors, body: null };
  }
  return {
    errors,
    body: { email, password: form.password, fullName, dateOfBirth: form.dateOfBirth, gender },
  };
}

/**
 * Shape and length only, as on the server. No minimum password length:
 * enforcing the signup rule here would only tell someone guessing which
 * passwords are worth trying.
 */
export function validateLogin(form: LoginBody): { errors: FieldErrors<LoginField>; body: LoginBody | null } {
  const email = form.email.trim();
  const errors: FieldErrors<LoginField> = {};

  const emailError = checkEmail(email);
  if (emailError !== null) {
    errors.email = emailError;
  }
  if (form.password === '') {
    errors.password = 'Enter your password.';
  } else if (form.password.length > PASSWORD_MAX_LENGTH) {
    errors.password = `Use at most ${String(PASSWORD_MAX_LENGTH)} characters.`;
  }

  if (Object.keys(errors).length > 0) {
    return { errors, body: null };
  }
  return { errors, body: { email, password: form.password } };
}

/** VerifyEmailRequest: @Pattern("\\d{6}"). */
export function validateVerificationCode(code: string): string | null {
  return VERIFICATION_CODE.test(code) ? null : 'Enter the 6-digit code from the email.';
}

/** How a second factor is entered: the authenticator app's current code, or one backup code. */
export type SecondFactorMethod = 'totp' | 'backup';

/** SecondFactorLoginRequest and TwoFactorChangeRequest: @Size(max = 32) on code. */
export const SECOND_FACTOR_CODE_MAX_LENGTH = 32;

const TOTP_CODE = /^\d{6}$/;

/** BackupCodes.NORMALISED: ten characters of Crockford's Base32. */
const BACKUP_CODE = /^[0-9A-HJKMNP-TV-Z]{10}$/;

/**
 * The backend's reading of a typed backup code: case, spaces and the hyphen
 * ignored, O read as 0 and I or L as 1. Used to check the shape only; the
 * code is sent as typed and the server normalises it again.
 */
export function normaliseBackupCode(code: string): string {
  return code.toUpperCase().replace(/[-\s]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
}

export function validateSecondFactorCode(code: string, method: SecondFactorMethod): string | null {
  const trimmed = code.trim();
  if (method === 'totp') {
    return TOTP_CODE.test(trimmed) ? null : 'Enter the 6-digit code from your authenticator app.';
  }
  return BACKUP_CODE.test(normaliseBackupCode(trimmed))
    ? null
    : 'Enter one of your backup codes: 10 letters and digits, like 7KQ2M-X9PDA.';
}

/** TwoFactorConfirmRequest: @Pattern("\\d{6}"). */
export function validateTotpCode(code: string): string | null {
  return validateSecondFactorCode(code, 'totp');
}

/** TwoFactorSetupRequest and TwoFactorChangeRequest: @NotEmpty @Size(max = 128) on password. */
export function validateCurrentPassword(password: string): string | null {
  if (password === '') {
    return 'Enter your password.';
  }
  return password.length > PASSWORD_MAX_LENGTH ? `Use at most ${String(PASSWORD_MAX_LENGTH)} characters.` : null;
}

/**
 * TwoFactorChangeRequest's code accepts either kind, so the shape check
 * does too; a backup code entered here is spent like at sign-in.
 */
export function validateAnySecondFactorCode(code: string): string | null {
  if (validateSecondFactorCode(code, 'totp') === null || validateSecondFactorCode(code, 'backup') === null) {
    return null;
  }
  return 'Enter the current code from your authenticator app, or one of your backup codes.';
}

/** Today's date in India as yyyy-MM-dd. */
export function todayIn(timeZone: string, now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

/**
 * The latest birth date that is 18 or over today: today minus 18 years,
 * clamped to the end of the month as PostgreSQL clamps it. That is the same
 * direction AgePolicy and the ck_users_adult constraint compare in, so on
 * 2026-02-28 someone born 2008-02-29 is still 17 here as well as there.
 */
export function latestAdultBirthDate(todayIso: string): string {
  const match = ISO_DATE.exec(todayIso);
  if (match === null) {
    throw new Error(`Not an ISO date: ${todayIso}`);
  }
  const year = Number(match[1]) - MINIMUM_AGE_YEARS;
  const month = Number(match[2]);
  const day = Math.min(Number(match[3]), daysInMonth(year, month));
  return `${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}`;
}

/** PasswordPolicy.containsPersonalInfo, same tokens and the same 4-character floor. */
export function containsPersonalInfo(password: string, email: string, fullName: string): boolean {
  const haystack = password.toLowerCase();
  const localPart = email.slice(0, Math.max(email.indexOf('@'), 0)).toLowerCase();
  if (localPart.length >= MIN_PERSONAL_TOKEN_LENGTH && haystack.includes(localPart)) {
    return true;
  }
  return fullName
    .toLowerCase()
    .split(NAME_SEPARATORS)
    .some((part) => part.length >= MIN_PERSONAL_TOKEN_LENGTH && haystack.includes(part));
}

function checkEmail(email: string): string | null {
  if (email === '') {
    return 'Enter your email address.';
  }
  if (email.length > EMAIL_MAX_LENGTH) {
    return `Use at most ${String(EMAIL_MAX_LENGTH)} characters.`;
  }
  return EMAIL_SHAPE.test(email) ? null : 'Enter a valid email address, like name@example.com.';
}

/** Java's \p{Cntrl}: U+0000 to U+001F and U+007F. */
function hasControlCharacter(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const unit = value.charCodeAt(i);
    if (unit <= 0x1f || unit === 0x7f) {
      return true;
    }
  }
  return false;
}

function isRealIsoDate(value: string): boolean {
  const match = ISO_DATE.exec(value);
  if (match === null) {
    return false;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth(year, month);
}

function daysInMonth(year: number, month: number): number {
  // setUTCFullYear rather than Date.UTC, which maps years 0-99 to 1900-1999.
  const date = new Date(0);
  date.setUTCFullYear(year, month, 0);
  return date.getUTCDate();
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, '0');
}
