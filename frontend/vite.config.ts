import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { plexSplitSubsetsOnly } from './vite/plex-split-subsets';

// Carbon ships Sass sources; `loadPaths` lets `@use '@carbon/react'` resolve from node_modules.
export default defineConfig({
  plugins: [react(), plexSplitSubsetsOnly()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern-compiler',
        loadPaths: ['node_modules'],
        quietDeps: true,
        silenceDeprecations: ['global-builtin', 'import'],
      },
    },
  },
  server: {
    port: 5173,
  },
});
