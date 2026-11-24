import { describe, expect, it } from 'vitest';
import { decodeFrame, encodeFrame, MAX_FRAME_LENGTH } from './stompFrames';

describe('STOMP frames', () => {
  it('encodes a frame as command, headers, blank line, body and NUL', () => {
    expect(encodeFrame('SUBSCRIBE', { id: 'chat', destination: '/user/queue/chat' })).toBe(
      'SUBSCRIBE\nid:chat\ndestination:/user/queue/chat\n\n\0',
    );
  });

  it('escapes header values everywhere but CONNECT', () => {
    expect(encodeFrame('SEND', { note: 'a:b\nc\\d' })).toBe('SEND\nnote:a\\cb\\nc\\\\d\n\n\0');
    expect(encodeFrame('CONNECT', { Authorization: 'Bearer abc.def' })).toBe('CONNECT\nAuthorization:Bearer abc.def\n\n\0');
  });

  it('decodes a MESSAGE frame with its headers and body', () => {
    const frame = decodeFrame('MESSAGE\ndestination:/user/queue/chat\nsubscription:chat\n\n{"type":"TYPING"}\0');

    expect(frame).toEqual({
      command: 'MESSAGE',
      headers: { destination: '/user/queue/chat', subscription: 'chat' },
      body: '{"type":"TYPING"}',
    });
  });

  it('unescapes headers and keeps the first of a repeated header', () => {
    const frame = decodeFrame('ERROR\nmessage:Not\\callowed\nmessage:second\n\n\0');

    expect(frame?.headers['message']).toBe('Not:allowed');
  });

  it('tolerates CRLF and the newlines a server may send before or after a frame', () => {
    expect(decodeFrame('\r\nCONNECTED\r\nversion:1.2\r\n\r\n\0\n')?.command).toBe('CONNECTED');
  });

  it.each([
    ['a heart-beat', '\n'],
    ['an unknown command', 'SEND\ndestination:/x\n\nhi\0'],
    ['no blank line', 'MESSAGE\ndestination:/x\0'],
    ['no NUL terminator', 'MESSAGE\ndestination:/x\n\nbody'],
    ['data after the NUL', 'MESSAGE\n\nbody\0MESSAGE\n\nmore\0'],
    ['a header without a colon', 'MESSAGE\nnot-a-header\n\nbody\0'],
    ['an undefined escape', 'MESSAGE\nh:\\t\n\nbody\0'],
    ['binary data', new ArrayBuffer(4)],
    ['an oversized frame', `MESSAGE\n\n${'x'.repeat(MAX_FRAME_LENGTH)}\0`],
  ])('gives null for %s', (_name, data) => {
    expect(decodeFrame(data)).toBeNull();
  });
});
