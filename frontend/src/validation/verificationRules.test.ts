import { describe, expect, it } from 'vitest';
import {
  PHOTO_MAX_BYTES,
  validatePhoneCode,
  validatePhoneNumber,
  validatePhoto,
  validateTripMatesId,
} from './verificationRules';

describe('validatePhoneNumber', () => {
  it.each(['+91 98765 43210', '9876543210', '098765-43210', '(+44) 7700 900123'])('lets %s through to the server', (value) => {
    expect(validatePhoneNumber(value)).toEqual({ errors: {}, phoneNumber: value.trim() });
  });

  it.each(['12345', '+91 98765 4321a', 'tel:9876543210', '1'.repeat(16), `+${'1'.repeat(40)}`])('refuses %s', (value) => {
    expect(validatePhoneNumber(value).phoneNumber).toBeNull();
    expect(validatePhoneNumber(value).errors.phoneNumber).toContain('country code');
  });

  it('asks for a number when there is none', () => {
    expect(validatePhoneNumber('   ').errors.phoneNumber).toBe('Enter your mobile number.');
  });
});

describe('validatePhoneCode', () => {
  it('takes six digits, trimmed', () => {
    expect(validatePhoneCode(' 482913 ')).toEqual({ errors: {}, code: '482913' });
  });

  it.each(['', '48291', '4829130', '48291a'])('refuses %j', (value) => {
    expect(validatePhoneCode(value).code).toBeNull();
  });
});

describe('validatePhoto', () => {
  it('accepts a JPEG or PNG up to the limit', () => {
    expect(validatePhoto(new File(['x'], 'a.jpg', { type: 'image/jpeg' }))).toBeNull();
    expect(validatePhoto(new File(['x'], 'a.png', { type: 'image/png' }))).toBeNull();
  });

  it('refuses no file, other types, empty files and large ones', () => {
    expect(validatePhoto(null)).toBe('Choose a photo first.');
    expect(validatePhoto(new File(['x'], 'a.webp', { type: 'image/webp' }))).toBe('Choose a JPEG or PNG photo.');
    expect(validatePhoto(new File([], 'a.jpg', { type: 'image/jpeg' }))).toBe('That file is empty.');
    expect(validatePhoto(new File([new Uint8Array(PHOTO_MAX_BYTES + 1)], 'a.jpg', { type: 'image/jpeg' }))).toBe(
      'Choose a photo under 5 MB.',
    );
  });
});

describe('validateTripMatesId', () => {
  it('normalises a pasted ID', () => {
    expect(validateTripMatesId(' 5E0C8B7A-1D2E-4F3A-9B8C-7D6E5F4A3B2C ')).toEqual({
      errors: {},
      userId: '5e0c8b7a-1d2e-4f3a-9b8c-7d6e5f4a3b2c',
    });
  });

  it.each(['', 'asha@example.com', '5e0c8b7a1d2e4f3a9b8c7d6e5f4a3b2c', '../admin'])('refuses %j', (value) => {
    expect(validateTripMatesId(value).userId).toBeNull();
  });
});
