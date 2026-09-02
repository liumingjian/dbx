/**
 * A stand-in for `lottie-web` in the jsdom test environment.
 *
 * The table substrate's package entry pulls in `lottie-web` for its illustrated empty
 * states, and `lottie-web` draws to a canvas at import time. jsdom implements no canvas, so
 * merely importing `@carbon/ibm-products` would abort every test file that renders a table.
 *
 * DBX renders its own empty states (`src/components/ViewState.tsx`), so nothing under test
 * depends on this module's behaviour — only on its being importable.
 */
const lottie = {
  loadAnimation: () => ({
    destroy: () => {},
    play: () => {},
    pause: () => {},
    stop: () => {},
    setSpeed: () => {},
    goToAndStop: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
  }),
  setQuality: () => {},
  registerAnimation: () => {},
  destroy: () => {},
};

export default lottie;
