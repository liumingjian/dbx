import { messages } from '@/messages';
import { Page } from './Page';

export function MigrationTasksPage() {
  return (
    <Page title={messages.tasks.title} lead={messages.tasks.lead}>
      <p>{messages.placeholder.notYetBuilt}</p>
    </Page>
  );
}
