import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { overviewBody, PHOTO_PATH } from '../test/verificationFixtures';
import { createApiClient } from './createApiClient';
import { createVerificationApi } from './verificationApi';

const BASE_URL = 'http://api.test/api/v1';

const server = setupServer();
const api = createVerificationApi(createApiClient(BASE_URL), BASE_URL);

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

function overviewWith(profilePhoto: unknown) {
  server.use(http.get(`${BASE_URL}/verification`, () => HttpResponse.json({ ...overviewBody(), profilePhoto })));
}

describe('verification API', () => {
  it('builds image links under the API origin from the media path', async () => {
    overviewWith({ path: PHOTO_PATH, expiresAt: '2026-11-23T10:05:00Z' });

    const overview = await api.overview();

    expect(overview.profilePhoto).toEqual({ src: `${BASE_URL}${PHOTO_PATH}`, expiresAt: '2026-11-23T10:05:00Z' });
  });

  it.each([
    'https://evil.example/x.jpg',
    '//evil.example/x.jpg',
    '/media/../admin',
    '/media/a.b?x=1',
    'javascript:alert(1)',
    '/other/a.b',
  ])('refuses a media path of %s', async (path) => {
    overviewWith({ path, expiresAt: '2026-11-23T10:05:00Z' });

    await expect(api.overview()).rejects.toThrow('Unexpected media link.');
  });

  it('reads an unknown status or rejection reason as none', async () => {
    server.use(
      http.get(`${BASE_URL}/verification`, () =>
        HttpResponse.json({ ...overviewBody(), selfie: { status: 'HACKED', rejectionReason: '<b>x</b>', submittedAt: null } }),
      ),
    );

    const overview = await api.overview();

    expect(overview.selfie).toEqual({ status: null, rejectionReason: null, submittedAt: null });
  });

  it('refuses a tier it does not know', async () => {
    server.use(http.get(`${BASE_URL}/verification`, () => HttpResponse.json({ ...overviewBody(), tier: 'ID_DOCUMENT' })));

    await expect(api.overview()).rejects.toThrow('Unexpected verification state.');
  });

  it('refuses a selfie check with an unknown prompt', async () => {
    server.use(
      http.post(`${BASE_URL}/verification/selfie/challenge`, () =>
        HttpResponse.json({ challengeId: 'c', prompt: 'SAY_YOUR_PASSWORD', expiresIn: 120 }),
      ),
    );

    await expect(api.startSelfie()).rejects.toThrow('Unexpected selfie check.');
  });

  it('encodes a vouch ID into the path', async () => {
    let path = '';
    server.use(
      http.delete(`${BASE_URL}/vouches/*`, ({ request }) => {
        path = new URL(request.url).pathname;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await api.withdrawVouch('../admin/users');

    expect(path).toBe('/api/v1/vouches/..%2Fadmin%2Fusers');
  });
});
