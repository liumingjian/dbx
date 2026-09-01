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
