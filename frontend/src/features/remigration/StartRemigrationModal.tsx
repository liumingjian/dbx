import { useState } from 'react';
import { Modal, TextInput } from '@carbon/react';
import type { WriteFreezeDeclaration } from '@/contract';
import { messages } from '@/messages';

/**
 * Confirming a 重新迁移 (#41).
 *
 * It asks for the same two things 执行确认 asks for, and for the same reasons:
 *
 *  - **a new 写冻结 declaration.** The new 迁移运行 captures a new 源基线, and a 写冻结
 *    「must remain valid from source-baseline capture until every selected table reaches a
 *    validation terminal state」. The earlier run's commitment covered an earlier boundary,
 *    so it cannot be reused — and `CONTEXT.md` puts 「permanent checkbox」 under 写冻结's
 *    `_Avoid_`, which is why a named 责任人 and a bounded 时限 are both required.
 *  - **the source database's own identifier, typed.** This starts a migration against a
 *    production database. A modal alone does not make that deliberate; a second click is
 *    still a click.
 *
 * The body states what is about to happen to the earlier run: nothing.
 */
interface StartRemigrationModalProps {
  readonly sourceDatabase: string;
  readonly tableCount: number;
  readonly pending: boolean;
  readonly failed: boolean;
  readonly onCancel: () => void;
  readonly onConfirm: (writeFreeze: WriteFreezeDeclaration) => void;
}

export function StartRemigrationModal({
  sourceDatabase,
  tableCount,
  pending,
  failed,
  onCancel,
  onConfirm,
}: StartRemigrationModalProps) {
  const copy = messages.remigration.modal;
  const [operator, setOperator] = useState('');
  const [duration, setDuration] = useState('8');
  const [changeReference, setChangeReference] = useState('');
  const [typed, setTyped] = useState('');

  const durationHours = Number.parseFloat(duration);
  const complete =
    operator.trim() !== '' &&
    Number.isFinite(durationHours) &&
    durationHours > 0 &&
    typed.trim() === sourceDatabase;

  const confirm = (): void => {
    // Checked again here rather than trusting the disabled state: a dialog that can be
    // submitted by keyboard must not depend on a button's appearance for its safety.
    if (!complete) {
      return;
    }
    onConfirm({
      accountableOperator: operator.trim(),
      durationHours,
      changeReference: changeReference.trim() === '' ? null : changeReference.trim(),
    });
  };

  return (
    <Modal
      open
      danger
      modalHeading={copy.title}
      primaryButtonText={copy.confirm}
      secondaryButtonText={copy.cancel}
      primaryButtonDisabled={!complete || pending}
      onRequestClose={onCancel}
      onSecondarySubmit={onCancel}
      onRequestSubmit={confirm}
    >
      <p>{copy.body(tableCount, sourceDatabase)}</p>
      <p>{copy.freezeNotice}</p>
      <TextInput
        id="dbx-remigration-operator"
        labelText={copy.operatorLabel}
        helperText={copy.operatorHelper}
        value={operator}
        onChange={(event) => setOperator(event.target.value)}
      />
      <TextInput
        id="dbx-remigration-duration"
        labelText={copy.durationLabel}
        helperText={copy.durationHelper}
        value={duration}
        onChange={(event) => setDuration(event.target.value)}
      />
      <TextInput
        id="dbx-remigration-change-reference"
        labelText={copy.changeReferenceLabel}
        helperText={copy.changeReferenceHelper}
        value={changeReference}
        onChange={(event) => setChangeReference(event.target.value)}
      />
      <TextInput
        id="dbx-remigration-challenge"
        labelText={copy.challengeLabel(sourceDatabase)}
        helperText={copy.challengeHelper}
        value={typed}
        onChange={(event) => setTyped(event.target.value)}
      />
      {complete ? null : <p className="dbx-validation__notice">{copy.incomplete}</p>}
      {failed ? (
        <p className="dbx-validation__notice" role="alert">
          {copy.failed}
        </p>
      ) : null}
    </Modal>
  );
}
