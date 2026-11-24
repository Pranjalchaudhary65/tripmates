import { MAX_MESSAGE_LENGTH } from '../api/chatApi';

/**
 * The client-side copy of the server's message rules, for instant feedback.
 * The server cleans markup out and counts again, and stays the authority:
 * text that passes here can still be refused once tags are stripped.
 */
export function validateMessage(text: string): string | null {
  if (text.trim() === '') {
    return 'Write something before sending.';
  }
  if (codePointLength(text) > MAX_MESSAGE_LENGTH) {
    return `Keep a message under ${String(MAX_MESSAGE_LENGTH)} characters. Split a longer one in two.`;
  }
  return null;
}

/** Characters as the server counts them (code points), so an emoji counts once, not twice. */
export function codePointLength(text: string): number {
  return text.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]/g, '_').length;
}
