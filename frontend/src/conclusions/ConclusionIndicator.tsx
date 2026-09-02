// `@carbon/react` publishes this component under a prefixed name while it is still in
// preview; `IconIndicator` is the name Carbon's documentation and ADR-0014 use, and the
// two exports are the same component. Aliasing it here keeps the prefix in one line
// instead of spreading a temporary name through the product.
import { unstable__IconIndicator as IconIndicator } from '@carbon/react';
import { messages } from '@/messages';
import { conclusionIndicatorKind, type DbxConclusion } from './conclusion';

/**
 * Renders one conclusion.
 *
 * `IconIndicator` rather than `Tag`: Carbon defines `Tag` for categorisation and
 * explicitly not for status (ADR-0014), so `Tag` stays with dimensions such as
 * environment or database kind. `IconIndicator` is also the only Carbon indicator whose
 * `kind` vocabulary contains `unknown`, `in-progress` and `not-started`, which is what
 * lets `INCONCLUSIVE` and `NOT_RUN` keep first-class forms.
 *
 * The label is always rendered, never optional: ADR-0014 requires at least three of
 * symbol, shape, colour and text, and #30 requires a screen reader to be able to read a
 * conclusion's meaning. No conclusion in DBX is carried by colour alone.
 */
export function ConclusionIndicator({
  conclusion,
  label,
  size,
}: {
  readonly conclusion: DbxConclusion;
  /** Defaults to the conclusion's own wording; pass one when the row names something else. */
  readonly label?: string;
  readonly size?: 16 | 20;
}) {
  return (
    <IconIndicator
      kind={conclusionIndicatorKind[conclusion]}
      label={label ?? messages.conclusion.labels[conclusion]}
      size={size ?? 16}
    />
  );
}
