import { messages } from '@/messages';
import { Page } from './Page';

export function NotFoundPage() {
  return <Page title={messages.product.name} lead={messages.placeholder.notYetBuilt} />;
}
