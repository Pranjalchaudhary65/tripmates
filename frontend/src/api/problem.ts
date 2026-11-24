import axios from 'axios';

/**
 * What a screen needs to know about a failed request, already reduced to
 * text that is safe to show. Nothing from the server reaches the user
 * except details whose code is listed in SERVER_WORDED_CODES and field
 * messages placed next to their input.
 */
export interface ApiFailure {
  kind: 'problem' | 'network' | 'timeout' | 'unexpected';
  /** HTTP status, or null when no response arrived. */
  status: number | null;
  /** The problem's stable machine-readable code, e.g. invalid_credentials. */
  code: string | null;
  /** The one sentence to show in the generic error display. */
  message: string;
  /** Messages for named request fields, keyed by the DTO field name. */
  fieldErrors: Partial<Record<string, string[]>>;
  /** Validation messages that belong to the request as a whole. */
  formErrors: string[];
  /** Links what the user sees to the server log entry. */
  correlationId: string | null;
  /** Whole seconds until a retry can succeed; set for 429 and for a 503 that says so. */
  retryAfterSeconds: number | null;
}

/**
 * Codes whose server detail is shown word for word. Every one is a fixed
 * sentence in the backend's ProblemType enums, written for end users and
 * identical for every account, so showing it reveals nothing the request
 * did not already decide. A code not listed here falls back to the status
 * wording below, so a new server message can never surface unreviewed.
 */
const SERVER_WORDED_CODES: ReadonlySet<string> = new Set([
  'underage',
  'password_breached',
  'password_contains_personal_info',
  'invalid_verification_code',
  'invalid_credentials',
  'captcha_required',
  'temporarily_unavailable',
  'invalid_second_factor',
  'login_challenge_expired',
  'reauthentication_failed',
  'invalid_totp_code',
  'two_factor_already_enabled',
  'two_factor_not_enabled',
  'two_factor_setup_not_started',
  'two_factor_required_for_admin',
  'invalid_phone_number',
  'phone_already_verified',
  'phone_in_use',
  'invalid_phone_code',
  'phone_verification_required',
  'profile_photo_required',
  'selfie_already_verified',
  'selfie_review_pending',
  'invalid_image',
  'liveness_challenge_invalid',
  'vouch_requires_selfie_match',
  'vouch_limit_reached',
  'already_vouching',
  'cannot_vouch_for_self',
  'verification_already_decided',
  'cannot_review_own_verification',
  'invalid_quiz_answer',
  'quiz_incomplete',
  'vibe_profile_missing',
  'invalid_trip_dates',
  'invalid_location_filter',
  'invalid_date_filter',
  'too_many_trips',
  'own_trip',
  'join_request_exists',
  'too_many_pending_requests',
  'trip_full',
  'trip_closed',
  'join_request_decided',
  'trip_busy',
  'deal_breaker_conflict',
  'empty_message',
  'message_too_long',
  'conversation_closed',
  'message_id_reused',
  'contact_locked',
]);

/**
 * rate_limited is the exception among known codes, and so is every other 429
 * (phone_code_cooldown, phone_code_daily_limit): their server detail tells
 * an API client to read the Retry-After header, which means nothing to a
 * person. The screen shows the wait itself instead.
 */
export const RATE_LIMITED_MESSAGE = 'Too many attempts.';

/**
 * Used when a 429 arrives without a readable Retry-After, such as through a
 * proxy that strips it. Long enough not to invite an immediate repeat of the
 * refused request.
 */
export const FALLBACK_RETRY_AFTER_SECONDS = 60;

/** No sensible limit is a day long; anything beyond this is a broken header. */
const MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60;

const MESSAGES = {
  fieldsInvalid: 'Some details need fixing. Check the highlighted fields.',
  badRequest: 'The request could not be processed. Please try again.',
  unauthorized: 'You need to sign in to do that.',
  forbidden: 'You do not have permission to do that.',
  notFound: 'That could not be found.',
  tooLarge: 'That file is too large.',
  unavailable: 'TripMates is temporarily unavailable. Please try again shortly.',
  serverError: 'Something went wrong on our side. Please try again.',
  network: 'Could not reach TripMates. Check your internet connection and try again.',
  timeout: 'TripMates took too long to respond. Please try again.',
  unexpected: 'Something went wrong. Please try again.',
} as const;

/** Same rule the backend applies to inbound IDs, so nothing odd is ever rendered as a reference. */
const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

/** IMF-fixdate, the HTTP-date form senders must use, e.g. "Tue, 17 Nov 2026 10:00:45 GMT". */
const HTTP_DATE = /^[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT$/;

const TIMEOUT_CODES: ReadonlySet<string> = new Set(['ECONNABORTED', 'ETIMEDOUT']);

/** Turns anything a request can throw into an ApiFailure. Never throws. */
export function toApiFailure(error: unknown, now: number = Date.now()): ApiFailure {
  if (!axios.isAxiosError(error)) {
    return emptyFailure('unexpected', MESSAGES.unexpected);
  }
  const response = error.response;
  if (response === undefined) {
    return TIMEOUT_CODES.has(error.code ?? '')
      ? emptyFailure('timeout', MESSAGES.timeout)
      : emptyFailure('network', MESSAGES.network);
  }

  const body = asRecord(response.data);
  const status = response.status;
  const code = typeof body?.['code'] === 'string' ? body['code'] : null;
  const { fieldErrors, formErrors } = readViolations(body?.['errors']);
  const hasFieldErrors = Object.keys(fieldErrors).length > 0;

  let retryAfterSeconds = parseRetryAfter(headerValue(response.headers, 'retry-after'), now);
  if (status === 429) {
    retryAfterSeconds ??= FALLBACK_RETRY_AFTER_SECONDS;
  } else if (status !== 503) {
    retryAfterSeconds = null;
  }

  return {
    kind: 'problem',
    status,
    code,
    message: messageFor(status, code, body?.['detail'], hasFieldErrors),
    fieldErrors,
    formErrors,
    correlationId:
      readCorrelationId(body?.['correlationId']) ??
      readCorrelationId(headerValue(response.headers, 'x-correlation-id')),
    retryAfterSeconds,
  };
}

/** What a form renders for a failure: messages beside its own inputs, and at most one notice. */
export interface FormFailureView<F extends string> {
  fieldErrors: Partial<Record<F, string>>;
  notice: { message: string; correlationId: string | null } | null;
}

export const CHECK_FIELDS_MESSAGE = 'Check the highlighted fields and try again.';

/**
 * Splits a failure between the inputs a form actually shows and its generic
 * error display. When every server message landed beside an input, the
 * notice is only a pointer to them. Anything that cannot be placed (a
 * request-level message, or a field the form has no input for, such as
 * verificationId) is not shown raw: the form gets the generic wording and
 * the correlation ID instead.
 */
export function describeFailure<F extends string>(
  failure: ApiFailure | null,
  formFields: readonly F[],
): FormFailureView<F> {
  if (failure === null) {
    return { fieldErrors: {}, notice: null };
  }
  const fieldErrors: Partial<Record<F, string>> = {};
  let unplaced = failure.formErrors.length;
  for (const [field, messages] of Object.entries(failure.fieldErrors)) {
    const formField = formFields.find((candidate) => candidate === field);
    const first = messages?.[0];
    if (formField !== undefined && first !== undefined) {
      fieldErrors[formField] = first;
    } else {
      unplaced++;
    }
  }

  const placed = Object.keys(fieldErrors).length;
  if (placed > 0 && unplaced === 0) {
    return { fieldErrors, notice: { message: CHECK_FIELDS_MESSAGE, correlationId: null } };
  }
  const message = unplaced > 0 && failure.status === 400 ? MESSAGES.badRequest : failure.message;
  return { fieldErrors, notice: { message, correlationId: failure.correlationId } };
}

function messageFor(status: number, code: string | null, detail: unknown, hasFieldErrors: boolean): string {
  if (code === 'rate_limited' || status === 429) {
    return RATE_LIMITED_MESSAGE;
  }
  if (code !== null && SERVER_WORDED_CODES.has(code) && typeof detail === 'string' && detail.trim() !== '') {
    return detail;
  }
  if (status === 400) {
    return hasFieldErrors ? MESSAGES.fieldsInvalid : MESSAGES.badRequest;
  }
  if (status === 401) {
    return MESSAGES.unauthorized;
  }
  if (status === 403) {
    return MESSAGES.forbidden;
  }
  if (status === 404) {
    return MESSAGES.notFound;
  }
  if (status === 413) {
    return MESSAGES.tooLarge;
  }
  if (status === 503) {
    return MESSAGES.unavailable;
  }
  return status >= 500 ? MESSAGES.serverError : MESSAGES.badRequest;
}

function readViolations(raw: unknown): Pick<ApiFailure, 'fieldErrors' | 'formErrors'> {
  const fieldErrors: Partial<Record<string, string[]>> = {};
  const formErrors: string[] = [];
  if (!Array.isArray(raw)) {
    return { fieldErrors, formErrors };
  }
  for (const entry of raw) {
    const violation = asRecord(entry);
    const message = violation?.['message'];
    if (typeof message !== 'string' || message.trim() === '') {
      continue;
    }
    const field = violation?.['field'];
    if (typeof field === 'string' && field !== '') {
      (fieldErrors[field] ??= []).push(message);
    } else {
      formErrors.push(message);
    }
  }
  return { fieldErrors, formErrors };
}

/**
 * Retry-After is either delta-seconds or an HTTP date (RFC 9110). The
 * backend sends seconds; the date form is accepted so a proxy rewriting it
 * does not break the countdown.
 */
export function parseRetryAfter(raw: string | null, now: number): number | null {
  if (raw === null) {
    return null;
  }
  const value = raw.trim();
  let seconds: number;
  if (/^\d+$/.test(value)) {
    seconds = Number(value);
  } else {
    // Date.parse alone accepts far too much ("-5" parses as a year).
    if (!HTTP_DATE.test(value)) {
      return null;
    }
    const at = Date.parse(value);
    if (Number.isNaN(at)) {
      return null;
    }
    seconds = Math.ceil((at - now) / 1000);
  }
  if (!Number.isFinite(seconds)) {
    return null;
  }
  return Math.min(Math.max(seconds, 1), MAX_RETRY_AFTER_SECONDS);
}

function readCorrelationId(raw: unknown): string | null {
  return typeof raw === 'string' && CORRELATION_ID_PATTERN.test(raw) ? raw : null;
}

function headerValue(headers: unknown, name: string): string | null {
  const record = asRecord(headers);
  if (record === null) {
    return null;
  }
  const getter = record['get'];
  const value: unknown = typeof getter === 'function' ? getter.call(headers, name) : record[name];
  return typeof value === 'string' ? value : null;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null;
}

function emptyFailure(kind: ApiFailure['kind'], message: string): ApiFailure {
  return {
    kind,
    status: null,
    code: null,
    message,
    fieldErrors: {},
    formErrors: [],
    correlationId: null,
    retryAfterSeconds: null,
  };
}
