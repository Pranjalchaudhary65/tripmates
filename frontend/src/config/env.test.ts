import { describe, expect, it } from 'vitest';
import { parseApiBaseUrl } from './env';

describe('parseApiBaseUrl', () => {
  it('accepts an absolute URL and drops a trailing slash', () => {
    expect(parseApiBaseUrl('http://localhost:8082/api/v1/')).toBe('http://localhost:8082/api/v1');
    expect(parseApiBaseUrl(' https://api.example.com/api/v1 ')).toBe('https://api.example.com/api/v1');
  });

  it.each([undefined, '', '   '])('rejects a missing value: %j', (raw) => {
    expect(() => parseApiBaseUrl(raw)).toThrow('VITE_API_BASE_URL is not set');
  });

  it.each(['/api/v1', 'localhost:8082/api/v1', 'ftp://localhost/api/v1', 'http://localhost:8082/api/v1?x=1'])(
    'rejects an unusable value: %s',
    (raw) => {
      expect(() => parseApiBaseUrl(raw)).toThrow(/VITE_API_BASE_URL must/);
    },
  );
});
