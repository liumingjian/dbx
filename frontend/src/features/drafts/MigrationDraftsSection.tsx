import { useMemo, useState } from 'react';
import { Button, Modal } from '@carbon/react';
import { useNavigate } from 'react-router-dom';
import { useDatabaseConnections } from '@/api/databaseConnections';
import {
  useCreateMigrationDraft,
  useDiscardMigrationDraft,
  useMigrationDrafts,
} from '@/api/migrationDrafts';
import { DbxTable, type DbxTableColumn } from '@/components/DbxTable';
import type { MigrationDraft } from '@/contract';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { paths } from '@/routes/paths';
import { furthestReachableStage } from '@/wizard';

/**
 * 迁移草稿, listed in their own right beside the migration tasks.
 *
 * They are a separate list rather than rows in the task table, because they are a separate
 * concept: a 迁移任务 is a *user-approved* migration (`CONTEXT.md`), so an unapproved one
 * cannot exist. Nothing here shares a column with a task — a draft has no 批准时间, no
 * 迁移运行 and no status, and giving it borrowed ones is how 「已批准」 would quietly become
 * a property that can be false.
 *
 * Discarding is a real deletion, confirmed once: 「丢弃后不留痕迹」 is the requirement, and a
 * draft produces no migration run and is never audit evidence, so there is nothing a
 * tombstone would be evidence of.
 */
export function MigrationDraftsSection() {
  const navigate = useNavigate();
  const draftsQuery = useMigrationDrafts();
  const connectionsQuery = useDatabaseConnections();
  const create = useCreateMigrationDraft();
  const discard = useDiscardMigrationDraft();
  const [discarding, setDiscarding] = useState<MigrationDraft | null>(null);

  const drafts = useMemo(() => draftsQuery.data ?? [], [draftsQuery.data]);
  const connections = useMemo(() => connectionsQuery.data ?? [], [connectionsQuery.data]);

  /**
   * Resume where the draft actually stands, by the same gate evaluation the wizard uses.
   *
   * The list does not read the draft's 逐表配置 — it would be one request per row — so the
   * stage-three evidence is `null` here. That resolves to 逐表配置与预检 at the furthest,
   * which is the honest answer: this page cannot say the stage was satisfied, and the
   * wizard re-evaluates it properly the moment the draft is opened.
   */
  const resumePath = (draft: MigrationDraft) =>
    paths.wizardStage(
      draft.id,
      furthestReachableStage({ draft, connections, tableConfigurations: null }),
    );

  const startDraft = () =>
    create.mutate(
      {},
      { onSuccess: (draft) => navigate(paths.wizardStage(draft.id, 'connections')) },
    );

  const columns = useMemo<readonly DbxTableColumn<MigrationDraft>[]>(
    () => [
      {
        id: 'name',
        header: messages.drafts.columns.name,
        identifying: true,
        width: 260,
        textValue: (draft) => (draft.name === '' ? draft.id : draft.name),
        renderCell: (draft) =>
          draft.name === '' ? (
            <span>
              {messages.drafts.unnamed} <Identifier>{draft.id}</Identifier>
            </span>
          ) : (
            draft.name
          ),
      },
      {
        id: 'sourceDatabase',
        header: messages.drafts.columns.sourceDatabase,
        width: 200,
        textValue: (draft) => draft.sourceDatabase ?? messages.drafts.notChosen,
        renderCell: (draft) =>
          draft.sourceDatabase === null ? (
            messages.drafts.notChosen
          ) : (
            <Identifier>{draft.sourceDatabase}</Identifier>
          ),
      },
      {
        id: 'targetSchema',
        header: messages.drafts.columns.targetSchema,
        width: 220,
        textValue: (draft) => draft.targetSchema ?? messages.drafts.notChosen,
        renderCell: (draft) =>
          draft.targetSchema === null ? (
            messages.drafts.notChosen
          ) : (
            <Identifier>{draft.targetSchema}</Identifier>
          ),
      },
      {
        id: 'selectedTableCount',
        header: messages.drafts.columns.selectedTableCount,
        width: 120,
        textValue: (draft) => String(draft.selectedTables.length),
        renderCell: (draft) => <Identifier>{draft.selectedTables.length}</Identifier>,
      },
      {
        id: 'updatedAt',
        header: messages.drafts.columns.updatedAt,
        width: 200,
        textValue: (draft) => formatTimestamp(draft.updatedAt),
        renderCell: (draft) => <Identifier>{formatTimestamp(draft.updatedAt)}</Identifier>,
      },
      {
        id: 'actions',
        header: messages.drafts.columns.actions,
        width: 220,
        textValue: () => '',
        renderCell: (draft) => (
          <>
            <Button kind="ghost" size="sm" onClick={() => navigate(resumePath(draft))}>
              {messages.drafts.continueAction}
            </Button>
            <Button kind="danger--ghost" size="sm" onClick={() => setDiscarding(draft)}>
              {messages.drafts.discardAction}
            </Button>
          </>
        ),
      },
    ],
    // `connections` moves the resume target, so the cells have to be rebuilt with it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [navigate, connections],
  );

  return (
    // No `aria-label` here: the heading below names the section, and the table inside it
    // carries the same words as its own accessible name.
    <section className="dbx-drafts">
      <h2 className="dbx-drafts__title">{messages.drafts.title}</h2>
      <p className="dbx-page__lead">{messages.drafts.lead}</p>

      <DbxTable
        label={messages.drafts.listLabel}
        columns={columns}
        rows={drafts}
        rowId={(draft) => draft.id}
        loading={draftsQuery.isPending}
        error={
          draftsQuery.isError
            ? {
                title: messages.drafts.error.title,
                body: messages.drafts.error.body,
                onRetry: () => void draftsQuery.refetch(),
              }
            : null
        }
        // The create action lives in the toolbar, where it is reachable whether or not
        // there are drafts; the empty copy names it rather than repeating the button.
        empty={{ title: messages.drafts.empty.title, body: messages.drafts.empty.body }}
        densityPreferenceKey="migration-drafts"
        toolbar={
          <Button kind="primary" size="sm" onClick={startDraft}>
            {messages.drafts.createAction}
          </Button>
        }
      />

      {discarding !== null ? (
        <Modal
          open
          danger
          modalHeading={messages.drafts.discard.title}
          primaryButtonText={messages.drafts.discard.confirm}
          secondaryButtonText={messages.drafts.discard.cancel}
          onRequestClose={() => setDiscarding(null)}
          onSecondarySubmit={() => setDiscarding(null)}
          onRequestSubmit={() => {
            discard.mutate(discarding.id);
            setDiscarding(null);
          }}
        >
          <p>{messages.drafts.discard.body}</p>
        </Modal>
      ) : null}
    </section>
  );
}
