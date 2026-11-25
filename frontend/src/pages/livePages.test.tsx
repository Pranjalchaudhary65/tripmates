// @vitest-environment jsdom
import { cleanup, fireEvent, screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import * as geolocation from '../location/geolocation';
import { useAuthStore } from '../stores/authStore';
import { apiDelete, apiGet, apiPost, apiPut, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';
import { liveTrip, premortem, SOS_ID, TRIP_ID } from '../test/safetyFixtures';

vi.mock('../location/geolocation', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../location/geolocation')>();
  return { ...actual, currentPosition: vi.fn() };
});

const server = createApiServer();
const LIVE = `/trips/${TRIP_ID}/live`;

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  server.use(apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')));
  vi.mocked(geolocation.currentPosition).mockResolvedValue({ latitude: 14.551234, longitude: 74.312345, accuracy: 18 });
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

async function openLive(body: Record<string, unknown>) {
  server.use(apiGet(LIVE, () => HttpResponse.json(body)));
  signIn();
  renderAt(`/trips/${TRIP_ID}/live`);
  await screen.findByRole('heading', { level: 1, name: 'Live trip: Monsoon weekend in Gokarna' });
}

describe('live trip screen', () => {
  it('asks before sending an SOS, sends it with the location once, and offers 112', async () => {
    const sent: Record<string, unknown>[] = [];
    server.use(
      apiPost(`/trips/${TRIP_ID}/sos`, async ({ request }) => {
        sent.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json(
          liveTrip({ openSos: { id: SOS_ID, triggeredAt: '2026-12-12T10:00:00Z', contactAlerted: true } }),
          { status: 201 },
        );
      }),
    );
    await openLive(liveTrip());

    fireEvent.click(screen.getByRole('button', { name: 'SOS' }));
    expect(sent).toHaveLength(0);
    expect(screen.getByRole('link', { name: 'Call 112' }).getAttribute('href')).toBe('tel:112');
    fireEvent.click(screen.getByRole('button', { name: 'Send SOS now' }));

    expect(await screen.findByText(/Your emergency contact has been alerted/)).toBeDefined();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ latitude: 14.551234, longitude: 74.312345, accuracyMeters: 18 });
    expect(typeof sent[0]?.['clientSosId']).toBe('string');
  });

  it('sends the SOS without a location rather than fail it, and reuses one ID on a retry', async () => {
    vi.mocked(geolocation.currentPosition).mockRejectedValue(new geolocation.LocationError('denied'));
    const sent: Record<string, unknown>[] = [];
    server.use(
      apiPost(`/trips/${TRIP_ID}/sos`, async ({ request }) => {
        sent.push((await request.json()) as Record<string, unknown>);
        if (sent.length === 1) {
          return problem(503, 'temporarily_unavailable', 'Try again shortly.');
        }
        return HttpResponse.json(
          liveTrip({ openSos: { id: SOS_ID, triggeredAt: '2026-12-12T10:00:00Z', contactAlerted: false } }),
          { status: 201 },
        );
      }),
    );
    await openLive(liveTrip());

    fireEvent.click(screen.getByRole('button', { name: 'SOS' }));
    fireEvent.click(screen.getByRole('button', { name: 'Send SOS now' }));
    expect(await screen.findByText('If you are in danger, call 112 now.')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Send SOS now' }));

    expect(await screen.findByText(/could not reach your emergency contact yet/)).toBeDefined();
    expect(sent).toHaveLength(2);
    expect(sent[0]).not.toHaveProperty('latitude');
    expect(sent[1]?.['clientSosId']).toBe(sent[0]?.['clientSosId']);
  });

  it('prompts an overdue check-in and answers it with the location', async () => {
    let body: unknown = null;
    server.use(
      apiPost(`${LIVE}/check-in`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(
          liveTrip({
            nextCheckIn: { dueAt: '2026-12-13T03:00:00Z', overdue: false },
            lastCheckIn: { at: '2026-12-12T15:05:00Z', kmFromDestination: 1.2 },
          }),
        );
      }),
    );
    await openLive(liveTrip({ nextCheckIn: { dueAt: '2026-12-12T15:00:00Z', overdue: true } }));

    expect(screen.getByRole('alert').textContent).toMatch(/your emergency contact will be alerted/);
    fireEvent.click(screen.getByRole('button', { name: "I'm safe" }));

    expect(await screen.findByText(/about 1.2 km from Gokarna, Karnataka\.$/)).toBeDefined();
    expect(body).toEqual({ latitude: 14.551234, longitude: 74.312345, accuracyMeters: 18 });
  });

  it('flags a check-in made far outside the trip area', async () => {
    await openLive(liveTrip({ lastCheckIn: { at: '2026-12-12T15:05:00Z', kmFromDestination: 180.4 } }));

    expect(screen.getByText(/180.4 km from Gokarna, Karnataka: outside the trip area/)).toBeDefined();
  });

  it('starts and stops sharing, with no reason asked', async () => {
    let shared: unknown = null;
    server.use(
      apiPut(`${LIVE}/sharing`, async ({ request }) => {
        shared = await request.json();
        return HttpResponse.json(liveTrip({ sharing: { checkInEveryHours: 6, noteSet: true, startedAt: '2026-12-12T03:00:00Z' } }));
      }),
      apiDelete(`${LIVE}/sharing`, () => HttpResponse.json(liveTrip({ sharing: null, nextCheckIn: null }))),
    );
    await openLive(liveTrip({ sharing: null, nextCheckIn: null }));

    const form = screen.getByRole('form', { name: 'Trip sharing' });
    fireEvent.change(within(form).getByLabelText('Check in every'), { target: { value: '6' } });
    fireEvent.change(within(form).getByLabelText(/Note for your emergency contact/), { target: { value: 'Room 4' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Start sharing' }));
    expect(await screen.findByRole('heading', { name: 'Sharing is on' })).toBeDefined();
    expect(shared).toEqual({ checkInEveryHours: 6, note: 'Room 4' });

    fireEvent.click(screen.getByRole('button', { name: 'Stop sharing' }));
    expect(await screen.findByRole('heading', { name: 'Share this trip with your emergency contact' })).toBeDefined();
  });

  it('sends the user to add an emergency contact before sharing', async () => {
    await openLive(liveTrip({ sharing: null, nextCheckIn: null, emergencyContactSet: false }));

    expect(screen.getByRole('link', { name: 'Add an emergency contact' })).toBeDefined();
    expect(screen.queryByRole('form', { name: 'Trip sharing' })).toBeNull();
  });

  it('shows companions by name and check-in time only', async () => {
    await openLive(liveTrip());

    const list = screen.getByRole('list', { name: 'Travelling with' });
    expect(within(list).getByText(/Asha \(host\) · last checked in/)).toBeDefined();
  });

  it('shows the pre-mortem before the trip starts', async () => {
    server.use(apiGet(`/trips/${TRIP_ID}/premortem`, () => HttpResponse.json(premortem())));
    await openLive(liveTrip({ phase: 'BEFORE', nextCheckIn: null }));

    const region = screen.getByRole('region', { name: 'Before you go' });
    expect(await within(region).findByText('You like slow days; Asha likes packed days.')).toBeDefined();
    expect(within(region).getByRole('heading', { name: 'You and Asha' })).toBeDefined();
  });

  it('keeps SOS available on a trip that is not locked yet, without check-ins', async () => {
    await openLive(liveTrip({ locked: false, sharing: null, nextCheckIn: null }));

    expect(screen.getByRole('button', { name: 'SOS' })).toBeDefined();
    expect(screen.queryByRole('form', { name: 'Trip sharing' })).toBeNull();
  });

  it('shows the same "not available" for a trip the user is not on and for a bad ID', async () => {
    server.use(apiGet(LIVE, () => problem(404, 'not_found', 'The requested resource was not found.')));
    signIn();
    renderAt(`/trips/${TRIP_ID}/live`);
    expect(await screen.findByRole('heading', { name: 'Live trip not available' })).toBeDefined();

    cleanup();
    renderAt('/trips/nope/live');
    expect(await screen.findByRole('heading', { name: 'Live trip not available' })).toBeDefined();
  });
});
