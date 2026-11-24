import type { FlagType, SystemCode } from '../api/chatApi';
import type { SocketStatus } from './chatSocket';

/** Words for chat codes; the server only ever sends the codes. */

/** What a SYSTEM message says. It carries a code, never text, so its wording lives here. */
export const SYSTEM_MESSAGE_LABELS: Record<SystemCode, string> = {
  CONTACT_UNLOCKED: 'Your trip is locked. Contact details are now available above.',
  CONVERSATION_CLOSED: 'This conversation was closed.',
};

/** The warning under a flagged message from the other person. */
export const FLAG_WARNINGS: Record<FlagType, string> = {
  MONEY_REQUEST:
    'TripMates never asks you to pay anyone before you have met. Be careful with any request for money or payment details.',
  OFF_PLATFORM_PUSH:
    'Keep the conversation here until your trip is locked. Moving to another app or a private place removes the safety checks.',
  HARASSMENT: 'This message may be abusive. You do not have to reply, and you can stop at any time.',
};

/** The note under the caller's own flagged message. */
export const OWN_FLAG_NOTES: Record<FlagType, string> = {
  MONEY_REQUEST: 'Flagged for review: it looks like a request for money.',
  OFF_PLATFORM_PUSH: 'Flagged for review: it looks like moving the conversation off TripMates.',
  HARASSMENT: 'Flagged for review: it may read as abusive.',
};

/** A notice only when live updates are not flowing; null while they are. */
export const CONNECTION_NOTICES: Record<SocketStatus, string | null> = {
  idle: null,
  connecting: 'Connecting for live updates…',
  online: null,
  offline: 'Live updates paused. Reconnecting…',
};

const TIME = new Intl.DateTimeFormat('en-IN', { hour: '2-digit', minute: '2-digit' });
const DAY_AND_TIME = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
});

/** "14:05" for today, "12 Dec, 14:05" for any other day, in the viewer's own zone. */
export function formatSentAt(sentAt: string, now = new Date()): string {
  const date = new Date(sentAt);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return date.toDateString() === now.toDateString() ? TIME.format(date) : DAY_AND_TIME.format(date);
}
