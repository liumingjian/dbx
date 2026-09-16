/**
 * A miniature backend that violates every boundary rule on purpose.
 *
 * <p>The tree mirrors the production package layout one level down, so the rules of
 * {@code ModuleBoundaryRules} can be pointed at this root and be the same rules that guard the real
 * sources. It is test-only and excluded from the production run in {@code ModuleBoundaryTest}.
 *
 * <p>Nothing here is a module and nothing here compiles into the application.
 */
package com.dbx.archfixture;
