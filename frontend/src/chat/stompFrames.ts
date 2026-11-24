/**
 * The few STOMP 1.2 frames the chat needs, encoded and parsed by hand: the
 * client sends CONNECT, SUBSCRIBE, SEND and DISCONNECT, and reads CONNECTED,
 * MESSAGE and ERROR. A frame is a command line, header lines, a blank line,
 * a body, and a NUL byte.
 *
 * Parsing is strict on purpose. Anything that is not a well-formed frame of
 * a command the client expects comes back as null and is ignored, so a
 * broken or hostile frame can never reach the screen half-parsed.
 */

export type ClientCommand = 'CONNECT' | 'SUBSCRIBE' | 'SEND' | 'DISCONNECT';
export type ServerCommand = 'CONNECTED' | 'MESSAGE' | 'ERROR' | 'RECEIPT';

const SERVER_COMMANDS: readonly ServerCommand[] = ['CONNECTED', 'MESSAGE', 'ERROR', 'RECEIPT'];

export interface StompFrame {
  command: ServerCommand;
  headers: Readonly<Record<string, string>>;
  body: string;
}

const NUL = '\0';

/** The longest frame the client will parse; the server's own limit is far below it. */
export const MAX_FRAME_LENGTH = 64 * 1024;

export function encodeFrame(command: ClientCommand, headers: Readonly<Record<string, string>>, body = ''): string {
  // CONNECT headers are not escaped in STOMP 1.2; nothing the client puts in
  // them (a version, a host, a bearer token) contains a colon or a newline.
  const escape = command === 'CONNECT' ? (value: string) => value : escapeHeader;
  const headerLines = Object.entries(headers)
    .map(([name, value]) => `${escape(name)}:${escape(value)}\n`)
    .join('');
  return `${command}\n${headerLines}\n${body}${NUL}`;
}

/**
 * One frame from one WebSocket message. A bare newline is a heart-beat and
 * gives null, as does anything malformed.
 */
export function decodeFrame(data: unknown): StompFrame | null {
  if (typeof data !== 'string' || data.length > MAX_FRAME_LENGTH) {
    return null;
  }
  const text = data.replace(/^(\r?\n)+/, '');
  if (text === '') {
    return null;
  }
  const blankLine = /\r?\n\r?\n/.exec(text);
  if (blankLine === null) {
    return null;
  }
  const headerEnd = blankLine.index;
  const lines = text.slice(0, headerEnd).split('\n').map((line) => line.replace(/\r$/, ''));
  const command = SERVER_COMMANDS.find((candidate) => candidate === lines[0]);
  if (command === undefined) {
    return null;
  }
  const headers: Record<string, string> = {};
  for (const line of lines.slice(1)) {
    const colon = line.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    const name = unescapeHeader(line.slice(0, colon), command);
    const value = unescapeHeader(line.slice(colon + 1), command);
    if (name === null || value === null) {
      return null;
    }
    // STOMP 1.2: when a header repeats, the first one counts.
    if (!(name in headers)) {
      headers[name] = value;
    }
  }
  const rest = text.slice(headerEnd + blankLine[0].length);
  const end = rest.indexOf(NUL);
  if (end === -1 || rest.slice(end + 1).replace(/[\r\n]/g, '') !== '') {
    return null;
  }
  return { command, headers, body: rest.slice(0, end) };
}

function escapeHeader(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\r/g, '\\r').replace(/\n/g, '\\n').replace(/:/g, '\\c');
}

/** Null for an escape STOMP does not define, which makes the frame malformed. */
function unescapeHeader(value: string, command: ServerCommand): string | null {
  if (command === 'CONNECTED') {
    return value;
  }
  let out = '';
  for (let i = 0; i < value.length; i++) {
    const char = value.charAt(i);
    if (char !== '\\') {
      out += char;
      continue;
    }
    const next = value.charAt(i + 1);
    if (next === 'n') {
      out += '\n';
    } else if (next === 'r') {
      out += '\r';
    } else if (next === 'c') {
      out += ':';
    } else if (next === '\\') {
      out += '\\';
    } else {
      return null;
    }
    i++;
  }
  return out;
}
