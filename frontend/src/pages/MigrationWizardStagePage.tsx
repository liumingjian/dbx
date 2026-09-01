import { useParams } from 'react-router-dom';
import { messages } from '@/messages';
import { isWizardStage } from '@/routes/paths';
import { Identifier } from './Identifier';
import { Page } from './Page';
import { NotFoundPage } from './NotFoundPage';

/**
 * One route per wizard stage. The stages render full page rather than a wide tearsheet —
 * a deliberate, recorded deviation from Carbon (ADR-0014). Do not "correct" it.
 */
export function MigrationWizardStagePage() {
  const { draftId, stage } = useParams();

  if (!isWizardStage(stage)) {
    return <NotFoundPage />;
  }

  return (
    <Page
      title={messages.wizard.title}
      lead={`${messages.wizard.stageLabel}：${messages.wizard.stages[stage]}`}
    >
      <p>
        {messages.wizard.draftLabel} <Identifier>{draftId}</Identifier>
      </p>
      <p>{messages.placeholder.notYetBuilt}</p>
    </Page>
  );
}
