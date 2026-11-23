import { useState, type SubmitEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { toApiFailure } from '../api/problem';
import { vibeApi, type Quiz, type QuizAnswers, type QuizQuestion } from '../api/vibeApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';

const QUESTIONS_PER_STEP = 2;
const ANSWER_FIELDS: readonly 'answers'[] = ['answers'];

const loadQuiz = () => vibeApi.quiz();

/**
 * The vibe quiz, a few questions per step. Every step saves the answers so
 * far on the server before moving on, so the quiz picks up where it was left,
 * after a reload or on another device; nothing is kept in the browser.
 */
export function VibeQuizPage() {
  const { state, reload } = useApiResource(loadQuiz);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Your travel vibe</h1>
      <p className="mt-2 text-sm text-slate-600">
        14 quick questions about how you like to travel. There are no right answers: they help us suggest people
        you will enjoy a trip with.
      </p>
      {state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading the quiz…
        </p>
      )}
      {state.kind === 'failed' && (
        <div className="mt-6 space-y-3">
          <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
          <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {state.kind === 'ready' && <QuizForm quiz={state.data} onStale={reload} />}
    </section>
  );
}

function QuizForm({ quiz, onStale }: { quiz: Quiz; onStale: () => void }) {
  const steps = chunk(quiz.questions, QUESTIONS_PER_STEP);
  const [start] = useState(() => {
    const saved = knownAnswers(quiz);
    return { answers: saved, step: firstIncompleteStep(steps, saved) };
  });
  const [answers, setAnswers] = useState<QuizAnswers>(start.answers);
  const [step, setStep] = useState(start.step);
  const [unanswered, setUnanswered] = useState<readonly string[]>([]);
  const server = useRequestFailure(ANSWER_FIELDS);
  const { pending, run } = useSubmission();
  const navigate = useNavigate();
  const current = steps[step] ?? [];
  const last = step === steps.length - 1;

  function choose(questionId: string, optionId: string) {
    setAnswers((previous) => ({ ...previous, [questionId]: optionId }));
    setUnanswered((previous) => previous.filter((id) => id !== questionId));
  }

  function next(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const missing = current.filter((question) => answers[question.id] === undefined).map((question) => question.id);
    setUnanswered(missing);
    server.clear();
    if (missing.length > 0) {
      return;
    }
    const snapshot = answers;
    void run(async () => {
      try {
        if (last) {
          await vibeApi.submit(snapshot);
          void navigate('/vibe', { state: { justFinished: true } });
        } else {
          await vibeApi.saveDraft(snapshot);
          setStep(step + 1);
        }
      } catch (error) {
        server.report(toApiFailure(error));
      }
    });
  }

  return (
    <form noValidate onSubmit={next} className="mt-6 space-y-6">
      {start.step > 0 && step === start.step && (
        <p role="status" className="rounded-md bg-teal-50 px-3 py-2 text-sm text-teal-900">
          Welcome back. We kept your answers and picked up where you left off.
        </p>
      )}
      <div>
        <p className="text-sm font-medium text-slate-700">
          Step {step + 1} of {steps.length}
        </p>
        <div
          role="progressbar"
          aria-label="Quiz progress"
          aria-valuemin={0}
          aria-valuemax={steps.length}
          aria-valuenow={step}
          className="mt-1 h-2 w-full rounded bg-slate-200"
        >
          <div className="h-2 rounded bg-teal-600" style={{ width: `${String((step / steps.length) * 100)}%` }} />
        </div>
      </div>

      {current.map((question) => (
        <QuestionField
          key={question.id}
          question={question}
          value={answers[question.id] ?? null}
          missing={unanswered.includes(question.id)}
          onChoose={choose}
        />
      ))}

      {server.notice !== null && (
        <div className="space-y-2">
          <ErrorNotice
            message={server.fieldErrors.answers ?? server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
          {server.failure?.code === 'invalid_quiz_answer' && (
            <button type="button" onClick={onStale} className="text-sm font-medium text-teal-700 underline">
              Reload the quiz
            </button>
          )}
        </div>
      )}

      <div className="flex items-center gap-4">
        {step > 0 && (
          <button
            type="button"
            onClick={() => {
              setStep(step - 1);
              setUnanswered([]);
              server.clear();
            }}
            className="font-medium text-slate-700 underline"
          >
            Back
          </button>
        )}
        <div className="flex-1">
          <SubmitButton
            label={last ? 'See my vibe' : 'Save and continue'}
            pendingLabel={last ? 'Working it out…' : 'Saving…'}
            pending={pending}
            waitSeconds={server.retrySecondsLeft}
          />
        </div>
      </div>
      {quiz.hasProfile && (
        <p className="text-sm text-slate-600">
          Finishing replaces your current vibe, including any changes you made by hand.{' '}
          <Link to="/vibe" className="font-medium text-teal-700 underline">
            Keep my current vibe
          </Link>
        </p>
      )}
    </form>
  );
}

function QuestionField({
  question,
  value,
  missing,
  onChoose,
}: {
  question: QuizQuestion;
  value: string | null;
  missing: boolean;
  onChoose: (questionId: string, optionId: string) => void;
}) {
  const errorId = `${question.id}-error`;
  return (
    <fieldset aria-describedby={missing ? errorId : undefined} aria-invalid={missing}>
      <legend className="font-medium text-slate-900">{question.text}</legend>
      <div className="mt-2 space-y-1">
        {question.options.map((option) => (
          <label key={option.id} className="flex items-center gap-2 text-sm text-slate-800">
            <input
              type="radio"
              name={question.id}
              value={option.id}
              checked={value === option.id}
              onChange={() => {
                onChoose(question.id, option.id);
              }}
            />
            {option.label}
          </label>
        ))}
      </div>
      {missing && (
        <p id={errorId} className="mt-1 text-sm text-red-700">
          Choose an answer.
        </p>
      )}
    </fieldset>
  );
}

function chunk<T>(items: readonly T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let start = 0; start < items.length; start += size) {
    chunks.push(items.slice(start, start + size));
  }
  return chunks;
}

/** The saved answers that still fit this quiz's questions; anything else is dropped rather than sent back. */
function knownAnswers(quiz: Quiz): QuizAnswers {
  const answers: QuizAnswers = {};
  for (const question of quiz.questions) {
    const saved = quiz.draft?.answers[question.id];
    if (saved !== undefined && question.options.some((option) => option.id === saved)) {
      answers[question.id] = saved;
    }
  }
  return answers;
}

function firstIncompleteStep(steps: readonly (readonly QuizQuestion[])[], answers: QuizAnswers): number {
  const index = steps.findIndex((questions) => questions.some((question) => answers[question.id] === undefined));
  return index === -1 ? Math.max(steps.length - 1, 0) : index;
}
