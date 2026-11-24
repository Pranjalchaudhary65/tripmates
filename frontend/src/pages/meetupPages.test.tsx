// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import * as camera from '../camera/camera';
import * as geolocation from '../location/geolocation';
import { useAuthStore } from '../stores/authStore';
import { apiDelete, apiGet, apiPost, apiPut, createApiServer, problem } from '../test/apiServer';
import { match, MATCH_ID, meetup, VENUE_ID, venueOptions } from '../test/meetupFixtures';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';

vi.mock('../camera/camera', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../camera/camera')>();
  return { ...actual, openFrontCamera: vi.fn(), attachStream: vi.fn(), captureFrame: vi.fn(), stopStream: vi.fn() };
});
vi.mock('../location/geolocation', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../location/geolocation')>();
  return { ...actual, currentPosition: vi.fn() };
});

const server = createApiServer();
const MATCH_PATH = `/matches/${MATCH_ID}`;
const FAKE_STREAM = { id: 'front-camera' } as unknown as MediaStream;

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  server.use(apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')));
  vi.mocked(camera.openFrontCamera).mockResolvedValue(FAKE_STREAM);
  vi.mocked(camera.attachStream).mockResolvedValue(undefined);
  vi.mocked(camera.captureFrame).mockResolvedValue(new Blob(['frame'], { type: 'image/jpeg' }));
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

async function openMatch(body: Record<string, unknown>, heading: string) {
  server.use(apiGet(MATCH_PATH, () => HttpResponse.json(body)));
  signIn();
  renderAt(`/matches/${MATCH_ID}`);
  await screen.findByRole('heading', { level: 1, name: heading });
}

describe('match screen', () => {
  it('shows the same plain screen to both people when a match stops, with no reason', async () => {
    await openMatch(match('DID_NOT_PROCEED'), 'This match didn’t proceed');

    expect(screen.getByText(/we don't share them with anyone/)).toBeDefined();
    expect(screen.queryByRole('region', { name: 'Meet-up details' })).toBeNull();
    expect(screen.queryByRole('list', { name: 'Steps before the trip locks' })).toBeNull();
    expect(document.body.textContent).not.toMatch(/rating|safety|cancel/i);
  });

  it('confirms the video call and moves on once both have', async () => {
    let posted = false;
    server.use(
      apiPost(`${MATCH_PATH}/video-call`, () => {
        posted = true;
        return HttpResponse.json(match('ARRANGE_MEETUP', { emergencyContactSet: false }));
      }),
    );
    await openMatch(
      match('VIDEO_CALL', { videoCall: { confirmedByMe: false, confirmedByThem: true, verified: false } }),
      'Step 1: a video call',
    );

    expect(screen.getByText('Asha: confirmed')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'We had our video call' }));

    expect(await screen.findByRole('heading', { name: 'Step 2: meet in a public place' })).toBeDefined();
    expect(posted).toBe(true);
    expect(screen.getByRole('link', { name: 'Add an emergency contact' })).toBeDefined();
  });

  it('offers only daylight start times at a verified venue, shown in the venue’s time, and proposes one', async () => {
    let sent: unknown = null;
    server.use(
      apiGet(`${MATCH_PATH}/venues`, () => HttpResponse.json(venueOptions())),
      apiPost(`${MATCH_PATH}/meetup`, async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(match('AWAITING_CONFIRMATION', { meetup: meetup({ proposedByMe: true }) }), {
          status: 201,
        });
      }),
    );
    await openMatch(match('ARRANGE_MEETUP'), 'Step 2: meet in a public place');

    fireEvent.click(await screen.findByRole('radio', { name: /Gokarna bus stand/ }));
    const time = screen.getByLabelText('Start time (daylight only)');
    const options = within(time).getAllByRole('option').map((option) => option.textContent);
    expect(options[1]).toMatch(/7:15/);
    expect(options.at(-1)).toMatch(/3:45/);
    fireEvent.change(time, { target: { value: '2026-12-05T02:00:00.000Z' } });
    fireEvent.click(screen.getByRole('button', { name: 'Propose this meet-up' }));

    expect(await screen.findByText('Waiting for Asha to confirm.')).toBeDefined();
    expect(sent).toEqual({ venueId: VENUE_ID, scheduledAt: '2026-12-05T02:00:00.000Z', bringingPlusOne: false });
  });

  it('needs a venue and a time before proposing anything', async () => {
    let posts = 0;
    server.use(
      apiGet(`${MATCH_PATH}/venues`, () => HttpResponse.json(venueOptions())),
      apiPost(`${MATCH_PATH}/meetup`, () => {
        posts++;
        return HttpResponse.json(match('AWAITING_CONFIRMATION', { meetup: meetup() }), { status: 201 });
      }),
    );
    await openMatch(match('ARRANGE_MEETUP'), 'Step 2: meet in a public place');
    await screen.findByRole('radio', { name: /Gokarna bus stand/ });

    fireEvent.click(screen.getByRole('button', { name: 'Propose this meet-up' }));

    expect(screen.getByText('Choose a venue, a day and a time.')).toBeDefined();
    expect(posts).toBe(0);
  });

  it('shows the server’s reason when it refuses a time', async () => {
    server.use(
      apiGet(`${MATCH_PATH}/venues`, () => HttpResponse.json(venueOptions())),
      apiPost(`${MATCH_PATH}/meetup`, () =>
        problem(400, 'meetup_not_in_daylight', 'Meet-ups happen in daylight. Pick a start time inside the venue’s daylight window.'),
      ),
    );
    await openMatch(match('ARRANGE_MEETUP'), 'Step 2: meet in a public place');
    fireEvent.click(await screen.findByRole('radio', { name: /Gokarna bus stand/ }));
    fireEvent.change(screen.getByLabelText('Start time (daylight only)'), {
      target: { value: '2026-12-05T02:00:00.000Z' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Propose this meet-up' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Meet-ups happen in daylight.');
  });

  it('lets the other person confirm a proposal', async () => {
    let sent: unknown = null;
    server.use(
      apiPost(`${MATCH_PATH}/meetup/confirm`, async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(match('CONFIRMED', { meetup: meetup({ status: 'CONFIRMED', myPlusOne: true }) }));
      }),
    );
    await openMatch(match('AWAITING_CONFIRMATION', { meetup: meetup() }), 'Step 2: meet in a public place');

    expect(within(screen.getByRole('region', { name: 'Meet-up details' })).getByText(/12:00 pm/)).toBeDefined();
    fireEvent.click(screen.getByRole('checkbox', { name: 'I am bringing a friend along' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm this meet-up' }));

    expect(await screen.findByRole('heading', { name: 'Your meet-up is on' })).toBeDefined();
    expect(sent).toEqual({ bringingPlusOne: true });
  });

  it('explains the safety net and lets someone say they are safe', async () => {
    server.use(
      apiPost(`${MATCH_PATH}/meetup/safe`, () =>
        HttpResponse.json(match('CONFIRMED', { meetup: meetup({ status: 'CONFIRMED', iConfirmedSafe: true }) })),
      ),
    );
    await openMatch(match('CONFIRMED', { meetup: meetup({ status: 'CONFIRMED', theyArrived: true }) }), 'Your meet-up is on');

    const safety = screen.getByRole('region', { name: 'Safety' });
    expect(safety.textContent).toContain('After 60 minutes we alert your emergency contact');
    expect(screen.getByText(/Asha: checked in/)).toBeDefined();
    expect(screen.getByRole('link', { name: 'Check in at the venue' }).getAttribute('href')).toBe(
      `/matches/${MATCH_ID}/check-in`,
    );
    fireEvent.click(within(safety).getByRole('button', { name: "I'm safe" }));

    expect(await within(safety).findByText(/You told us you are safe/)).toBeDefined();
  });

  it('steps back for safety after one confirmation that explains it costs nothing', async () => {
    let sent: unknown = null;
    server.use(
      apiPost(`${MATCH_PATH}/meetup/cancel`, async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(match('DID_NOT_PROCEED'));
      }),
    );
    await openMatch(match('CONFIRMED', { meetup: meetup({ status: 'CONFIRMED' }) }), 'Your meet-up is on');

    fireEvent.click(screen.getByRole('button', { name: "I don't feel comfortable going ahead" }));
    const dialog = screen.getByRole('alertdialog', { name: 'Call off the meet-up' });
    expect(dialog.textContent).toContain('It costs you nothing.');
    fireEvent.click(within(dialog).getByRole('button', { name: 'End the match' }));

    expect(await screen.findByRole('heading', { name: 'This match didn’t proceed' })).toBeDefined();
    expect(sent).toEqual({ type: 'SAFETY' });
  });

  it('asks privately how it felt, and needs an answer', async () => {
    let sent: unknown = null;
    server.use(
      apiPost(`${MATCH_PATH}/meetup/feedback`, async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(match('WAITING_FOR_OTHER', { meetup: meetup({ status: 'COMPLETED' }), myFeedbackGiven: true }));
      }),
    );
    await openMatch(match('FEEDBACK', { meetup: meetup({ status: 'COMPLETED' }) }), 'How did it feel?');

    expect(screen.getByText(/never shown to them, never part of any rating/)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Save privately' }));
    expect(screen.getByText('Choose how comfortable you felt.')).toBeDefined();
    fireEvent.click(screen.getByRole('radio', { name: /4 · Comfortable/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Save privately' }));

    expect(await screen.findByRole('heading', { name: 'Thanks for telling us' })).toBeDefined();
    expect(sent).toEqual({ comfortRating: 4, notes: null });
  });

  it('never calls the API for an ID that is not a UUID, and treats a 404 the same', async () => {
    signIn();
    renderAt('/matches/..%2Fadmin');
    expect(await screen.findByRole('heading', { name: 'Match not available' })).toBeDefined();
    cleanup();

    server.use(apiGet(MATCH_PATH, () => problem(404, 'not_found', 'The requested resource was not found.')));
    renderAt(`/matches/${MATCH_ID}`);
    expect(await screen.findByRole('heading', { name: 'Match not available' })).toBeDefined();
  });
});

describe('check-in screen', () => {
  async function openCheckIn() {
    server.use(apiGet(MATCH_PATH, () => HttpResponse.json(match('CONFIRMED', { meetup: meetup({ status: 'CONFIRMED' }) }))));
    signIn();
    renderAt(`/matches/${MATCH_ID}/check-in`);
    await screen.findByRole('button', { name: 'Share my location' });
  }

  it('takes the location and a live selfie, then checks in', async () => {
    vi.mocked(geolocation.currentPosition).mockResolvedValue({ latitude: 14.5406, longitude: 74.3167, accuracy: 8 });
    let body = '';
    server.use(
      apiPost(`${MATCH_PATH}/meetup/check-in`, async ({ request }) => {
        body = await request.text();
        return HttpResponse.json({ arrived: true, meetupCompleted: true });
      }),
    );
    await openCheckIn();

    expect(screen.getByRole('button', { name: 'Check in' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Share my location' }));
    expect(await screen.findByText(/You look close enough: about 44 m/)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Open camera' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Take selfie' }));
    expect(await screen.findByText(/Selfie taken/)).toBeDefined();
    expect(camera.stopStream).toHaveBeenCalledWith(FAKE_STREAM);
    fireEvent.click(screen.getByRole('button', { name: 'Check in' }));

    expect(await screen.findByText('You are checked in.')).toBeDefined();
    expect(screen.getByText(/Asha is here too/)).toBeDefined();
    expect(body).toMatch(/name="latitude"\r?\n\r?\n14\.5406/);
    expect(body).toContain('name="selfie"; filename="selfie.jpg"');
  });

  it('explains a refused location without sending anything', async () => {
    vi.mocked(geolocation.currentPosition).mockRejectedValue(new geolocation.LocationError('denied'));
    await openCheckIn();

    fireEvent.click(screen.getByRole('button', { name: 'Share my location' }));

    expect((await screen.findByRole('alert')).textContent).toContain('not allowed to use your location');
  });

  it('shows the server’s answer when the phone is not at the venue', async () => {
    vi.mocked(geolocation.currentPosition).mockResolvedValue({ latitude: 14.56, longitude: 74.3167, accuracy: 8 });
    server.use(
      apiPost(`${MATCH_PATH}/meetup/check-in`, () =>
        problem(409, 'not_at_venue', 'You need to be at the venue to check in, within 100 metres of it.'),
      ),
    );
    await openCheckIn();

    fireEvent.click(screen.getByRole('button', { name: 'Share my location' }));
    expect(await screen.findByText(/You need to be within 100 m of the venue/)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Open camera' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Take selfie' }));
    await screen.findByText(/Selfie taken/);
    fireEvent.click(screen.getByRole('button', { name: 'Check in' }));

    expect((await screen.findByRole('alert')).textContent).toContain('within 100 metres');
  });
});

describe('emergency contact screen', () => {
  it('adds a contact after checking it, and shows the number masked', async () => {
    let saved = false;
    let sent: unknown = null;
    server.use(
      apiGet('/users/me/emergency-contact', () =>
        HttpResponse.json(
          saved
            ? { contact: { name: 'Meera Rao', relationship: 'SIBLING', maskedPhone: '+91 ••••••• 210', updatedAt: '2026-11-24T10:00:00Z' } }
            : { contact: null },
        ),
      ),
      apiPut('/users/me/emergency-contact', async ({ request }) => {
        sent = await request.json();
        saved = true;
        return HttpResponse.json({
          contact: { name: 'Meera Rao', relationship: 'SIBLING', maskedPhone: '+91 ••••••• 210', updatedAt: '2026-11-24T10:00:00Z' },
        });
      }),
    );
    signIn();
    renderAt('/account/emergency-contact');
    await screen.findByRole('heading', { name: 'Add a contact' });

    fireEvent.click(screen.getByRole('button', { name: 'Save contact' }));
    expect(screen.getByText('Enter their name.')).toBeDefined();
    expect(sent).toBeNull();
    fireEvent.change(screen.getByLabelText('Their name'), { target: { value: 'Meera Rao' } });
    fireEvent.change(screen.getByLabelText('Who they are to you'), { target: { value: 'SIBLING' } });
    fireEvent.change(screen.getByLabelText('Their mobile number'), { target: { value: '+91 98765 43210' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save contact' }));

    const current = await screen.findByRole('region', { name: 'Current contact' });
    expect(current.textContent).toContain('+91 ••••••• 210');
    expect(sent).toEqual({ name: 'Meera Rao', relationship: 'SIBLING', phoneNumber: '+91 98765 43210' });
  });

  it('puts the server’s refusal of the user’s own number beside the number', async () => {
    server.use(
      apiGet('/users/me/emergency-contact', () => HttpResponse.json({ contact: null })),
      apiPut('/users/me/emergency-contact', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Bad Request',
            status: 400,
            code: 'emergency_contact_is_you',
            detail: 'Your emergency contact must be someone else. Enter their number, not yours.',
            errors: [{ field: 'phoneNumber', message: 'Your emergency contact must be someone else. Enter their number, not yours.' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
      apiDelete('/users/me/emergency-contact', () => new HttpResponse(null, { status: 204 })),
    );
    signIn();
    renderAt('/account/emergency-contact');
    await screen.findByRole('heading', { name: 'Add a contact' });

    fireEvent.change(screen.getByLabelText('Their name'), { target: { value: 'Me' } });
    fireEvent.change(screen.getByLabelText('Who they are to you'), { target: { value: 'OTHER' } });
    fireEvent.change(screen.getByLabelText('Their mobile number'), { target: { value: '+91 98765 43210' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save contact' }));

    await waitFor(() => {
      expect(document.body.textContent).toContain('must be someone else');
    });
  });
});
