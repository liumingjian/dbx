import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // `public/mockServiceWorker.js` is MSW's generated service worker, kept byte-for-byte as
  // the package ships it (regenerate with `npx msw init public/ --save`).
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results', 'public'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // ADR-0015: business code talks to DbxTable, never to the table substrate directly.
      // ADR-0014: Carbon is the only UI library.
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@carbon/ibm-products', '@carbon/ibm-products/*', 'react-table'],
              message:
                'Import the DbxTable boundary instead (ADR-0015): the table substrate must not leak into business code.',
            },
            // ADR-0016: the mocks sit *behind* the contract, and the product is meant to
            // survive their removal unchanged. Product code that reaches into `src/mocks`
            // inverts that — the scenario registry, the fixtures and the store are test
            // infrastructure, not a module the application may depend on.
            {
              group: ['@/mocks', '@/mocks/*'],
              message:
                'Product code must not import mock infrastructure (ADR-0016). Read what you need from the URL or the contract; see `src/api/queryKeys.ts`.',
            },
          ],
        },
      ],
    },
  },
  {
    // The mock boundary has to be started by something, and the tests have to be able to
    // reach the fixtures and the scenario registry they assert against. This is the whole
    // of that exemption; the ban above holds for every other file.
    files: ['src/main.tsx', 'src/test/**', '**/*.test.*'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@carbon/ibm-products', '@carbon/ibm-products/*', 'react-table'],
              message:
                'Import the DbxTable boundary instead (ADR-0015): the table substrate must not leak into business code.',
            },
          ],
        },
      ],
    },
  },
  {
    // ADR-0015 requires exactly one module to know the table substrate exists. That module
    // is `DbxTable.tsx`, and this is the whole of its exemption: the ban above still holds
    // for every other file, including the rest of the `DbxTable` directory, whose public
    // types must stay expressible without the substrate.
    files: ['src/components/DbxTable/DbxTable.tsx'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },
  prettier,
);
