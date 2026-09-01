import type { ReactNode } from 'react';
import { Button, InlineLoading } from '@carbon/react';
import { messages } from '@/messages';

/**
 * Loading, empty and error states.
 *
 * #30 requires every key view to have all three and to be reachable from a URL scenario
 * parameter, because a blank page is not a state — it is the absence of one. The empty
 * state always names a next action rather than merely reporting that there is nothing.
 */

export function LoadingState({ description }: { description: string }) {
  return (
    <div className="dbx-view-state">
      <InlineLoading description={description} status="active" />
    </div>
  );
}

export function EmptyState({
  title,
  body,
  action,
}: {
  title: string;
  body: string;
  action?: ReactNode;
}) {
  return (
    <div className="dbx-view-state">
      <h3 className="dbx-view-state__title">{title}</h3>
      <p className="dbx-view-state__body">{body}</p>
      {action}
    </div>
  );
}

export function ErrorState({
  title,
  body,
  onRetry,
}: {
  title: string;
  body: string;
  onRetry: () => void;
}) {
  return (
    <div className="dbx-view-state" role="alert">
      <h3 className="dbx-view-state__title">{title}</h3>
      <p className="dbx-view-state__body">{body}</p>
      <Button kind="tertiary" onClick={onRetry}>
        {messages.common.retry}
      </Button>
    </div>
  );
}
