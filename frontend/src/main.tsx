import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/app/App';
import { startMockBoundary } from '@/mocks/browser';
import '@/styles/fonts';
import '@/styles/index.scss';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root is missing from index.html');
}

// In this phase every DBX request is mocked (ADR-0016), so the mock boundary starts in
// every build — dev, the Playwright suite, and a preview of the production bundle alike.
// Rendering waits for it: starting the service worker is asynchronous, and painting first
// would let the opening queries race the worker and intermittently see a blank page.
// Written as a promise chain rather than top-level await because Vite's default browser
// target does not support the latter.
void startMockBoundary().then(() => {
  createRoot(container).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
});
