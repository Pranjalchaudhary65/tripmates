import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { profileBody, quizBody } from '../test/vibeFixtures';
import { createApiClient } from './createApiClient';
import { createVibeApi } from './vibeApi';

const BASE_URL = 'http://api.test/api/v1';

const server = setupServer();
const api = createVibeApi(createApiClient(BASE_URL));

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

describe('vibe API', () => {
  it('reads a quiz and keeps only string answers from a draft', async () => {
    server.use(
      http.get(`${BASE_URL}/vibe/quiz`, () =>
        HttpResponse.json({
          ...quizBody(),
          draft: { answers: { 'budget-stay': 'b', 'pace-day': 3, 'pace-rest': null }, updatedAt: '2026-11-23T10:00:00Z' },
        }),
      ),
    );

    const quiz = await api.quiz();

    expect(quiz.questions).toHaveLength(5);
    expect(quiz.draft?.answers).toEqual({ 'budget-stay': 'b' });
  });

  it('refuses a quiz with a question that has no options', async () => {
    server.use(
      http.get(`${BASE_URL}/vibe/quiz`, () =>
        HttpResponse.json({ ...quizBody(), questions: [{ id: 'q', text: 'Q?', options: [] }] }),
      ),
    );

    await expect(api.quiz()).rejects.toThrow('Unexpected quiz question.');
  });

  it.each([
    ['socialEnergy', 0],
    ['socialEnergy', 6],
    ['riskAppetite', 2.5],
    ['budgetBand', 'BILLIONAIRE'],
    ['expenseSplitStyle', null],
  ])('refuses a profile whose %s is %j', async (field, value) => {
    server.use(
      http.get(`${BASE_URL}/vibe/profile`, () =>
        HttpResponse.json({ ...profileBody(), profile: { ...profileBody().profile, [field]: value } }),
      ),
    );

    await expect(api.profile()).rejects.toThrow('Unexpected vibe profile.');
  });

  it('reads a missing profile and missing deal-breakers as null', async () => {
    server.use(http.get(`${BASE_URL}/vibe/profile`, () => HttpResponse.json(profileBody({ profile: false }))));

    expect(await api.profile()).toEqual({ profile: null, dealBreakers: null });
  });
});
