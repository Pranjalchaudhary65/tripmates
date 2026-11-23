// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import * as camera from '../camera/camera';
import { useAuthStore } from '../stores/authStore';
import { API_BASE_URL, apiGet, apiPost, apiPut, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn, SIGNED_IN_USER_ID } from '../test/renderApp';
import { overviewBody, PHOTO_PATH } from '../test/verificationFixtures';

vi.mock('../camera/camera', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../camera/camera')>();
  return {
    ...actual,
    openFrontCamera: vi.fn(),
    attachStream: vi.fn(),
    captureFrame: vi.fn(),
    stopStream: vi.fn(),
  };
});

const FAKE_STREAM = { id: 'front-camera' } as unknown as MediaStream;
const CODE_SENT = { verificationId: 'b1f4c6a2-0000-4000-8000-000000000001', maskedNumber: '+91 ••••••• 210', expiresIn: 600, resendAfter: 60 };

const server = createApiServer();

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  server.use(apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')));
  vi.mocked(camera.openFrontCamera).mockResolvedValue(FAKE_STREAM);
  vi.mocked(camera.attachStream).mockResolvedValue(undefined);
  vi.mocked(camera.captureFrame).mockImplementation(() => Promise.resolve(new Blob(['frame'], { type: 'image/jpeg' })));
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

function serveOverview(options: Parameters<typeof overviewBody>[0] = {}) {
  server.use(apiGet('/verification', () => HttpResponse.json(overviewBody(options))));
}

async function openCentre(options: Parameters<typeof overviewBody>[0] = {}) {
  serveOverview(options);
  signIn();
  renderAt('/account/verification');
  await screen.findByRole('progressbar');
}

function section(name: string): HTMLElement {
  const heading = screen.getByRole('heading', { level: 2, name });
  const element = heading.closest('section');
  if (element === null) {
    throw new Error(`No section for ${name}`);
  }
  return element;
}

describe('verification centre', () => {
  it('needs a session', async () => {
    renderAt('/account/verification');

    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeDefined();
  });

  it('shows the tier on the progress bar and brings the session up to date with it', async () => {
    await openCentre({ tier: 'PHONE', maskedNumber: '+91 ••••••• 210' });

    const bar = screen.getByRole('progressbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('2');
    expect(bar.getAttribute('aria-valuetext')).toBe('Phone');
    expect(screen.getByText('Phone', { selector: '[aria-current="step"]' })).toBeDefined();
    expect(within(section('Phone number')).getByText('Verified: +91 ••••••• 210')).toBeDefined();
    await waitFor(() => {
      expect(useAuthStore.getState().user?.verificationTier).toBe('PHONE');
    });
  });

  it('says every step is optional and never asks for an ID document', async () => {
    await openCentre();

    expect(screen.getByText(/Every step is optional/).textContent).toContain('never asks for a government ID');
  });

  it('shows a load failure through the normaliser and retries on request', async () => {
    let attempts = 0;
    server.use(
      apiGet('/verification', () => {
        attempts++;
        return attempts === 1
          ? HttpResponse.json({ status: 500, correlationId: 'corr-500', detail: 'java.lang.NullPointerException' }, { status: 500 })
          : HttpResponse.json(overviewBody());
      }),
    );
    signIn();
    renderAt('/account/verification');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Something went wrong on our side.');
    expect(alert.textContent).toContain('corr-500');
    expect(alert.textContent).not.toContain('NullPointer');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('progressbar')).toBeDefined();
  });
});

describe('phone step', () => {
  it('checks the number before sending anything', async () => {
    let calls = 0;
    server.use(
      apiPost('/verification/phone', () => {
        calls++;
        return HttpResponse.json(CODE_SENT, { status: 202 });
      }),
    );
    await openCentre();

    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));
    expect(await screen.findByText('Enter your mobile number.')).toBeDefined();
    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '12345' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));
    expect(await screen.findByText(/with its country code, such as/)).toBeDefined();
    expect(calls).toBe(0);
  });

  it('sends a code, confirms it and moves the tier up', async () => {
    const bodies: unknown[] = [];
    server.use(
      apiPost('/verification/phone', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(CODE_SENT, { status: 202 });
      }),
      apiPost('/verification/phone/confirm', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(overviewBody({ tier: 'PHONE', maskedNumber: '+91 ••••••• 210' }));
      }),
    );
    await openCentre();

    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '98765 43210' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));
    expect(await screen.findByText(/We sent a 6-digit code to \+91 ••••••• 210/)).toBeDefined();
    expect(screen.getByRole('button', { name: /Send a new code in \d+s/ })).toHaveProperty('disabled', true);
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: '482913' } });
    fireEvent.click(screen.getByRole('button', { name: 'Verify phone number' }));

    expect(await screen.findByText('Verified: +91 ••••••• 210')).toBeDefined();
    expect(bodies).toEqual([
      { phoneNumber: '98765 43210' },
      { verificationId: CODE_SENT.verificationId, code: '482913' },
    ]);
    await waitFor(() => {
      expect(useAuthStore.getState().user?.verificationTier).toBe('PHONE');
    });
  });

  it('places a refused number beside its field', async () => {
    server.use(
      apiPost('/verification/phone', () =>
        HttpResponse.json(
          {
            status: 400,
            code: 'invalid_phone_number',
            detail: 'Enter a mobile number with its country code, such as +91 98765 43210.',
            errors: [{ field: 'phoneNumber', message: 'Enter a mobile number with its country code, such as +91 98765 43210.' }],
          },
          { status: 400 },
        ),
      ),
    );
    await openCentre();

    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '+1 555 0100 000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));

    const input = screen.getByLabelText('Mobile number');
    await waitFor(() => {
      expect(input.getAttribute('aria-invalid')).toBe('true');
    });
  });

  it.each([
    ['phone_already_verified', 409, 'Your phone number is already verified.'],
    ['temporarily_unavailable', 503, 'This action is temporarily unavailable. Try again shortly.'],
  ])('shows %s when sending', async (code, status, detail) => {
    server.use(apiPost('/verification/phone', () => problem(status, code, detail)));
    await openCentre();

    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '9876543210' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
  });

  it('waits out a cooldown before sending again', async () => {
    server.use(
      apiPost('/verification/phone', () =>
        problem(429, 'phone_code_cooldown', 'A code was sent moments ago. Wait for it, or request another after the wait in Retry-After.', {
          headers: { 'Retry-After': '42' },
        }),
      ),
    );
    await openCentre();

    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '9876543210' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Too many attempts.');
    expect(alert.textContent).not.toContain('Retry-After');
    expect(screen.getByRole('button', { name: /Try again in 4\ds/ })).toHaveProperty('disabled', true);
  });

  it('reports a network failure without losing the number', async () => {
    server.use(apiPost('/verification/phone', () => HttpResponse.error()));
    await openCentre();

    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '9876543210' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Could not reach TripMates');
    expect(screen.getByLabelText('Mobile number')).toHaveProperty('value', '9876543210');
  });

  async function reachCodeEntry() {
    server.use(apiPost('/verification/phone', () => HttpResponse.json(CODE_SENT, { status: 202 })));
    await openCentre();
    fireEvent.change(screen.getByLabelText('Mobile number'), { target: { value: '9876543210' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a code' }));
    await screen.findByLabelText('Code');
  }

  it('checks the code shape before sending it', async () => {
    let calls = 0;
    server.use(
      apiPost('/verification/phone/confirm', () => {
        calls++;
        return HttpResponse.json(overviewBody({ tier: 'PHONE' }));
      }),
    );
    await reachCodeEntry();

    fireEvent.change(screen.getByLabelText('Code'), { target: { value: '12ab' } });
    fireEvent.click(screen.getByRole('button', { name: 'Verify phone number' }));

    expect(await screen.findByText('Enter the 6-digit code from the message.')).toBeDefined();
    expect(calls).toBe(0);
  });

  it('puts a wrong code beside the code field and clears it', async () => {
    const detail = 'The code is invalid or has expired. Check the code or request a new one.';
    server.use(
      apiPost('/verification/phone/confirm', () =>
        HttpResponse.json(
          { status: 400, code: 'invalid_phone_code', detail, errors: [{ field: 'code', message: detail }] },
          { status: 400 },
        ),
      ),
    );
    await reachCodeEntry();

    fireEvent.change(screen.getByLabelText('Code'), { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Verify phone number' }));

    expect(await screen.findByText(detail)).toBeDefined();
    expect(screen.getByLabelText('Code')).toHaveProperty('value', '');
  });

  it('says the number belongs to another account only after the right code', async () => {
    const detail = 'This phone number is already verified on another TripMates account.';
    server.use(apiPost('/verification/phone/confirm', () => problem(409, 'phone_in_use', detail)));
    await reachCodeEntry();

    fireEvent.change(screen.getByLabelText('Code'), { target: { value: '482913' } });
    fireEvent.click(screen.getByRole('button', { name: 'Verify phone number' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
  });

  it('goes back to the number to use a different one', async () => {
    await reachCodeEntry();

    fireEvent.click(screen.getByRole('button', { name: 'Use a different number' }));

    expect(screen.getByLabelText('Mobile number')).toBeDefined();
  });
});

describe('profile photo step', () => {
  function choose(file: File) {
    fireEvent.change(screen.getByLabelText('Choose a photo'), { target: { files: [file] } });
  }

  it('refuses other file types and large files before uploading', async () => {
    let calls = 0;
    server.use(
      apiPut('/users/me/profile-photo', () => {
        calls++;
        return HttpResponse.json(overviewBody({ photo: true }));
      }),
    );
    await openCentre();

    choose(new File(['GIF89a'], 'cat.gif', { type: 'image/gif' }));
    fireEvent.click(screen.getByRole('button', { name: 'Upload photo' }));
    expect(await screen.findByText('Choose a JPEG or PNG photo.')).toBeDefined();

    choose(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'big.jpg', { type: 'image/jpeg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Upload photo' }));
    expect(await screen.findByText('Choose a photo under 5 MB.')).toBeDefined();
    expect(calls).toBe(0);
  });

  it('uploads a photo and shows it from the signed link under the API origin', async () => {
    let body = '';
    server.use(
      apiPut('/users/me/profile-photo', async ({ request }) => {
        body = await request.text();
        return HttpResponse.json(overviewBody({ photo: true }));
      }),
    );
    await openCentre();

    choose(new File(['jpeg-bytes'], 'me.jpg', { type: 'image/jpeg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Upload photo' }));

    const image = await screen.findByRole('img', { name: 'Your profile photo' });
    expect(image.getAttribute('src')).toBe(`${API_BASE_URL}${PHOTO_PATH}`);
    expect(body).toContain('name="photo"; filename="photo.jpg"');
    expect(body).toContain('jpeg-bytes');
    expect(screen.getByRole('button', { name: 'Replace photo' })).toBeDefined();
  });

  it.each([
    [400, 'invalid_image', 'The image could not be used. Upload a JPEG or PNG photo, 64 to 4096 pixels on each side.'],
    [413, null, 'That file is too large.'],
  ])('shows a %s refusal', async (status, code, message) => {
    server.use(
      apiPut('/users/me/profile-photo', () =>
        code === null ? HttpResponse.json({ status }, { status }) : problem(status, code, message),
      ),
    );
    await openCentre();

    choose(new File(['jpeg-bytes'], 'me.jpg', { type: 'image/jpeg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Upload photo' }));

    expect((await screen.findByRole('alert')).textContent).toContain(message);
  });

  it('warns that a new photo means a new selfie once the selfie matches', async () => {
    await openCentre({ tier: 'SELFIE_MATCH', photo: true, selfieStatus: 'APPROVED' });

    expect(screen.getByText(/A new photo means taking the selfie check again/)).toBeDefined();
  });

  it('never renders a link the server points somewhere else', async () => {
    server.use(
      apiGet('/verification', () =>
        HttpResponse.json({ ...overviewBody(), profilePhoto: { path: '//evil.example/x.jpg', expiresAt: '2026-11-23T10:05:00Z' } }),
      ),
    );
    signIn();
    renderAt('/account/verification');

    expect((await screen.findByRole('alert')).textContent).toContain('Something went wrong.');
    expect(document.querySelector('img')).toBeNull();
  });
});

describe('selfie step', () => {
  const CHALLENGE = { challengeId: 'c'.repeat(43), prompt: 'TURN_HEAD_LEFT', expiresIn: 120 };

  async function openReady() {
    await openCentre({ tier: 'PHONE', photo: true });
  }

  it('explains what comes first when the phone or photo is missing', async () => {
    await openCentre({ tier: 'EMAIL', photo: true });

    expect(within(section('Selfie check')).getByText(/Verify your phone number and add a profile photo first/)).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Start the selfie check' })).toBeNull();
  });

  it('says a pending selfie is with a reviewer', async () => {
    await openCentre({ tier: 'PHONE', photo: true, selfieStatus: 'PENDING' });

    expect(within(section('Selfie check')).getByText(/waiting for a reviewer/)).toBeDefined();
  });

  it.each([
    ['LIVENESS_FAILED', 'We could not see you move between the two photos.'],
    ['LOW_SIMILARITY', 'Your selfie did not match your profile photo.'],
    ['REVIEWER_REJECTED', 'A reviewer could not match your selfie'],
    ['PROFILE_PHOTO_CHANGED', 'You changed your profile photo'],
    ['REVIEW_EXPIRED', 'was not reviewed in time and has been deleted'],
  ])('explains a %s rejection in its own words', async (reason, text) => {
    await openCentre({ tier: 'PHONE', photo: true, selfieStatus: 'REJECTED', rejectionReason: reason });

    expect(within(section('Selfie check')).getByRole('alert').textContent).toContain(text);
  });

  it('takes two live frames around the prompt and sends them together', async () => {
    let sent = '';
    server.use(
      apiPost('/verification/selfie/challenge', () => HttpResponse.json(CHALLENGE)),
      apiPost('/verification/selfie', async ({ request }) => {
        sent = await request.text();
        return HttpResponse.json(overviewBody({ tier: 'SELFIE_MATCH', photo: true, selfieStatus: 'APPROVED' }));
      }),
    );
    await openReady();

    fireEvent.click(screen.getByRole('button', { name: 'Start the selfie check' }));
    expect(await screen.findByText('Step 1 of 2: look straight at the camera.')).toBeDefined();
    expect(camera.attachStream).toHaveBeenCalledWith(screen.getByLabelText('Camera preview'), FAKE_STREAM);
    fireEvent.click(screen.getByRole('button', { name: 'Take the first photo' }));
    expect(await screen.findByText('Step 2 of 2: turn your head a little to your left.')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Take the second photo and send' }));

    expect(await screen.findByText('Your selfie matches your profile photo.')).toBeDefined();
    expect(sent).toMatch(new RegExp(`name="challengeId"\\r?\\n\\r?\\n${CHALLENGE.challengeId}\\r?\\n`));
    expect(sent).toContain('name="neutralFrame"; filename="neutral.jpg"');
    expect(sent).toContain('name="promptFrame"; filename="prompt.jpg"');
    expect(camera.stopStream).toHaveBeenCalledWith(FAKE_STREAM);
    await waitFor(() => {
      expect(useAuthStore.getState().user?.verificationTier).toBe('SELFIE_MATCH');
    });
  });

  it.each([
    ['denied', 'was not allowed to use the camera'],
    ['unsupported', 'This browser cannot use a camera.'],
    ['unavailable', 'No camera could be started.'],
  ] as const)('explains a %s camera without spending an attempt', async (problemKind, text) => {
    let challenges = 0;
    server.use(
      apiPost('/verification/selfie/challenge', () => {
        challenges++;
        return HttpResponse.json(CHALLENGE);
      }),
    );
    vi.mocked(camera.openFrontCamera).mockRejectedValue(new camera.CameraError(problemKind));
    await openReady();

    fireEvent.click(screen.getByRole('button', { name: 'Start the selfie check' }));

    expect((await screen.findByRole('alert')).textContent).toContain(text);
    expect(challenges).toBe(0);
  });

  it.each([
    ['selfie_review_pending', 409, 'Your last selfie is waiting for a reviewer. You will see the result here.'],
    ['phone_verification_required', 409, 'Verify your phone number before the selfie check.'],
    ['profile_photo_required', 409, 'Add a profile photo first. The selfie is compared against it.'],
    ['selfie_already_verified', 409, 'Your selfie already matches your profile photo.'],
  ])('stops the camera when the server refuses to start (%s)', async (code, status, detail) => {
    server.use(apiPost('/verification/selfie/challenge', () => problem(status, code, detail)));
    await openReady();

    fireEvent.click(screen.getByRole('button', { name: 'Start the selfie check' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
    expect(camera.stopStream).toHaveBeenCalledWith(FAKE_STREAM);
    expect(screen.queryByLabelText('Camera preview')).toBeNull();
  });

  async function submitBoth() {
    fireEvent.click(screen.getByRole('button', { name: 'Start the selfie check' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Take the first photo' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Take the second photo and send' }));
  }

  it.each([
    ['liveness_challenge_invalid', 400, 'This selfie check has expired or was already used. Start a new one.'],
    ['invalid_image', 400, 'The image could not be used. Upload a JPEG or PNG photo, 64 to 4096 pixels on each side.'],
  ])('shows %s from the submission and offers a fresh start', async (code, status, detail) => {
    server.use(
      apiPost('/verification/selfie/challenge', () => HttpResponse.json(CHALLENGE)),
      apiPost('/verification/selfie', () => problem(status, code, detail)),
    );
    await openReady();

    await submitBoth();

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
    expect(screen.getByRole('button', { name: 'Start the selfie check' })).toHaveProperty('disabled', false);
  });

  it('holds the start button through a rate limit', async () => {
    server.use(
      apiPost('/verification/selfie/challenge', () => HttpResponse.json(CHALLENGE)),
      apiPost('/verification/selfie', () =>
        problem(429, 'rate_limited', 'Too many attempts.', { headers: { 'Retry-After': '3600' } }),
      ),
    );
    await openReady();

    await submitBoth();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Too many attempts.');
    expect(alert.textContent).toContain('You can try again in');
    expect(screen.getByRole('button', { name: 'Start the selfie check' })).toHaveProperty('disabled', true);
  });

  it('asks for a moment when the camera has no frame yet, without ending the check', async () => {
    server.use(apiPost('/verification/selfie/challenge', () => HttpResponse.json(CHALLENGE)));
    vi.mocked(camera.captureFrame).mockRejectedValueOnce(new camera.CameraNotReadyError());
    await openReady();

    fireEvent.click(screen.getByRole('button', { name: 'Start the selfie check' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Take the first photo' }));

    expect(await screen.findByText('The camera is still starting. Try again in a moment.')).toBeDefined();
    expect(screen.getByText('Step 1 of 2: look straight at the camera.')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Take the first photo' }));
    expect(await screen.findByText('Step 2 of 2: turn your head a little to your left.')).toBeDefined();
    expect(screen.queryByText('The camera is still starting. Try again in a moment.')).toBeNull();
  });

  it('times out a check that is left open and turns the camera off', async () => {
    server.use(apiPost('/verification/selfie/challenge', () => HttpResponse.json({ ...CHALLENGE, expiresIn: 1 })));
    await openReady();

    fireEvent.click(screen.getByRole('button', { name: 'Start the selfie check' }));
    await screen.findByLabelText('Camera preview');

    expect(
      (await screen.findByRole('alert', {}, { timeout: 3000 })).textContent,
    ).toContain('This selfie check timed out.');
    expect(screen.queryByLabelText('Camera preview')).toBeNull();
    expect(camera.stopStream).toHaveBeenCalledWith(FAKE_STREAM);
  });

  it('turns the camera off when the user leaves the page', async () => {
    server.use(apiPost('/verification/selfie/challenge', () => HttpResponse.json(CHALLENGE)));
    await openReady();
    fireEvent.click(screen.getByRole('button', { name: 'Start the selfie check' }));
    await screen.findByLabelText('Camera preview');
    vi.mocked(camera.stopStream).mockClear();

    cleanup();

    expect(camera.stopStream).toHaveBeenCalledWith(FAKE_STREAM);
  });
});

describe('vouch step', () => {
  it('shows the ID a friend needs, and the vouch screen link once the selfie matches', async () => {
    await openCentre({ tier: 'SELFIE_MATCH', photo: true, selfieStatus: 'APPROVED', received: 1 });

    const vouches = section('Vouches');
    expect(within(vouches).getByText(SIGNED_IN_USER_ID)).toBeDefined();
    expect(within(vouches).getByText('1 person vouches for you.')).toBeDefined();
    expect(within(vouches).getByRole('link', { name: /Vouch for a friend/ }).getAttribute('href')).toBe('/account/vouches');
  });

  it('explains vouching comes with the selfie tier', async () => {
    await openCentre({ tier: 'PHONE' });

    expect(within(section('Vouches')).queryByRole('link')).toBeNull();
    expect(within(section('Vouches')).getByText(/Once your selfie matches/)).toBeDefined();
  });
});
