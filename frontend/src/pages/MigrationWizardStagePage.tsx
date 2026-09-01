import { useCallback, type ComponentType } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { useDatabaseConnections } from '@/api/databaseConnections';
import {
  useDiscardMigrationDraft,
  useMigrationDraft,
  useUpdateMigrationDraft,
} from '@/api/migrationDrafts';
import { ApiError } from '@/api/http';
import { EmptyState, ErrorState, LoadingState } from '@/components/ViewState';
import type { MigrationDraftPatch } from '@/contract';
import { messages } from '@/messages';
import { isWizardStage, paths, type WizardStage } from '@/routes/paths';
import {
  StageConnections,
  StageScope,
  StageTables,
  WizardShell,
  resolveStageEntry,
  type WizardGateContext,
} from '@/wizard';
import { useDraftTableConfigurations } from '@/api/draftTables';
import { Page } from './Page';
import { NotFoundPage } from './NotFoundPage';

/**
 * One route per wizard stage (`/tasks/new/:draftId/:stage`).
 *
 * The stages render full page rather than a wide tearsheet — a deliberate, recorded
 * deviation from Carbon (ADR-0014). Do not "correct" it.
 *
 * This module is where a stage's URL meets its gate. Every stage is deep-linkable, so the
 * gate has to hold against a typed address and not only against a clicked button: a stage
 * this draft has not earned is redirected to the stage that is actually stopping it, by
 * exactly the evaluation the footer uses. `replace`, so the back button does not bounce
 * between the two.
 */

/** What a stage component is handed. Stable, so #35–#37 add stages without changing it. */
export interface WizardStageProps {
  readonly context: WizardGateContext;
  readonly onPatch: (patch: MigrationDraftPatch) => void;
}

/**
 * Which component renders which stage.
 *
 * A stage with no entry renders the "later batch" placeholder, which is honest while its
 * gate (`src/wizard/stageGates.ts`) also says the stage is undelivered. #37 adds
 * `confirm`, and #38/#40 take over 运行监控 and 校验报告 from the migration run.
 */
const stageContent: Partial<Record<WizardStage, ComponentType<WizardStageProps>>> = {
  connections: StageConnections,
  scope: StageScope,
  tables: StageTables,
};

export function MigrationWizardStagePage() {
  const { draftId, stage } = useParams();
  const navigate = useNavigate();
  const draftQuery = useMigrationDraft(draftId ?? '');
  const connectionsQuery = useDatabaseConnections();
  // Stage three's gate is a fact about the tables in the 迁移范围, so the summaries are
  // part of the gate context rather than something the stage alone knows (#35). The page
  // does not wait for them: `null` is a state the gate answers for, which keeps the shell
  // rendering while the read is in flight.
  const tableConfigurationsQuery = useDraftTableConfigurations(draftId ?? '');
  const update = useUpdateMigrationDraft();
  const discard = useDiscardMigrationDraft();

  const onPatch = useCallback(
    (patch: MigrationDraftPatch) => {
      if (draftId !== undefined) {
        update.mutate({ draftId, patch });
      }
    },
    [draftId, update],
  );

  if (!isWizardStage(stage) || draftId === undefined) {
    return <NotFoundPage />;
  }

  if (draftQuery.isPending || connectionsQuery.isPending) {
    return (
      <Page title={messages.wizard.title}>
        <LoadingState description={messages.wizard.loading} />
      </Page>
    );
  }

  // A draft that is not there is not a failure: 「丢弃后不留痕迹」 means a discarded draft
  // leaves nothing behind, so a stale link finds nothing and the page says exactly that
  // rather than offering a retry that can never succeed.
  if (draftQuery.error instanceof ApiError && draftQuery.error.status === 404) {
    return (
      <Page title={messages.wizard.title}>
        <EmptyState title={messages.wizard.notFound.title} body={messages.wizard.notFound.body} />
      </Page>
    );
  }

  if (draftQuery.isError || connectionsQuery.isError || draftQuery.data === undefined) {
    return (
      <Page title={messages.wizard.title}>
        <ErrorState
          title={messages.wizard.error.title}
          body={messages.wizard.error.body}
          onRetry={() => void draftQuery.refetch()}
        />
      </Page>
    );
  }

  const context: WizardGateContext = {
    draft: draftQuery.data,
    connections: connectionsQuery.data ?? [],
    tableConfigurations: tableConfigurationsQuery.data ?? null,
  };

  const permitted = resolveStageEntry(stage, context);
  if (permitted !== stage) {
    return <Navigate to={paths.wizardStage(draftId, permitted)} replace />;
  }

  const Stage = stageContent[stage];

  return (
    <WizardShell
      stage={stage}
      context={context}
      onDiscard={() => discard.mutate(draftId, { onSuccess: () => navigate(paths.migrationTasks) })}
    >
      {Stage === undefined ? (
        <p>{messages.wizard.notYetBuilt}</p>
      ) : (
        <Stage context={context} onPatch={onPatch} />
      )}
    </WizardShell>
  );
}
