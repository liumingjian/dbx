import { useState } from 'react';
import { Button } from '@carbon/react';
import {
  useAddCredentialVersion,
  useCheckDatabaseConnection,
  useDatabaseConnections,
  useRegisterDatabaseConnection,
} from '@/api/databaseConnections';
import { EmptyState, ErrorState, LoadingState } from '@/components/ViewState';
import type { DatabaseConnection } from '@/contract';
import { AddCredentialVersionModal } from '@/features/connections/AddCredentialVersionModal';
import { DatabaseConnectionCard } from '@/features/connections/DatabaseConnectionCard';
import { RegisterConnectionModal } from '@/features/connections/RegisterConnectionModal';
import { messages } from '@/messages';
import { Page } from './Page';

/**
 * 数据源 — the navigation area on which 数据库连接 and their 凭据版本 are registered,
 * checked and maintained (`CONTEXT.md`). The area is a 数据源; each row is a 数据库连接.
 *
 * The three states this page can be in — loading, empty and error — are all reachable from
 * the URL scenario parameter (`?scenario=loading|empty|error`), which is what makes them
 * reviewable rather than theoretical.
 */
export function DatabaseConnectionsPage() {
  const connections = useDatabaseConnections();
  const register = useRegisterDatabaseConnection();
  const addCredentialVersion = useAddCredentialVersion();
  const check = useCheckDatabaseConnection();

  const [registerOpen, setRegisterOpen] = useState(false);
  const [credentialTarget, setCredentialTarget] = useState<DatabaseConnection | null>(null);

  const busy = register.isPending || addCredentialVersion.isPending || check.isPending;

  return (
    <Page title={messages.connections.title} lead={messages.connections.lead}>
      {connections.isPending ? <LoadingState description={messages.connections.loading} /> : null}

      {connections.isError ? (
        <ErrorState
          title={messages.connections.error.title}
          body={messages.connections.error.body}
          onRetry={() => void connections.refetch()}
        />
      ) : null}

      {connections.isSuccess && connections.data.length === 0 ? (
        <EmptyState
          title={messages.connections.empty.title}
          body={messages.connections.empty.body}
          action={
            <Button onClick={() => setRegisterOpen(true)}>
              {messages.connections.registerAction}
            </Button>
          }
        />
      ) : null}

      {connections.isSuccess && connections.data.length > 0 ? (
        <>
          <div className="dbx-connections__toolbar">
            <Button onClick={() => setRegisterOpen(true)}>
              {messages.connections.registerAction}
            </Button>
          </div>
          <ul className="dbx-connections" aria-label={messages.connections.listLabel}>
            {connections.data.map((connection) => (
              <li key={connection.id}>
                <DatabaseConnectionCard
                  connection={connection}
                  busy={busy}
                  onRecheck={() => check.mutate(connection.id)}
                  onAddCredentialVersion={() => setCredentialTarget(connection)}
                />
              </li>
            ))}
          </ul>
        </>
      ) : null}

      <RegisterConnectionModal
        open={registerOpen}
        onClose={() => setRegisterOpen(false)}
        onSubmit={(request) => {
          register.mutate(request);
          setRegisterOpen(false);
        }}
      />
      <AddCredentialVersionModal
        connection={credentialTarget}
        onClose={() => setCredentialTarget(null)}
        onSubmit={(id, request) => {
          addCredentialVersion.mutate({ id, request });
          setCredentialTarget(null);
        }}
      />
    </Page>
  );
}
