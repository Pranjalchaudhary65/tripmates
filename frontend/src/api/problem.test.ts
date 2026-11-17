import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createApiClient } from './createApiClient';
import {
  CHECK_FIELDS_MESSAGE,
  describeFailure,
  FALLBACK_RETRY_AFTER_SECONDS,
  parseRetryAfter,
  RATE_LIMITED_MESSAGE,
  toApiFailure,
  type ApiFailure,
} from './problem';

const BASE_URL = 'http://api.test/api/v1';
const URL = `${BASE_URL}/thing`;
const CORRELATION_ID = '7f3c2a9e-1b4d-4c55-9f00-0a1b2c3d4e5f';
const PROBLEM_JSON = { 'Content-Type': 'application/problem+json' };

const server = setupServer();
const api = createApiClient(BASE_URL);

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

async function failureFor(response: () => Response): Promise<ApiFailure> {
  server.use(http.post(URL, response));
  try {
    await api.post('/thing', {});
  } catch (error) {
    return toApiFailure(error);
  }
  throw new Error('Expected the request to fail.');
}

describe('toApiFailure', () => {
  it('shows the server wording for a known code, with its correlation ID', async () => {
    const detail =
      'The email or password is incorrect, or the email address has not been verified yet. ' +
      'If you just signed up, use the code we emailed you first.';
    const failure = await failureFor(() =>
      HttpResponse.json(
        { status: 401, title: 'Unauthorized', detail, code: 'invalid_credentials', correlationId: CORRELATION_ID },
        { status: 401, headers: PROBLEM_JSON },
      ),
    );

    expect(failure).toMatchObject({
      kind: 'problem',
      status: 401,
      code: 'invalid_credentials',
      message: detail,
      correlationId: CORRELATION_ID,
      retryAfterSeconds: null,
    });
  });

  it('never shows the detail of a code it does not know', async () => {
    const failure = await failureFor(() =>
      HttpResponse.json(
        { status: 409, detail: 'Row 42 of table users is locked by pid 1234', code: 'something_new' },
        { status: 409, headers: PROBLEM_JSON },
      ),
    );

    expect(failure.message).not.toContain('pid');
    expect(failure.message).toBe('The request could not be processed. Please try again.');
  });

  it('never shows the detail of a 500', async () => {
    const failure = await failureFor(() =>
      HttpResponse.json(
        { status: 500, detail: 'NullPointerException at UserService.java:88', correlationId: CORRELATION_ID },
        { status: 500, headers: PROBLEM_JSON },
      ),
    );

    expect(failure.message).toBe('Something went wrong on our side. Please try again.');
    expect(failure.correlationId).toBe(CORRELATION_ID);
  });

  it('collects field errors by field and keeps request-level ones apart', async () => {
    const failure = await failureFor(() =>
      HttpResponse.json(
        {
          status: 400,
          detail: 'The request is malformed or failed validation.',
          errors: [
            { field: 'email', message: 'must be a well-formed email address' },
            { field: 'password', message: 'must be between 12 and 128 characters' },
            { field: 'password', message: 'must not be null' },
            { field: null, message: 'dates are inconsistent' },
            { field: 'gender', message: 42 },
          ],
        },
        { status: 400, headers: PROBLEM_JSON },
      ),
    );

    expect(failure.fieldErrors).toEqual({
      email: ['must be a well-formed email address'],
      password: ['must be between 12 and 128 characters', 'must not be null'],
    });
    expect(failure.formErrors).toEqual(['dates are inconsistent']);
  });

  it('turns a 429 into a wait read from Retry-After, not the header-reading server text', async () => {
    const failure = await failureFor(() =>
      HttpResponse.json(
        {
          status: 429,
          code: 'rate_limited',
          detail: 'Too many attempts. Wait for the number of seconds in the Retry-After header, then try again.',
        },
        { status: 429, headers: { ...PROBLEM_JSON, 'Retry-After': '37' } },
      ),
    );

    expect(failure.message).toBe(RATE_LIMITED_MESSAGE);
    expect(failure.retryAfterSeconds).toBe(37);
  });

  it('falls back to a fixed wait when a 429 has no readable Retry-After', async () => {
    const failure = await failureFor(() =>
      HttpResponse.json({ status: 429, code: 'rate_limited' }, { status: 429, headers: PROBLEM_JSON }),
    );

    expect(failure.retryAfterSeconds).toBe(FALLBACK_RETRY_AFTER_SECONDS);
  });

  it('keeps the Retry-After of a 503 and shows its server wording', async () => {
    const failure = await failureFor(() =>
      HttpResponse.json(
        {
          status: 503,
          code: 'temporarily_unavailable',
          detail: 'This action is temporarily unavailable. Try again shortly.',
        },
        { status: 503, headers: { ...PROBLEM_JSON, 'Retry-After': '30' } },
      ),
    );

    expect(failure.message).toBe('This action is temporarily unavailable. Try again shortly.');
    expect(failure.retryAfterSeconds).toBe(30);
  });

  it('ignores Retry-After on statuses where it means nothing to the form', async () => {
    const failure = await failureFor(() =>
      HttpResponse.json({ status: 400 }, { status: 400, headers: { ...PROBLEM_JSON, 'Retry-After': '30' } }),
    );

    expect(failure.retryAfterSeconds).toBeNull();
  });

  it('reads the correlation ID from the header when the body has none, and rejects odd values', async () => {
    const fromHeader = await failureFor(
      () => new HttpResponse('<html>Bad gateway</html>', { status: 502, headers: { 'X-Correlation-Id': 'abc-123' } }),
    );
    expect(fromHeader.correlationId).toBe('abc-123');
    expect(fromHeader.message).toBe('Something went wrong on our side. Please try again.');

    const hostile = await failureFor(() =>
      HttpResponse.json({ status: 500, correlationId: '<img src=x onerror=alert(1)>' }, { status: 500 }),
    );
    expect(hostile.correlationId).toBeNull();
  });

  it('reports a network failure without a status', async () => {
    const failure = await failureFor(() => HttpResponse.error());

    expect(failure).toMatchObject({ kind: 'network', status: null, correlationId: null });
    expect(failure.message).toBe('Could not reach TripMates. Check your internet connection and try again.');
  });

  it('treats anything that is not an Axios error as unexpected', () => {
    const failure = toApiFailure(new TypeError("Cannot read properties of undefined (reading 'id')"));

    expect(failure.kind).toBe('unexpected');
    expect(failure.message).toBe('Something went wrong. Please try again.');
  });
});

describe('parseRetryAfter', () => {
  const now = Date.parse('2026-11-17T10:00:00Z');

  it('accepts delta-seconds and HTTP dates', () => {
    expect(parseRetryAfter('120', now)).toBe(120);
    expect(parseRetryAfter('Tue, 17 Nov 2026 10:00:45 GMT', now)).toBe(45);
  });

  it('never returns zero or a negative wait, and caps absurd ones', () => {
    expect(parseRetryAfter('0', now)).toBe(1);
    expect(parseRetryAfter('Tue, 17 Nov 2026 09:00:00 GMT', now)).toBe(1);
    expect(parseRetryAfter('99999999', now)).toBe(86_400);
  });

  it('rejects junk', () => {
    expect(parseRetryAfter(null, now)).toBeNull();
    expect(parseRetryAfter('soon', now)).toBeNull();
    expect(parseRetryAfter('-5', now)).toBeNull();
  });
});

describe('describeFailure', () => {
  const base: ApiFailure = {
    kind: 'problem',
    status: 400,
    code: null,
    message: 'Some details need fixing. Check the highlighted fields.',
    fieldErrors: {},
    formErrors: [],
    correlationId: CORRELATION_ID,
    retryAfterSeconds: null,
  };

  it('puts field messages beside their inputs and only points at them in the notice', () => {
    const view = describeFailure(
      { ...base, code: 'underage', fieldErrors: { dateOfBirth: ['You must be at least 18 years old to use TripMates.'] } },
      ['email', 'dateOfBirth'],
    );

    expect(view.fieldErrors).toEqual({ dateOfBirth: 'You must be at least 18 years old to use TripMates.' });
    expect(view.notice).toEqual({ message: CHECK_FIELDS_MESSAGE, correlationId: null });
  });

  it('does not show a message for a field the form has no input for', () => {
    const view = describeFailure({ ...base, fieldErrors: { verificationId: ['must not be null'] } }, ['code']);

    expect(view.fieldErrors).toEqual({});
    expect(view.notice).toEqual({
      message: 'The request could not be processed. Please try again.',
      correlationId: CORRELATION_ID,
    });
  });

  it('shows a failure with no field errors in the notice with its correlation ID', () => {
    const view = describeFailure({ ...base, status: 503, message: 'Unavailable.' }, ['email']);

    expect(view.notice).toEqual({ message: 'Unavailable.', correlationId: CORRELATION_ID });
  });
});
