import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord } from './accessGrant';
import { apiClient } from './client';

export const BUDGET_BANDS = ['SHOESTRING', 'MID', 'COMFORT', 'LUXURY'] as const;
export const PACES = ['PACKED', 'BALANCED', 'SLOW'] as const;
export const WAKE_TIMES = ['EARLY_BIRD', 'AVERAGE', 'NIGHT_OWL'] as const;
export const PLANNING_STYLES = ['DETAILED_ITINERARY', 'ROUGH_PLAN', 'FULLY_SPONTANEOUS'] as const;
export const EXPENSE_SPLIT_STYLES = ['STRICT_SPLIT', 'WHOEVER_CAN', 'ONE_PAYS_ALTERNATE'] as const;
export const HABIT_PREFERENCES = ['NO', 'OK', 'EITHER'] as const;
export const GENDER_PREFERENCES = ['ANY', 'SAME'] as const;

export type BudgetBand = (typeof BUDGET_BANDS)[number];
export type Pace = (typeof PACES)[number];
export type WakeTime = (typeof WAKE_TIMES)[number];
export type PlanningStyle = (typeof PLANNING_STYLES)[number];
export type ExpenseSplitStyle = (typeof EXPENSE_SPLIT_STYLES)[number];
export type HabitPreference = (typeof HABIT_PREFERENCES)[number];
export type GenderPreference = (typeof GENDER_PREFERENCES)[number];

/** Question ID to option ID. */
export type QuizAnswers = Record<string, string>;

export interface QuizQuestion {
  id: string;
  text: string;
  options: { id: string; label: string }[];
}

/** QuizResponse. */
export interface Quiz {
  version: number;
  questions: QuizQuestion[];
  draft: { answers: QuizAnswers; updatedAt: string } | null;
  hasProfile: boolean;
}

/** The eight dimensions, as VibeAdjustRequest takes them. */
export interface VibeDimensions {
  budgetBand: BudgetBand;
  pace: Pace;
  wakeTime: WakeTime;
  planningStyle: PlanningStyle;
  socialEnergy: number;
  riskAppetite: number;
  foodAdventurousness: number;
  expenseSplitStyle: ExpenseSplitStyle;
}

export interface DealBreakers {
  smoking: HabitPreference;
  drinking: HabitPreference;
  genderPreference: GenderPreference;
  minTrustScore: number;
}

/** VibeProfileResponse: either part is null until it exists. */
export interface VibeProfileView {
  profile: (VibeDimensions & { adjustedAt: string | null; updatedAt: string }) | null;
  dealBreakers: (DealBreakers & { updatedAt: string }) | null;
}

export function createVibeApi(client: AxiosInstance) {
  return {
    async quiz(): Promise<Quiz> {
      const response = await client.get<unknown>('/vibe/quiz');
      return readQuiz(response.data);
    },

    async saveDraft(answers: QuizAnswers): Promise<void> {
      await client.put('/vibe/quiz/draft', { answers });
    },

    async submit(answers: QuizAnswers): Promise<VibeProfileView> {
      const response = await client.post<unknown>('/vibe/quiz/submit', { answers });
      return readProfileView(response.data);
    },

    async profile(): Promise<VibeProfileView> {
      const response = await client.get<unknown>('/vibe/profile');
      return readProfileView(response.data);
    },

    async adjust(dimensions: VibeDimensions): Promise<VibeProfileView> {
      const response = await client.put<unknown>('/vibe/profile', dimensions);
      return readProfileView(response.data);
    },

    async setDealBreakers(dealBreakers: DealBreakers): Promise<VibeProfileView> {
      const response = await client.put<unknown>('/vibe/deal-breakers', dealBreakers);
      return readProfileView(response.data);
    },
  };
}

export type VibeApi = ReturnType<typeof createVibeApi>;

export const vibeApi: VibeApi = createVibeApi(apiClient);

function readQuiz(body: unknown): Quiz {
  if (
    isRecord(body) &&
    typeof body['version'] === 'number' &&
    Array.isArray(body['questions']) &&
    body['questions'].length > 0 &&
    typeof body['hasProfile'] === 'boolean'
  ) {
    const draft = body['draft'];
    return {
      version: body['version'],
      questions: body['questions'].map(readQuestion),
      draft:
        isRecord(draft) && isRecord(draft['answers']) && isNonEmptyString(draft['updatedAt'])
          ? { answers: readAnswers(draft['answers']), updatedAt: draft['updatedAt'] }
          : null,
      hasProfile: body['hasProfile'],
    };
  }
  throw new Error('Unexpected quiz.');
}

function readQuestion(raw: unknown): QuizQuestion {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['text']) &&
    Array.isArray(raw['options']) &&
    raw['options'].length > 1
  ) {
    return {
      id: raw['id'],
      text: raw['text'],
      options: raw['options'].map((option: unknown) => {
        if (isRecord(option) && isNonEmptyString(option['id']) && isNonEmptyString(option['label'])) {
          return { id: option['id'], label: option['label'] };
        }
        throw new Error('Unexpected quiz option.');
      }),
    };
  }
  throw new Error('Unexpected quiz question.');
}

function readAnswers(raw: Record<string, unknown>): QuizAnswers {
  const answers: QuizAnswers = {};
  for (const [questionId, optionId] of Object.entries(raw)) {
    if (typeof optionId === 'string') {
      answers[questionId] = optionId;
    }
  }
  return answers;
}

function readProfileView(body: unknown): VibeProfileView {
  if (!isRecord(body)) {
    throw new Error('Unexpected vibe profile.');
  }
  return { profile: readProfile(body['profile']), dealBreakers: readDealBreakers(body['dealBreakers']) };
}

function readProfile(raw: unknown): VibeProfileView['profile'] {
  if (raw === null || raw === undefined) {
    return null;
  }
  if (isRecord(raw) && isNonEmptyString(raw['updatedAt'])) {
    const budgetBand = oneOf(BUDGET_BANDS, raw['budgetBand']);
    const pace = oneOf(PACES, raw['pace']);
    const wakeTime = oneOf(WAKE_TIMES, raw['wakeTime']);
    const planningStyle = oneOf(PLANNING_STYLES, raw['planningStyle']);
    const expenseSplitStyle = oneOf(EXPENSE_SPLIT_STYLES, raw['expenseSplitStyle']);
    const socialEnergy = scale(raw['socialEnergy']);
    const riskAppetite = scale(raw['riskAppetite']);
    const foodAdventurousness = scale(raw['foodAdventurousness']);
    if (
      budgetBand !== null &&
      pace !== null &&
      wakeTime !== null &&
      planningStyle !== null &&
      expenseSplitStyle !== null &&
      socialEnergy !== null &&
      riskAppetite !== null &&
      foodAdventurousness !== null
    ) {
      return {
        budgetBand,
        pace,
        wakeTime,
        planningStyle,
        socialEnergy,
        riskAppetite,
        foodAdventurousness,
        expenseSplitStyle,
        adjustedAt: typeof raw['adjustedAt'] === 'string' ? raw['adjustedAt'] : null,
        updatedAt: raw['updatedAt'],
      };
    }
  }
  throw new Error('Unexpected vibe profile.');
}

function readDealBreakers(raw: unknown): VibeProfileView['dealBreakers'] {
  if (raw === null || raw === undefined) {
    return null;
  }
  if (isRecord(raw) && isNonEmptyString(raw['updatedAt']) && typeof raw['minTrustScore'] === 'number') {
    const smoking = oneOf(HABIT_PREFERENCES, raw['smoking']);
    const drinking = oneOf(HABIT_PREFERENCES, raw['drinking']);
    const genderPreference = oneOf(GENDER_PREFERENCES, raw['genderPreference']);
    if (smoking !== null && drinking !== null && genderPreference !== null) {
      return { smoking, drinking, genderPreference, minTrustScore: raw['minTrustScore'], updatedAt: raw['updatedAt'] };
    }
  }
  throw new Error('Unexpected deal-breakers.');
}

function scale(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 5 ? value : null;
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
