import { setupServer } from 'msw/node';
import { handlers } from './handlers';

/** The same handlers under Vitest. The browser worker cannot be used in jsdom. */
export const server = setupServer(...handlers);
