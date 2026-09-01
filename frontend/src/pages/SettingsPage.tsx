import { messages } from '@/messages';
import { Page } from './Page';

/** Navigation placeholder only; system settings have no content in this phase (#30). */
export function SettingsPage() {
  return <Page title={messages.settings.title} lead={messages.settings.lead} />;
}
