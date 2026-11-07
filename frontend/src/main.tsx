import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { installAuthInterceptors } from './api/authRefresh';
import { apiClient } from './api/client';
import './index.css';
import { createAppRouter } from './router';

const router = createAppRouter();

installAuthInterceptors(apiClient, {
  onSessionExpired: () => {
    void router.navigate('/', { replace: true });
  },
});

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('index.html is missing the #root element.');
}

createRoot(rootElement).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
