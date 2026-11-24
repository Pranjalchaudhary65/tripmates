import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';
import { parseApiBaseUrl } from './src/config/env.ts';

export default defineConfig(({ command, mode }) => {
  // A production bundle without a valid API URL would load as a blank page,
  // so the build refuses to produce one.
  if (command === 'build') {
    parseApiBaseUrl(loadEnv(mode, process.cwd(), 'VITE_')['VITE_API_BASE_URL']);
  }

  return {
    plugins: [react(), tailwindcss()],
    server: {
      // The backend's CORS allowlist holds the exact origin
      // http://localhost:5173. If this port were taken, Vite would quietly move
      // to 5174 and every API call would fail CORS, so it refuses to start instead.
      port: 5173,
      strictPort: true,
    },
    build: {
      sourcemap: false,
    },
    test: {
      environment: 'node',
      include: ['src/**/*.test.{ts,tsx}'],
      // Replaces the chat socket's WebSocket with a fake, so no test opens a real one.
      setupFiles: ['src/test/setup.ts'],
      restoreMocks: true,
      // A fixed, unroutable API origin for every test, so the shared client
      // can be imported without a developer's .env.local and MSW decides
      // every response. Nothing in a test can reach a real backend.
      env: {
        VITE_API_BASE_URL: 'http://api.test/api/v1',
      },
      // jsdom enforces CORS, so DOM tests run from the dev origin the
      // backend allows; src/test/apiServer.ts mocks the matching headers.
      environmentOptions: {
        jsdom: { url: 'http://localhost:5173/' },
      },
    },
  };
});
