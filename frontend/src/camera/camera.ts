/**
 * Everything the selfie check needs from the browser's camera, in one place,
 * so screens never touch getUserMedia or a canvas directly and tests can
 * stand in for both.
 *
 * Frames come from a live capture drawn onto a canvas, never from a file
 * picker: the liveness check asks for two frames taken seconds apart after
 * the server picks a prompt, which a stored photo cannot supply. A canvas
 * also carries no EXIF, so nothing about the device or place is uploaded.
 */

export type CameraProblem = 'unsupported' | 'denied' | 'unavailable';

/** The preview has no frame yet: the camera is still warming up, which is worth a moment's wait, not a failure. */
export class CameraNotReadyError extends Error {
  constructor() {
    super('not-ready');
  }
}

export class CameraError extends Error {
  readonly problem: CameraProblem;

  constructor(problem: CameraProblem) {
    super(problem);
    this.problem = problem;
  }
}

const FRAME_QUALITY = 0.9;

/** The front camera, video only. Rejects with a CameraError the screen can explain. */
export async function openFrontCamera(): Promise<MediaStream> {
  const devices = typeof navigator === 'undefined' ? undefined : navigator.mediaDevices;
  if (devices === undefined || typeof devices.getUserMedia !== 'function') {
    throw new CameraError('unsupported');
  }
  try {
    return await devices.getUserMedia({
      video: { facingMode: 'user', width: { ideal: 960 }, height: { ideal: 720 } },
      audio: false,
    });
  } catch (error) {
    const name = error instanceof Error ? error.name : '';
    throw new CameraError(name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : 'unavailable');
  }
}

/** Shows the stream in a video element, muted and inline, as mobile browsers require for autoplay. */
export async function attachStream(video: HTMLVideoElement, stream: MediaStream): Promise<void> {
  video.muted = true;
  video.playsInline = true;
  video.srcObject = stream;
  await video.play();
}

/** The current video frame as a JPEG. */
export function captureFrame(video: HTMLVideoElement): Promise<Blob> {
  const canvas = document.createElement('canvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  if (canvas.width === 0 || canvas.height === 0) {
    return Promise.reject(new CameraNotReadyError());
  }
  const context = canvas.getContext('2d');
  if (context === null) {
    return Promise.reject(new CameraError('unavailable'));
  }
  context.drawImage(video, 0, 0, canvas.width, canvas.height);
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob === null) {
          reject(new CameraError('unavailable'));
        } else {
          resolve(blob);
        }
      },
      'image/jpeg',
      FRAME_QUALITY,
    );
  });
}

/** Turns the camera light off. Safe to call more than once. */
export function stopStream(stream: MediaStream | null): void {
  stream?.getTracks().forEach((track) => {
    track.stop();
  });
}
