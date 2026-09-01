import { useState } from 'react';
import { InlineNotification, Select, SelectItem, TextInput } from '@carbon/react';
import { Link as RouterLink } from 'react-router-dom';
import { ConclusionIndicator, connectionCheckConclusion } from '@/conclusions';
import type { ConnectionRole, DatabaseConnection, MigrationDraftPatch } from '@/contract';
import { formatDialect, formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { paths } from '@/routes/paths';
import type { WizardGateContext } from './stageGates';

/**
 * Stage one — 连接与数据库.
 *
 * The wizard **chooses** a 数据库连接; it never creates one and never accepts a credential.
 * `CONTEXT.md` puts connection creation and credential entry in 数据源 alone, so the only
 * outbound path here is a link to that area.
 *
 * Two facts are on screen for every choice, because both change after the fact: the
 * connection's 最近校验, and its 源方言 / 目标方言. A pair of endpoint dialects does not by
 * itself imply a supported 数据库对 (`CONTEXT.md`), so the operator is shown the dialects
 * rather than being told the pair is fine.
 */
interface StageConnectionsProps {
  readonly context: WizardGateContext;
  readonly onPatch: (patch: MigrationDraftPatch) => void;
}

function connectionsForRole(
  connections: readonly DatabaseConnection[],
  role: ConnectionRole,
): readonly DatabaseConnection[] {
  return connections.filter((connection) => connection.role === role && !connection.archived);
}

/**
 * The 最近校验 of one connection, and a warning when it is anything but `SUCCEEDED`.
 *
 * Showing it the moment the connection is picked is the point: #34 requires an invalid
 * connection to be reported immediately rather than at 逐表配置与预检, which is where an
 * operator would otherwise meet it — after configuring hundreds of tables.
 */
function ConnectionCheckSummary({ connection }: { connection: DatabaseConnection }) {
  const { latestCheck } = connection;
  const usable = latestCheck.outcome === 'SUCCEEDED';
  return (
    <>
      <p className="dbx-wizard__fact">
        {messages.wizard.connections.endpointLabel}：
        <Identifier>{`${connection.host}:${connection.port}`}</Identifier>
      </p>
      <p className="dbx-wizard__fact">
        {messages.wizard.connections.latestCheckLabel}：
        <ConclusionIndicator
          conclusion={connectionCheckConclusion(latestCheck.outcome)}
          label={latestCheck.outcome}
        />
        {latestCheck.checkedAt === null ? (
          <span> {messages.wizard.connections.neverChecked}</span>
        ) : (
          <Identifier> {formatTimestamp(latestCheck.checkedAt)}</Identifier>
        )}
      </p>
      {usable ? null : (
        <InlineNotification
          kind="warning"
          lowContrast
          hideCloseButton
          role="alert"
          title={messages.wizard.connections.unusableTitle}
          subtitle={messages.wizard.connections.unusableDetail(latestCheck.outcome)}
        />
      )}
    </>
  );
}

export function StageConnections({ context, onPatch }: StageConnectionsProps) {
  const { draft, connections } = context;
  // The schema name is typed rather than chosen, so it is held locally while it is being
  // typed and written through on every change. Binding the field straight to the draft
  // would make each keystroke wait for a round trip to come back before the next one could
  // be seen.
  const [targetSchema, setTargetSchema] = useState(draft.targetSchema ?? '');
  const sources = connectionsForRole(connections, 'SOURCE');
  const targets = connectionsForRole(connections, 'TARGET');
  const source = sources.find((entry) => entry.id === draft.sourceConnectionId);
  const target = targets.find((entry) => entry.id === draft.targetConnectionId);

  return (
    <section className="dbx-wizard__panes" aria-label={messages.wizard.stages.connections}>
      <p className="dbx-wizard__lead">
        {messages.wizard.connections.lead}{' '}
        <RouterLink to={paths.databaseConnections}>
          {messages.wizard.connections.manageConnectionsLink}
        </RouterLink>
      </p>

      <div className="dbx-wizard__pane-grid">
        <section
          className="dbx-wizard__pane"
          aria-label={messages.wizard.connections.sourceHeading}
        >
          <h3 className="dbx-wizard__pane-title">{messages.wizard.connections.sourceHeading}</h3>
          <Select
            id="wizard-source-connection"
            labelText={messages.wizard.connections.connectionLabel}
            value={draft.sourceConnectionId ?? ''}
            onChange={(event) => {
              const id = event.target.value === '' ? null : event.target.value;
              // Changing the connection invalidates the database chosen inside the old one.
              onPatch({ sourceConnectionId: id, sourceDatabase: null });
            }}
          >
            <SelectItem value="" text={messages.wizard.connections.chooseConnection} />
            {sources.map((connection) => (
              <SelectItem key={connection.id} value={connection.id} text={connection.name} />
            ))}
          </Select>

          {source === undefined ? null : (
            <>
              <p className="dbx-wizard__fact">
                {messages.connections.sourceDialectLabel}：
                <Identifier>{formatDialect(source.dialect)}</Identifier>
              </p>
              <ConnectionCheckSummary connection={source} />
              <Select
                id="wizard-source-database"
                labelText={messages.wizard.connections.sourceDatabaseLabel}
                value={draft.sourceDatabase ?? ''}
                onChange={(event) =>
                  onPatch({
                    sourceDatabase: event.target.value === '' ? null : event.target.value,
                    // A different source database is a different set of tables, so the
                    // 迁移范围 chosen against the old one is no longer a statement about
                    // anything. Silently keeping it would carry names forward that may not
                    // exist here.
                    scopeKind: 'SELECTED_TABLES',
                    selectedTables: [],
                    excludedTables: [],
                  })
                }
              >
                <SelectItem value="" text={messages.wizard.connections.chooseDatabase} />
                {source.databases.map((database) => (
                  <SelectItem key={database} value={database} text={database} />
                ))}
              </Select>
            </>
          )}
        </section>

        <section
          className="dbx-wizard__pane"
          aria-label={messages.wizard.connections.targetHeading}
        >
          <h3 className="dbx-wizard__pane-title">{messages.wizard.connections.targetHeading}</h3>
          <Select
            id="wizard-target-connection"
            labelText={messages.wizard.connections.connectionLabel}
            value={draft.targetConnectionId ?? ''}
            onChange={(event) =>
              onPatch({
                targetConnectionId: event.target.value === '' ? null : event.target.value,
              })
            }
          >
            <SelectItem value="" text={messages.wizard.connections.chooseConnection} />
            {targets.map((connection) => (
              <SelectItem key={connection.id} value={connection.id} text={connection.name} />
            ))}
          </Select>

          {target === undefined ? null : (
            <>
              <p className="dbx-wizard__fact">
                {messages.connections.targetDialectLabel}：
                <Identifier>{formatDialect(target.dialect)}</Identifier>
              </p>
              <ConnectionCheckSummary connection={target} />
              <TextInput
                id="wizard-target-schema"
                labelText={messages.wizard.connections.targetSchemaLabel}
                helperText={messages.wizard.connections.targetSchemaHelper}
                value={targetSchema}
                onChange={(event) => {
                  setTargetSchema(event.target.value);
                  onPatch({ targetSchema: event.target.value === '' ? null : event.target.value });
                }}
              />
            </>
          )}
        </section>
      </div>

      {source !== undefined &&
      target !== undefined &&
      draft.sourceDatabase !== null &&
      draft.targetSchema !== null ? (
        <p className="dbx-wizard__resolved">
          {messages.wizard.connections.resolvedPairLabel}：
          <Identifier>{`${draft.sourceDatabase} → ${target.name} / ${draft.targetSchema}`}</Identifier>
        </p>
      ) : null}
    </section>
  );
}
