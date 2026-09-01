import { useParams } from 'react-router-dom';
import { messages } from '@/messages';
import { Page } from './Page';

export function MigrationRunPage() {
  const { runId } = useParams();

  return (
    <Page title={messages.run.title} lead={messages.placeholder.notYetBuilt}>
      <p>
        {messages.run.runLabel}
        <span className="dbx-identifier"> {runId}</span>
      </p>
    </Page>
  );
}
