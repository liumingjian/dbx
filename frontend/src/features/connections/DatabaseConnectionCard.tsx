import { Button, Tag, Tile } from '@carbon/react';
import type { ConnectionCheckOutcome, DatabaseConnection } from '@/contract';
import { formatCredentialVersion, formatDialect, formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';

/**
 * One 数据库连接.
 *
 * The row carries the three facts #30 asks for — which credential version is in force, how
 * the last connection check concluded, and when — because a connection whose freshness is
 * invisible cannot be trusted before a migration starts.
 *
 * `Tag` is used for the dimension it is meant for (which side of the database pair this
 * endpoint serves), never for a conclusion: ADR-0014 reserves conclusions for
 * `IconIndicator`, which arrives with the shared conclusion module in #33.
 */

const checkOutcomeLiterals: Record<ConnectionCheckOutcome, string> = {
  SUCCEEDED: messages.connections.checkOutcomes.succeeded,
  FAILED: messages.connections.checkOutcomes.failed,
  NOT_RUN: messages.connections.checkOutcomes.notRun,
};

export function DatabaseConnectionCard({
  connection,
  onRecheck,
  onAddCredentialVersion,
  busy,
}: {
  connection: DatabaseConnection;
  onRecheck: () => void;
  onAddCredentialVersion: () => void;
  busy: boolean;
}) {
  const { latestCheck } = connection;
  const dialectLabel =
    connection.role === 'SOURCE'
      ? messages.connections.sourceDialectLabel
      : messages.connections.targetDialectLabel;

  return (
    <Tile className="dbx-connection">
      <h3 className="dbx-connection__name">{connection.name}</h3>

      <dl className="dbx-connection__facts">
        <div className="dbx-connection__fact">
          <dt>{messages.connections.endpointLabel}</dt>
          <dd>
            <Identifier>{`${connection.host}:${connection.port}/${connection.database}`}</Identifier>
          </dd>
        </div>
        <div className="dbx-connection__fact">
          <dt>{dialectLabel}</dt>
          <dd>{formatDialect(connection.dialect)}</dd>
        </div>
        <div className="dbx-connection__fact">
          <dt>{messages.connections.credentialVersionLabel}</dt>
          <dd>
            <Identifier>
              {formatCredentialVersion(connection.currentCredentialVersion.version)}
            </Identifier>
          </dd>
        </div>
        <div className="dbx-connection__fact">
          <dt>{messages.connections.latestCheckLabel}</dt>
          <dd>
            <Identifier>{checkOutcomeLiterals[latestCheck.outcome]}</Identifier>
            {latestCheck.checkedAt === null ? (
              <span className="dbx-connection__check-time">
                {messages.connections.neverChecked}
              </span>
            ) : (
              <span className="dbx-connection__check-time">
                <Identifier>{formatTimestamp(latestCheck.checkedAt)}</Identifier>
              </span>
            )}
          </dd>
        </div>
      </dl>

      <div className="dbx-connection__meta">
        <Tag type="cool-gray">
          {connection.role === 'SOURCE'
            ? messages.connections.roles.source
            : messages.connections.roles.target}
        </Tag>
      </div>

      <div className="dbx-connection__actions">
        <Button kind="tertiary" size="sm" disabled={busy} onClick={onRecheck}>
          {messages.connections.recheckAction}
        </Button>
        <Button kind="ghost" size="sm" disabled={busy} onClick={onAddCredentialVersion}>
          {messages.connections.addCredentialVersionAction}
        </Button>
      </div>
    </Tile>
  );
}
