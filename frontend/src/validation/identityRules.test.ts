import { describe, expect, it } from 'vitest';
import {
  containsPersonalInfo,
  latestAdultBirthDate,
  PERSONAL_INFO_MESSAGE,
  todayIn,
  UNDERAGE_MESSAGE,
  validateLogin,
  validateSignup,
  validateVerificationCode,
  type SignupForm,
} from './identityRules';

const TODAY = '2026-11-17';

const VALID: SignupForm = {
  email: 'asha.verma@example.com',
  password: 'violet-kettle-harbour-91',
  fullName: 'Asha Verma',
  dateOfBirth: '2000-04-15',
  gender: 'FEMALE',
};

describe('validateSignup', () => {
  it('accepts a valid form and trims email and name but never the password', () => {
    const { errors, body } = validateSignup(
      { ...VALID, email: '  asha.verma@example.com ', fullName: ' Asha Verma ', password: ' violet-kettle-harbour-91 ' },
      TODAY,
    );

    expect(errors).toEqual({});
    expect(body).toEqual({
      email: 'asha.verma@example.com',
      password: ' violet-kettle-harbour-91 ',
      fullName: 'Asha Verma',
      dateOfBirth: '2000-04-15',
      gender: 'FEMALE',
    });
  });

  it('reports every empty field at once and returns no body', () => {
    const { errors, body } = validateSignup(
      { email: '', password: '', fullName: '   ', dateOfBirth: '', gender: '' },
      TODAY,
    );

    expect(body).toBeNull();
    expect(Object.keys(errors).sort()).toEqual(['dateOfBirth', 'email', 'fullName', 'gender', 'password']);
  });

  it('applies the server password lengths, 12 to 128', () => {
    expect(validateSignup({ ...VALID, password: 'a'.repeat(11) }, TODAY).errors.password).toBeDefined();
    expect(validateSignup({ ...VALID, password: 'zq'.repeat(6) }, TODAY).errors.password).toBeUndefined();
    expect(validateSignup({ ...VALID, password: 'zq'.repeat(64) }, TODAY).errors.password).toBeUndefined();
    expect(validateSignup({ ...VALID, password: 'zq'.repeat(64) + 'z' }, TODAY).errors.password).toBeDefined();
  });

  it('rejects a password built from the name or email local part', () => {
    expect(validateSignup({ ...VALID, password: 'my-verma-holiday-2026' }, TODAY).errors.password).toBe(
      PERSONAL_INFO_MESSAGE,
    );
    expect(validateSignup({ ...VALID, password: 'ASHA.VERMA-travels!' }, TODAY).errors.password).toBe(
      PERSONAL_INFO_MESSAGE,
    );
  });

  it('rejects control characters in the name, as the server pattern does', () => {
    expect(validateSignup({ ...VALID, fullName: 'Asha\u0000Verma' }, TODAY).errors.fullName).toBeDefined();
    expect(validateSignup({ ...VALID, fullName: 'Asha\u007fVerma' }, TODAY).errors.fullName).toBeDefined();
    expect(validateSignup({ ...VALID, fullName: 'Ásha Vérma-Nāyar' }, TODAY).errors.fullName).toBeUndefined();
  });

  it('accepts someone exactly 18 today and rejects someone 18 tomorrow', () => {
    expect(validateSignup({ ...VALID, dateOfBirth: '2008-11-17' }, TODAY).errors.dateOfBirth).toBeUndefined();
    expect(validateSignup({ ...VALID, dateOfBirth: '2008-11-18' }, TODAY).errors.dateOfBirth).toBe(UNDERAGE_MESSAGE);
  });

  it('rejects dates that do not exist and values that are not an option', () => {
    expect(validateSignup({ ...VALID, dateOfBirth: '2001-02-29' }, TODAY).errors.dateOfBirth).toBe(
      'Enter a valid date.',
    );
    const tampered = { ...VALID, gender: 'ADMIN' } as unknown as SignupForm;
    expect(validateSignup(tampered, TODAY).errors.gender).toBeDefined();
  });

  it('keeps the email check looser than the server, never stricter', () => {
    expect(validateSignup({ ...VALID, email: 'user@localhost' }, TODAY).errors.email).toBeUndefined();
    expect(validateSignup({ ...VALID, email: 'no-at-sign.example.com' }, TODAY).errors.email).toBeDefined();
    expect(validateSignup({ ...VALID, email: 'two words@example.com' }, TODAY).errors.email).toBeDefined();
    expect(validateSignup({ ...VALID, email: `${'a'.repeat(243)}@example.com` }, TODAY).errors.email).toBeDefined();
  });
});

describe('latestAdultBirthDate', () => {
  it('is today minus 18 years', () => {
    expect(latestAdultBirthDate('2026-11-17')).toBe('2008-11-17');
  });

  it('clamps 29 February the way PostgreSQL does, so a leap-day birthday waits until 1 March', () => {
    expect(latestAdultBirthDate('2026-02-28')).toBe('2008-02-28');
    expect(validateSignup({ ...VALID, dateOfBirth: '2008-02-29' }, '2026-02-28').errors.dateOfBirth).toBe(
      UNDERAGE_MESSAGE,
    );
    expect(validateSignup({ ...VALID, dateOfBirth: '2008-02-29' }, '2026-03-01').errors.dateOfBirth).toBeUndefined();
  });

  it('clamps when today is 29 February, since 18 years earlier is never a leap year', () => {
    expect(latestAdultBirthDate('2028-02-29')).toBe('2010-02-28');
    expect(latestAdultBirthDate('2032-02-29')).toBe('2014-02-28');
  });
});

describe('todayIn', () => {
  it('uses the date in India, not the browser zone', () => {
    // 20:00 UTC on the 17th is already 01:30 on the 18th in India.
    expect(todayIn('Asia/Kolkata', new Date('2026-11-17T20:00:00Z'))).toBe('2026-11-18');
    expect(todayIn('Asia/Kolkata', new Date('2026-11-17T18:00:00Z'))).toBe('2026-11-17');
  });
});

describe('validateLogin', () => {
  it('has no minimum password length, only the maximum', () => {
    expect(validateLogin({ email: 'a@b.co', password: 'short' }).errors).toEqual({});
    expect(validateLogin({ email: 'a@b.co', password: 'x'.repeat(129) }).errors.password).toBeDefined();
    expect(validateLogin({ email: 'a@b.co', password: '' }).errors.password).toBeDefined();
  });

  it('trims the email only', () => {
    expect(validateLogin({ email: ' a@b.co ', password: ' pw ' }).body).toEqual({ email: 'a@b.co', password: ' pw ' });
  });
});

describe('validateVerificationCode', () => {
  it('needs exactly six digits', () => {
    expect(validateVerificationCode('482913')).toBeNull();
    expect(validateVerificationCode('48291')).not.toBeNull();
    expect(validateVerificationCode('4829134')).not.toBeNull();
    expect(validateVerificationCode('48291a')).not.toBeNull();
  });
});

describe('containsPersonalInfo', () => {
  it('ignores name parts and local parts shorter than four characters', () => {
    expect(containsPersonalInfo('al-is-totally-fine', 'al@example.com', 'Al Li')).toBe(false);
  });
});
