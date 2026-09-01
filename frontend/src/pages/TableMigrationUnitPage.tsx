import { useParams } from 'react-router-dom';
import { messages } from '@/messages';
import { Page } from './Page';

/**
 * Per-table evidence. It is presented as a drawer over run monitoring in a later batch,
 * but it keeps its own URL so a DBA can paste one table's evidence into a ticket and a
 * refresh restores the same screen (#30).
 */
export function TableMigrationUnitPage() {
  const { runId, unitId } = useParams();

  return (
    <Page title={messages.run.evidenceTitle} lead={messages.placeholder.notYetBuilt}>
      <p>
        {messages.run.runLabel}
        <span className="dbx-identifier"> {runId}</span>
      </p>
      <p>
        {messages.run.unitLabel}
        <span className="dbx-identifier"> {unitId}</span>
      </p>
    </Page>
  );
}
