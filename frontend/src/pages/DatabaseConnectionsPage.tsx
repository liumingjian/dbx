import { messages } from '@/messages';
import { Page } from './Page';

export function DatabaseConnectionsPage() {
  return (
    <Page title={messages.connections.title} lead={messages.connections.lead}>
      <p>{messages.placeholder.notYetBuilt}</p>
    </Page>
  );
}
