import type { FieldErrors } from './identityRules';

/**
 * Client-side copies of the verification rules, for instant feedback only.
 * The server normalises and checks every value again.
 */

/** PhoneCodeRequest: @Size(max = 32). */
export const PHONE_NUMBER_MAX_LENGTH = 32;
/** ImageSanitizer.MAX_BYTES and the multipart limit. */
export const PHOTO_MAX_BYTES = 5 * 1024 * 1024;
/** The formats ImageSanitizer accepts; it decides from the bytes, this only saves a wasted upload. */
export const PHOTO_TYPES: readonly string[] = ['image/jpeg', 'image/png'];

/**
 * Looser than the server's normaliser on purpose: digits, the usual
 * separators and at most one +, 10 to 15 digits in all. "(+44) 7700 900123"
 * passes here as it does on the server, which strips the separators first.
 * Anything the server cannot read comes back as invalid_phone_number beside
 * the field.
 */
const PHONE_CHARACTERS = /^[\d\s().+-]+$/;
const SIX_DIGITS = /^\d{6}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type PhoneField = 'phoneNumber';
export type PhoneCodeField = 'code';
export type VouchField = 'vouchedUserId';

export function validatePhoneNumber(raw: string): { errors: FieldErrors<PhoneField>; phoneNumber: string | null } {
  const phoneNumber = raw.trim();
  const digits = phoneNumber.replace(/\D/g, '').length;
  const plusSigns = phoneNumber.split('+').length - 1;
  if (phoneNumber === '') {
    return { errors: { phoneNumber: 'Enter your mobile number.' }, phoneNumber: null };
  }
  const readable =
    phoneNumber.length <= PHONE_NUMBER_MAX_LENGTH &&
    PHONE_CHARACTERS.test(phoneNumber) &&
    plusSigns <= 1 &&
    digits >= 10 &&
    digits <= 15;
  if (!readable) {
    return {
      errors: { phoneNumber: 'Enter a mobile number with its country code, such as +91 98765 43210.' },
      phoneNumber: null,
    };
  }
  return { errors: {}, phoneNumber };
}

export function validatePhoneCode(raw: string): { errors: FieldErrors<PhoneCodeField>; code: string | null } {
  const code = raw.trim();
  if (!SIX_DIGITS.test(code)) {
    return { errors: { code: 'Enter the 6-digit code from the message.' }, code: null };
  }
  return { errors: {}, code };
}

/** Empty when the file is worth uploading; otherwise the sentence to show. */
export function validatePhoto(file: File | null): string | null {
  if (file === null) {
    return 'Choose a photo first.';
  }
  if (!PHOTO_TYPES.includes(file.type)) {
    return 'Choose a JPEG or PNG photo.';
  }
  if (file.size === 0) {
    return 'That file is empty.';
  }
  if (file.size > PHOTO_MAX_BYTES) {
    return 'Choose a photo under 5 MB.';
  }
  return null;
}

export function validateTripMatesId(raw: string): { errors: FieldErrors<VouchField>; userId: string | null } {
  const userId = raw.trim().toLowerCase();
  if (userId === '') {
    return { errors: { vouchedUserId: "Paste your friend's TripMates ID." }, userId: null };
  }
  if (!UUID.test(userId)) {
    return {
      errors: { vouchedUserId: 'A TripMates ID looks like 0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c.' },
      userId: null,
    };
  }
  return { errors: {}, userId };
}
