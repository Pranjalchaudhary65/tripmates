import { UPI_ID_PATTERN } from '../api/settlementApi';
import { MAX_EXPENSE_PAISE, rupeesToPaise } from '../settlement/money';

export interface ExpenseDraft {
  description: string;
  amount: string;
  split: 'EQUAL' | 'EXACT';
  /** Who shares it: user ID to their exact share as typed (ignored for an equal split). */
  participants: Map<string, string>;
}

export type ExpenseField = 'description' | 'amountPaise' | 'participants';

const INVISIBLE = /[\p{Cc}\p{Cf}]/u;

/** The same checks the server makes, so most mistakes never leave the browser. The server checks again. */
export function validateExpense(draft: ExpenseDraft, myUserId: string): Partial<Record<ExpenseField, string>> {
  const errors: Partial<Record<ExpenseField, string>> = {};
  const description = draft.description.trim();
  // UTF-16 length, the stricter of the two lengths the server checks.
  if (description === '' || description.length > 120 || INVISIBLE.test(description)) {
    errors.description = 'Describe the expense in 1 to 120 characters.';
  }
  const amount = rupeesToPaise(draft.amount);
  if (amount === null || amount > MAX_EXPENSE_PAISE) {
    errors.amountPaise = 'Enter an amount in rupees, like 1800 or 1800.50, up to 10,00,000.';
  }
  const people = [...draft.participants.keys()];
  if (people.length === 0 || people.every((id) => id === myUserId)) {
    errors.participants = 'Share it with at least one other person on the trip.';
  } else if (draft.split === 'EXACT' && amount !== null) {
    const shares = [...draft.participants.values()].map(rupeesToPaise);
    if (shares.some((share) => share === null)) {
      errors.participants = 'Give everyone sharing it an amount of more than zero.';
    } else if (shares.reduce((sum: number, share) => sum + (share ?? 0), 0) !== amount) {
      errors.participants = 'The shares must add up to the amount exactly.';
    }
  }
  return errors;
}

export function validateUpiId(value: string): string | undefined {
  return UPI_ID_PATTERN.test(value.trim().toLowerCase()) ? undefined : 'Enter a UPI ID like name@bank.';
}
