import { useState } from 'react';
import { Modal, TextInput } from '@carbon/react';
import { messages } from '@/messages';

/**
 * 「启动动作需要明确意图，不会被误触发」, stated as a thing only intent can satisfy.
 *
 * Confirming turns a 迁移草稿 into a 迁移任务 and generates an immutable 迁移运行 against a
 * production database. A modal alone does not make that deliberate — a second click is
 * still a click — so the operator types the source database's own identifier. It is the
 * one keystroke sequence that cannot be produced by a stray press, and it names the thing
 * being acted on rather than asking for a magic word.
 *
 * The dialog also states what is about to become unalterable, because that is the fact the
 * operator is actually consenting to: the scope of this execution is recorded now and
 * never edited afterwards.
 */
interface StartMigrationModalProps {
  readonly sourceDatabase: string;
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
}

export function StartMigrationModal({
  sourceDatabase,
  onCancel,
  onConfirm,
}: StartMigrationModalProps) {
  const copy = messages.wizard.confirm.start;
  const [typed, setTyped] = useState('');
  const satisfied = typed.trim() === sourceDatabase;

  return (
    <Modal
      open
      danger
      modalHeading={copy.title}
      primaryButtonText={copy.confirm}
      secondaryButtonText={copy.cancel}
      primaryButtonDisabled={!satisfied}
      onRequestClose={onCancel}
      onSecondarySubmit={onCancel}
      onRequestSubmit={() => {
        // Checked again here rather than trusting the disabled state: a dialog that can be
        // submitted by keyboard must not depend on a button's appearance for its safety.
        if (satisfied) {
          onConfirm();
        }
      }}
    >
      <p>{copy.body}</p>
      <TextInput
        id="dbx-start-migration-challenge"
        labelText={copy.challengeLabel(sourceDatabase)}
        helperText={copy.challengeHelper}
        value={typed}
        onChange={(event) => setTyped(event.target.value)}
      />
    </Modal>
  );
}
