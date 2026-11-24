/**
 * Client-side copies of the meet-up rules, for instant feedback only. The
 * server normalises the number and checks everything again.
 */

const CONTROL_OR_BRACKET = /[\p{Cc}\p{Cf}<>]/u;
const PHONE_CHARACTERS = /^\+?[\d\s().-]{8,32}$/;

export const NOTES_MAX_LENGTH = 1000;

export function validateEmergencyContact(input: {
  name: string;
  relationship: string;
  phoneNumber: string;
}): Partial<Record<'name' | 'relationship' | 'phoneNumber', string>> {
  const errors: Partial<Record<'name' | 'relationship' | 'phoneNumber', string>> = {};
  const name = input.name.trim();
  if (name === '') {
    errors.name = 'Enter their name.';
  } else if (name.length > 80 || CONTROL_OR_BRACKET.test(name)) {
    errors.name = 'Use up to 80 ordinary characters.';
  }
  if (input.relationship === '') {
    errors.relationship = 'Choose who they are to you.';
  }
  const digits = input.phoneNumber.replace(/\D/g, '');
  if (!PHONE_CHARACTERS.test(input.phoneNumber.trim()) || digits.length < 10 || digits.length > 15) {
    errors.phoneNumber = 'Enter a mobile number with its country code, such as +91 98765 43210.';
  }
  return errors;
}

/** A comfort rating is a whole number from 1 to 5; notes are optional. */
export function validateComfort(rating: number | null, notes: string): string | null {
  if (rating === null || !Number.isInteger(rating) || rating < 1 || rating > 5) {
    return 'Choose how comfortable you felt.';
  }
  if (notes.length > NOTES_MAX_LENGTH) {
    return `Keep notes under ${String(NOTES_MAX_LENGTH)} characters.`;
  }
  return null;
}
