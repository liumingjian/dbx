import { Modal } from '@carbon/react';
import { useRequestRunCancellation, useRunCancellationConsequences } from '@/api/runProgress';
import type { MigrationRunId } from '@/contract';
import { messages } from '@/messages';

/**
 * 取消 — stated before it happens (#38).
 *
 * `CONTEXT.md` defines a 取消 as 「a user-requested terminal stop of a migration run that
 * preserves … target data and diagnostic evidence」 and puts 「discard」, 「delete」 and
 * 「rollback」 under its `_Avoid_`. Both halves of that are consequences an operator has to
 * know *first*, so the dialog says what will stop, what will not be touched, and that
 * there is no way back into this run afterwards.
 *
 * The counts are read from the platform rather than added up in the browser: how many
 * tables are still in flight is a fact about the run at this instant, and it is exactly
 * what the operator is deciding about.
 */
interface CancelRunModalProps {
  readonly runId: MigrationRunId;
  readonly open: boolean;
  readonly onClose: () => void;
  /** Called once the platform has accepted the request, so the view can observe again. */
  readonly onRequested: () => void;
}

export function CancelRunModal({ runId, open, onClose, onRequested }: CancelRunModalProps) {
  const copy = messages.run.cancel;
  const consequences = useRunCancellationConsequences(runId, open);
  const request = useRequestRunCancellation(runId);

  return (
    <Modal
      open={open}
      danger
      modalHeading={copy.heading}
      primaryButtonText={copy.confirmAction}
      secondaryButtonText={copy.dismissAction}
      primaryButtonDisabled={consequences.data === undefined || request.isPending}
      onRequestClose={onClose}
      onSecondarySubmit={onClose}
      onRequestSubmit={() =>
        request.mutate(undefined, {
          onSuccess: () => {
            onRequested();
            onClose();
          },
        })
      }
    >
      <p>{copy.lead}</p>
      {consequences.data === undefined ? null : (
        <ul className="dbx-run__list">
          <li>{copy.inFlight(consequences.data.inFlightUnitCount)}</li>
          <li>{copy.terminal(consequences.data.terminalUnitCount)}</li>
          <li>{copy.preserved}</li>
          <li>{copy.terminalStop}</li>
          {consequences.data.alreadyRequested ? <li>{copy.alreadyRequested}</li> : null}
        </ul>
      )}
    </Modal>
  );
}
