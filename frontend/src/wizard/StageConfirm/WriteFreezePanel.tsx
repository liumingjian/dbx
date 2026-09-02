import { useState } from 'react';
import { Button, Select, SelectItem, TextInput } from '@carbon/react';
import type { WriteFreezeDeclaration } from '@/contract';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';

/**
 * The 写冻结 confirmation — **Gate 5** stated as a form rather than as a tick.
 *
 * `CONTEXT.md` defines a 写冻结 as 「the externally enforced, time-bounded operational
 * commitment that source data … does not change. It has an accountable operator and
 * expiry」, and lists 「permanent checkbox」 under `_Avoid_`. Both halves of that are why
 * this is a form: a confirmation with no named 责任人 answers for nothing, and one with no
 * 时限 is the permanent checkbox the glossary rules out.
 *
 * The 时限 is a duration and the expiry is derived from it, because the commitment starts
 * when the 迁移运行 starts. A datetime typed here would be stale by the time the operator
 * finished reading the rest of the summary, and a stale expiry is worse than none — it
 * would record a freeze that had already lapsed before the first row was read.
 *
 * DBX records the commitment and does not enforce it. The panel says so in as many words,
 * because a screen that implied the source was now locked would be inviting the operator
 * to skip arranging the thing that actually locks it.
 */
interface WriteFreezePanelProps {
  readonly freeze: WriteFreezeDeclaration | null;
  /** The platform's clock when the summary was assembled; the expiry is projected from it. */
  readonly assembledAt: string;
  readonly onConfirm: (freeze: WriteFreezeDeclaration) => void;
  readonly onRevoke: () => void;
}

const HOUR_MS = 60 * 60 * 1000;

/**
 * The 时限 on offer.
 *
 * A closed list rather than a free number, and with no 「不限」 among them: `CONTEXT.md`
 * puts 「permanent checkbox」 under 写冻结's `_Avoid_`, so an unbounded commitment is not a
 * thing this interface is able to express.
 */
const WRITE_FREEZE_DURATION_HOURS: readonly number[] = [2, 4, 8, 12];

export function WriteFreezePanel({
  freeze,
  assembledAt,
  onConfirm,
  onRevoke,
}: WriteFreezePanelProps) {
  const copy = messages.wizard.confirm;
  const [operator, setOperator] = useState(freeze?.accountableOperator ?? '');
  const [durationHours, setDurationHours] = useState(
    freeze?.durationHours ?? (WRITE_FREEZE_DURATION_HOURS[0] as number),
  );
  const [changeReference, setChangeReference] = useState(freeze?.changeReference ?? '');

  const expiresAt = formatTimestamp(
    new Date(Date.parse(assembledAt) + durationHours * HOUR_MS).toISOString(),
  );

  return (
    <section className="dbx-confirm__panel" aria-label={copy.freezeHeading}>
      <h3 className="dbx-confirm__heading">{copy.freezeHeading}</h3>
      <p className="dbx-confirm__constraint">{copy.freezeConstraint}</p>

      {freeze === null ? (
        <>
          <p className="dbx-confirm__state">{copy.freezeNotConfirmed}</p>
          <div className="dbx-confirm__fields">
            <TextInput
              id="dbx-write-freeze-operator"
              labelText={copy.operatorLabel}
              helperText={copy.operatorHelper}
              value={operator}
              onChange={(event) => setOperator(event.target.value)}
            />
            <Select
              id="dbx-write-freeze-duration"
              labelText={copy.durationLabel}
              value={String(durationHours)}
              onChange={(event) => setDurationHours(Number(event.target.value))}
            >
              {WRITE_FREEZE_DURATION_HOURS.map((hours) => (
                <SelectItem key={hours} value={String(hours)} text={copy.durationOption(hours)} />
              ))}
            </Select>
            <TextInput
              id="dbx-write-freeze-change-reference"
              labelText={copy.changeReferenceLabel}
              helperText={copy.changeReferenceHelper}
              value={changeReference}
              onChange={(event) => setChangeReference(event.target.value)}
            />
          </div>
          <p className="dbx-confirm__state">{copy.expiryPreview(expiresAt)}</p>
          <Button
            kind="tertiary"
            // Deliberately not disabled while the 责任人 is blank: the gate's sentence above
            // the footer already says what is missing, and a dead button that explains
            // nothing is the failure mode #30 calls out.
            onClick={() => {
              if (operator.trim() === '') {
                return;
              }
              onConfirm({
                accountableOperator: operator.trim(),
                durationHours,
                changeReference: changeReference.trim() === '' ? null : changeReference.trim(),
              });
            }}
          >
            {copy.confirmFreezeAction}
          </Button>
        </>
      ) : (
        <>
          <p className="dbx-confirm__state">
            {copy.freezeConfirmed(freeze.accountableOperator, freeze.durationHours)}
          </p>
          <p className="dbx-confirm__state">{copy.expiryPreview(expiresAt)}</p>
          {freeze.changeReference === null ? null : (
            <p className="dbx-confirm__state">
              {copy.changeReferenceLabel}：{freeze.changeReference}
            </p>
          )}
          <Button kind="ghost" onClick={onRevoke}>
            {copy.revokeFreezeAction}
          </Button>
        </>
      )}
    </section>
  );
}
