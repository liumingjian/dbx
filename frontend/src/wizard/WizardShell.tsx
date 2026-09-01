import { useState, type ReactNode } from 'react';
import { Button, InlineNotification, Modal, ProgressIndicator, ProgressStep } from '@carbon/react';
import { useNavigate } from 'react-router-dom';
import { messages } from '@/messages';
import { paths, wizardStages, type WizardStage } from '@/routes/paths';
import { Identifier } from '@/pages/Identifier';
import { Page } from '@/pages/Page';
import {
  evaluateStageGate,
  isStageComplete,
  isStageReachable,
  nextStage,
  previousStage,
  type WizardGateContext,
} from './stageGates';

/**
 * The migration wizard's frame.
 *
 * **Full page, not a wide tearsheet.** Carbon recommends a wide tearsheet for multi-step
 * creation and attaches a notice discouraging the full-page form; DBX deviates knowingly
 * and the deviation is recorded in ADR-0014. The reason is the content: stage two is a
 * 1200-row production-schema selector and stage three is a three-pane single-table
 * workspace, and a tearsheet holds neither. This is one of the three things #30 predicts a
 * later reader will "helpfully correct" — do not move it into a tearsheet.
 *
 * **One progress indicator** (ADR-0007): the stages are named here and nowhere else. There
 * is deliberately no second vertical stage rail duplicating them.
 *
 * The shell owns the frame, the stage rail and the footer; each stage owns its own content
 * and its own gate (`./stageGates.ts`). A later ticket adds a stage by supplying content
 * for it in `MigrationWizardStagePage` and a rule for it in the gate table — nothing in
 * this file changes.
 */
interface WizardShellProps {
  readonly stage: WizardStage;
  readonly context: WizardGateContext;
  readonly onDiscard: () => void;
  readonly children: ReactNode;
}

export function WizardShell({ stage, context, onDiscard, children }: WizardShellProps) {
  const navigate = useNavigate();
  const [confirmingDiscard, setConfirmingDiscard] = useState(false);

  const { draft } = context;
  const gate = evaluateStageGate(stage, context);
  const previous = previousStage(stage);
  const next = nextStage(stage);

  const goTo = (target: WizardStage) => {
    navigate(paths.wizardStage(draft.id, target));
  };

  const advance = () => {
    if (gate.blocked || next === null) {
      return;
    }
    goTo(next);
  };

  return (
    <Page
      title={messages.wizard.title}
      lead={`${messages.wizard.stageLabel}：${messages.wizard.stages[stage]}`}
      width="full"
      actions={
        <>
          <Button kind="ghost" onClick={() => navigate(paths.migrationTasks())}>
            {messages.wizard.exitAction}
          </Button>
          <Button kind="danger--ghost" onClick={() => setConfirmingDiscard(true)}>
            {messages.wizard.discardAction}
          </Button>
        </>
      }
    >
      <p className="dbx-wizard__draft">
        {messages.wizard.draftLabel} <Identifier>{draft.id}</Identifier>
      </p>

      <ProgressIndicator
        className="dbx-wizard__progress"
        aria-label={messages.wizard.progressLabel}
        currentIndex={wizardStages.indexOf(stage)}
        spaceEqually
        onChange={(index: number) => {
          const target = wizardStages[index];
          // Backwards only, and only into a stage this draft has actually satisfied.
          // Progress navigation is how a gate would otherwise be walked around.
          if (target !== undefined && isStageReachable(target, context)) {
            goTo(target);
          }
        }}
      >
        {wizardStages.map((entry) => (
          <ProgressStep
            key={entry}
            label={messages.wizard.stages[entry]}
            complete={isStageComplete(entry, context)}
            disabled={!isStageReachable(entry, context)}
          />
        ))}
      </ProgressIndicator>

      <div className="dbx-wizard__stage">{children}</div>

      {gate.blocked ? (
        <InlineNotification
          kind="warning"
          lowContrast
          // A blocked state cannot be dismissed (#30 story 103): closing it would leave the
          // operator looking at a button that does nothing for no stated reason.
          hideCloseButton
          role="alert"
          title={messages.wizard.blockedTitle}
          subtitle={gate.reason}
        />
      ) : null}

      <div className="dbx-wizard__footer">
        <Button
          kind="secondary"
          disabled={previous === null}
          onClick={() => previous !== null && goTo(previous)}
        >
          {messages.wizard.backAction}
        </Button>
        <Button
          kind="primary"
          // Deliberately not disabled. A disabled button says something is wrong without
          // saying what, and the notification above already carries the reason; pressing it
          // while blocked goes nowhere, which is the behaviour Gate 1's case asserts.
          onClick={advance}
        >
          {messages.wizard.nextAction}
        </Button>
      </div>

      {confirmingDiscard ? (
        <Modal
          open
          danger
          modalHeading={messages.drafts.discard.title}
          primaryButtonText={messages.drafts.discard.confirm}
          secondaryButtonText={messages.drafts.discard.cancel}
          onRequestClose={() => setConfirmingDiscard(false)}
          onSecondarySubmit={() => setConfirmingDiscard(false)}
          onRequestSubmit={() => {
            setConfirmingDiscard(false);
            onDiscard();
          }}
        >
          <p>{messages.drafts.discard.body}</p>
        </Modal>
      ) : null}
    </Page>
  );
}
