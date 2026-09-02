import { useState } from 'react';
import { Modal, TextArea, TextInput } from '@carbon/react';
import type { ValidationReportRow } from '@/contract';
import { messages } from '@/messages';

/**
 * Recording a 校验处置 (#40).
 *
 * 「An operator's **audited** decision」 is what the domain says it is, and the dialog is
 * shaped by that word: a named 责任人 and a written 理由 are what make the decision
 * auditable, so neither is optional and neither has a default. A dialog that could be
 * confirmed with two empty fields would be recording an anonymous shrug.
 *
 * The body states, before anything is typed, what recording will and will not do — the
 * technical conclusion is quoted in it and is not going to change. That sentence is the
 * whole reason this dialog is safe to offer at all: `CONTEXT.md` lists 「Manual pass,
 * overridden result」 under 校验处置's `_Avoid_`, and an operator who believes this button
 * turns a `FAIL` into a `PASS` has been misled by the interface rather than by the domain.
 */
interface RecordDispositionModalProps {
  readonly row: ValidationReportRow;
  readonly pending: boolean;
  readonly failed: boolean;
  readonly onCancel: () => void;
  readonly onConfirm: (reason: string, accountableOperator: string) => void;
}

export function RecordDispositionModal({
  row,
  pending,
  failed,
  onCancel,
  onConfirm,
}: RecordDispositionModalProps) {
  const copy = messages.validation.disposition.modal;
  const [operator, setOperator] = useState('');
  const [reason, setReason] = useState('');
  const complete = operator.trim() !== '' && reason.trim() !== '';

  return (
    <Modal
      open
      modalHeading={copy.title}
      primaryButtonText={copy.confirm}
      secondaryButtonText={copy.cancel}
      primaryButtonDisabled={!complete || pending}
      onRequestClose={onCancel}
      onSecondarySubmit={onCancel}
      onRequestSubmit={() => {
        // Checked again here rather than trusting the disabled state: a dialog that can be
        // submitted by keyboard must not depend on a button's appearance for its safety.
        if (complete) {
          onConfirm(reason.trim(), operator.trim());
        }
      }}
    >
      <p>{copy.body(row.sourceTable, messages.conclusion.labels[row.conclusion])}</p>
      <TextInput
        id="dbx-disposition-operator"
        labelText={copy.operatorLabel}
        helperText={copy.operatorHelper}
        value={operator}
        onChange={(event) => setOperator(event.target.value)}
      />
      <TextArea
        id="dbx-disposition-reason"
        labelText={copy.reasonLabel}
        helperText={copy.reasonHelper}
        rows={3}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
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
