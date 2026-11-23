import type {
  BudgetBand,
  ExpenseSplitStyle,
  GenderPreference,
  HabitPreference,
  Pace,
  PlanningStyle,
  WakeTime,
} from '../api/vibeApi';

/** Words for the vibe profile's values; the server only ever sends the codes. */

export const BUDGET_LABELS: Record<BudgetBand, string> = {
  SHOESTRING: 'Shoestring',
  MID: 'Mid-range',
  COMFORT: 'Comfort',
  LUXURY: 'Luxury',
};

export const PACE_LABELS: Record<Pace, string> = {
  PACKED: 'Packed days',
  BALANCED: 'Balanced',
  SLOW: 'Slow travel',
};

export const WAKE_LABELS: Record<WakeTime, string> = {
  EARLY_BIRD: 'Early bird',
  AVERAGE: 'Somewhere in between',
  NIGHT_OWL: 'Night owl',
};

export const PLANNING_LABELS: Record<PlanningStyle, string> = {
  DETAILED_ITINERARY: 'Detailed itinerary',
  ROUGH_PLAN: 'Rough plan',
  FULLY_SPONTANEOUS: 'Fully spontaneous',
};

export const EXPENSE_LABELS: Record<ExpenseSplitStyle, string> = {
  STRICT_SPLIT: 'Split every bill exactly',
  WHOEVER_CAN: 'Whoever can pays, it evens out',
  ONE_PAYS_ALTERNATE: 'Take turns paying',
};

export const HABIT_LABELS: Record<HabitPreference, string> = {
  NO: 'I want a trip without it',
  OK: 'It will be part of my trip',
  EITHER: 'No preference',
};

export const GENDER_LABELS: Record<GenderPreference, string> = {
  ANY: 'Anyone',
  SAME: 'Only people of my own gender',
};

/** The ends of the three 1-5 scales. */
export const SCALE_ENDS = {
  socialEnergy: ['Quiet', 'Very social'],
  riskAppetite: ['Cautious', 'Adventurous'],
  foodAdventurousness: ['Familiar food', 'Anything once'],
} as const;
