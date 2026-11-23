// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiGet, apiPost, apiPut, createApiServer, problem } from '../test/apiServer';
import { heading, installSessionHandling, renderAt, signIn } from '../test/renderApp';
import { profileBody, quizBody } from '../test/vibeFixtures';

const server = createApiServer();

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  server.use(apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')));
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

async function openQuiz(options: Parameters<typeof quizBody>[0] = {}) {
  server.use(apiGet('/vibe/quiz', () => HttpResponse.json(quizBody(options))));
  signIn();
  renderAt('/vibe/quiz');
  await screen.findByRole('progressbar', { name: 'Quiz progress' });
}

function pick(label: string) {
  fireEvent.click(screen.getByRole('radio', { name: label }));
}

function next() {
  fireEvent.click(screen.getByRole('button', { name: /Save and continue|See my vibe/ }));
}

describe('vibe quiz', () => {
  it('needs a session', async () => {
    renderAt('/vibe/quiz');

    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeDefined();
  });

  it('shows the first step of questions with their options', async () => {
    await openQuiz();

    expect(heading()).toBe('Your travel vibe');
    expect(screen.getByText('Step 1 of 3')).toBeDefined();
    expect(screen.getByRole('group', { name: 'Where would you rather sleep on a trip?' })).toBeDefined();
    expect(screen.getByRole('group', { name: 'How do you like to get between cities?' })).toBeDefined();
    expect(screen.queryByRole('group', { name: 'What does a good day look like?' })).toBeNull();
  });

  it('asks for every answer on a step before saving anything', async () => {
    let saves = 0;
    server.use(
      apiPut('/vibe/quiz/draft', () => {
        saves++;
        return HttpResponse.json({ answers: {}, updatedAt: '2026-11-23T10:00:00Z' });
      }),
    );
    await openQuiz();

    pick('Guesthouse');
    next();

    expect(await screen.findByText('Choose an answer.')).toBeDefined();
    expect(screen.getByRole('group', { name: 'How do you like to get between cities?' }).getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(saves).toBe(0);
  });

  it('saves the answers so far at every step, then submits them all and shows the vibe', async () => {
    const drafts: unknown[] = [];
    let submitted: unknown = null;
    server.use(
      apiPut('/vibe/quiz/draft', async ({ request }) => {
        drafts.push(await request.json());
        return HttpResponse.json({ answers: {}, updatedAt: '2026-11-23T10:00:00Z' });
      }),
      apiPost('/vibe/quiz/submit', async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json(profileBody());
      }),
      apiGet('/vibe/profile', () => HttpResponse.json(profileBody())),
    );
    await openQuiz();

    pick('Guesthouse');
    pick('Train');
    next();
    expect(await screen.findByText('Step 2 of 3')).toBeDefined();
    pick('Packed');
    pick('Some');
    next();
    expect(await screen.findByText('Step 3 of 3')).toBeDefined();
    pick('Whoever can');
    next();

    expect(await screen.findByRole('heading', { level: 1, name: 'My vibe' })).toBeDefined();
    expect(await screen.findByText(/Here is your travel vibe/)).toBeDefined();
    expect(drafts).toEqual([
      { answers: { 'budget-stay': 'b', 'budget-transport': 'b' } },
      { answers: { 'budget-stay': 'b', 'budget-transport': 'b', 'pace-day': 'a', 'pace-rest': 'b' } },
    ]);
    expect(submitted).toEqual({
      answers: { 'budget-stay': 'b', 'budget-transport': 'b', 'pace-day': 'a', 'pace-rest': 'b', 'expense-split': 'b' },
    });
  });

  it('resumes at the first unfinished step with the saved answers ticked', async () => {
    await openQuiz({ draft: { 'budget-stay': 'c', 'budget-transport': 'a', 'pace-day': 'b' } });

    expect(screen.getByText('Step 2 of 3')).toBeDefined();
    expect(screen.getByText(/Welcome back/)).toBeDefined();
    expect(screen.getByRole('radio', { name: 'Balanced' })).toHaveProperty('checked', true);
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByRole('radio', { name: 'Hotel' })).toHaveProperty('checked', true);
    expect(screen.queryByText(/Welcome back/)).toBeNull();
  });

  it('ignores saved answers that no longer fit the questions', async () => {
    await openQuiz({ draft: { 'budget-stay': 'z', 'gone-question': 'a' } });

    expect(screen.getByText('Step 1 of 3')).toBeDefined();
    expect(screen.queryByText(/Welcome back/)).toBeNull();
    expect(screen.getAllByRole('radio').some((radio) => (radio as HTMLInputElement).checked)).toBe(false);
  });

  it('warns that finishing replaces an existing vibe', async () => {
    await openQuiz({ hasProfile: true });

    expect(screen.getByText(/Finishing replaces your current vibe/)).toBeDefined();
    expect(screen.getByRole('link', { name: 'Keep my current vibe' }).getAttribute('href')).toBe('/vibe');
  });

  it('offers to reload a quiz whose questions changed under it', async () => {
    let loads = 0;
    const detail = 'One of the answers is not an option of its question. Reload the quiz and try again.';
    server.use(
      apiGet('/vibe/quiz', () => {
        loads++;
        return HttpResponse.json(quizBody());
      }),
      apiPut('/vibe/quiz/draft', () =>
        HttpResponse.json(
          { status: 400, code: 'invalid_quiz_answer', detail, errors: [{ field: 'answers', message: detail }] },
          { status: 400 },
        ),
      ),
    );
    signIn();
    renderAt('/vibe/quiz');
    await screen.findByText('Step 1 of 3');

    pick('Guesthouse');
    pick('Train');
    next();

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
    expect(screen.getByText('Step 1 of 3')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Reload the quiz' }));
    await waitFor(() => {
      expect(loads).toBe(2);
    });
  });

  it.each([
    [400, 'quiz_incomplete', 'Answer every question before finishing the quiz.', 'Answer every question'],
    [429, 'rate_limited', 'Too many attempts.', 'Too many attempts.'],
    [503, 'temporarily_unavailable', 'This action is temporarily unavailable. Try again shortly.', 'temporarily unavailable'],
  ])('shows a %s from the submission and stays on the last step', async (status, code, detail, shown) => {
    server.use(
      apiPut('/vibe/quiz/draft', () => HttpResponse.json({ answers: {}, updatedAt: '2026-11-23T10:00:00Z' })),
      apiPost('/vibe/quiz/submit', () =>
        status === 400
          ? HttpResponse.json(
              { status, code, detail, errors: [{ field: 'answers', message: detail }] },
              { status },
            )
          : problem(status, code, detail, { headers: { 'Retry-After': '30' } }),
      ),
    );
    await openQuiz({ draft: { 'budget-stay': 'a', 'budget-transport': 'a', 'pace-day': 'a', 'pace-rest': 'a' } });

    pick('Take turns');
    next();

    expect((await screen.findByRole('alert')).textContent).toContain(shown);
    expect(screen.getByText('Step 3 of 3')).toBeDefined();
  });

  it('keeps the answers when a save cannot reach the server', async () => {
    server.use(apiPut('/vibe/quiz/draft', () => HttpResponse.error()));
    await openQuiz();

    pick('Guesthouse');
    pick('Train');
    next();

    expect((await screen.findByRole('alert')).textContent).toContain('Could not reach TripMates');
    expect(screen.getByRole('radio', { name: 'Guesthouse' })).toHaveProperty('checked', true);
  });

  it('shows a failure to load the quiz and retries', async () => {
    let attempts = 0;
    server.use(
      apiGet('/vibe/quiz', () => {
        attempts++;
        return attempts === 1 ? HttpResponse.json({ status: 500 }, { status: 500 }) : HttpResponse.json(quizBody());
      }),
    );
    signIn();
    renderAt('/vibe/quiz');

    expect((await screen.findByRole('alert')).textContent).toContain('Something went wrong on our side.');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Step 1 of 3')).toBeDefined();
  });
});
