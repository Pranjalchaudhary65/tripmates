/** A five-question quiz in the shape GET /vibe/quiz returns: three steps of two, two and one. */
export function quizBody(options: { draft?: Record<string, string> | null; hasProfile?: boolean } = {}) {
  const question = (id: string, text: string, labels: string[]) => ({
    id,
    text,
    options: labels.map((label, index) => ({ id: String.fromCharCode(97 + index), label })),
  });
  return {
    version: 1,
    questions: [
      question('budget-stay', 'Where would you rather sleep on a trip?', ['Dorm bed', 'Guesthouse', 'Hotel']),
      question('budget-transport', 'How do you like to get between cities?', ['Bus', 'Train', 'Flight']),
      question('pace-day', 'What does a good day look like?', ['Packed', 'Balanced', 'Slow']),
      question('pace-rest', 'How many slow days?', ['None', 'Some', 'Most']),
      question('expense-split', 'How should shared costs work?', ['Split exactly', 'Whoever can', 'Take turns']),
    ],
    draft:
      options.draft === undefined || options.draft === null
        ? null
        : { answers: options.draft, updatedAt: '2026-11-23T10:00:00Z' },
    hasProfile: options.hasProfile ?? false,
  };
}

/** VibeProfileResponse as the backend writes it. */
export function profileBody(options: { profile?: boolean; dealBreakers?: boolean; adjusted?: boolean } = {}) {
  return {
    profile:
      options.profile === false
        ? null
        : {
            budgetBand: 'MID',
            pace: 'BALANCED',
            wakeTime: 'EARLY_BIRD',
            planningStyle: 'ROUGH_PLAN',
            socialEnergy: 4,
            riskAppetite: 2,
            foodAdventurousness: 5,
            expenseSplitStyle: 'WHOEVER_CAN',
            adjustedAt: options.adjusted === true ? '2026-11-23T11:00:00Z' : null,
            updatedAt: '2026-11-23T10:00:00Z',
          },
    dealBreakers:
      options.dealBreakers === true
        ? { smoking: 'NO', drinking: 'EITHER', genderPreference: 'SAME', minTrustScore: 40, updatedAt: '2026-11-23T10:00:00Z' }
        : null,
  };
}
