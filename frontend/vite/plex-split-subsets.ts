import type { Plugin } from 'vite';

/**
 * The IBM Plex packages ship two kinds of `@font-face` in one stylesheet: the official
 * split subsets, each carrying a `unicode-range`, and a single unranged fallback per
 * weight pointing at the complete family file. For IBM Plex Sans SC the complete files
 * are 3.8-5.2 MB each, so leaving them in bundles roughly 30 MB of fonts that exist only
 * to cover codepoints the subsets already cover.
 *
 * ADR-0014 asks for the official split subsets, self-hosted. Dropping every unranged face
 * is exactly that, and it keeps the decision in one readable place rather than in a
 * checked-in generated stylesheet that would silently drift from the package.
 */
const FONT_FACE = /@font-face\s*\{[^}]*\}\s*/g;

/** Exported for test: keeps only the `@font-face` rules that declare a `unicode-range`. */
export function keepSplitSubsetsOnly(css: string): string {
  return css.replace(FONT_FACE, (rule) => (rule.includes('unicode-range') ? rule : ''));
}

const PLEX_DEFAULT_CSS = /@ibm[\\/]plex-sans(-sc)?[\\/]css[\\/]ibm-plex-sans(-sc)?-default\.css$/;

export function plexSplitSubsetsOnly(): Plugin {
  return {
    name: 'dbx:plex-split-subsets-only',
    enforce: 'pre',
    transform(code, id) {
      const path = id.split('?')[0] ?? id;
      if (!PLEX_DEFAULT_CSS.test(path)) {
        return null;
      }
      return { code: keepSplitSubsetsOnly(code), map: null };
    },
  };
}
