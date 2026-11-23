import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { installAuthInterceptors } from './api/authRefresh';
import { apiClient } from './api/client';
import './index.css';
import { createAppRouter } from './router';
import { provideSessionRefresher, useAuthStore } from './stores/authStore';

const router = createAppRouter();

// A refresh that finds the session gone has already cleared the store; this
// only records why, and the route guards take the user to login from there.
provideSessionRefresher(
  installAuthInterceptors(apiClient, {
    onSessionExpired: () => {
      useAuthStore.getState().clearSession('expired');
    },
  }),
);

// Outside React, so it runs once per page load even under StrictMode.
void useAuthStore.getState().restoreSession();

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('index.html is missing the #root element.');
}

createRoot(rootElement).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
