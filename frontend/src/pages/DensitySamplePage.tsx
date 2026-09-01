import { messages } from '@/messages';
import { Page } from './Page';

/**
 * The 32px Chinese density sample (ADR-0014).
 *
 * This page exists to be looked at by a human: it is the moment the project decides
 * whether Chinese body text is readable at a 32px row height. Everything that follows —
 * the 1200-row table selector, the run monitoring matrix — is planned against that answer.
 *
 * It is a plain table on purpose. `DbxTable` arrives in the next batch (ADR-0015), and
 * the question here is typography, not table behaviour.
 */
const sampleRows = [
  {
    sourceTable: 'order_item',
    targetTable: 'public.order_item',
    rowCount: '48,271,903',
    phase: messages.densitySample.phases.writeComplete,
    conclusion: messages.densitySample.conclusions.supported,
  },
  {
    sourceTable: 'orderitem_archive',
    targetTable: 'public.orderitem_archive',
    rowCount: '9,120,447',
    phase: messages.densitySample.phases.readComplete,
    conclusion: messages.densitySample.conclusions.supported,
  },
  {
    sourceTable: 'customer_profile_attachment',
    targetTable: 'public.customer_profile_attachment',
    rowCount: '1,204,880',
    phase: messages.densitySample.phases.stuck,
    conclusion: messages.densitySample.conclusions.inconclusive,
  },
  {
    sourceTable: 'settlement_ledger_2024',
    targetTable: 'public.settlement_ledger_2024',
    rowCount: '312,005',
    phase: messages.densitySample.phases.migrationComplete,
    conclusion: messages.densitySample.conclusions.supported,
  },
  {
    sourceTable: 'legacy_enum_flags',
    targetTable: 'public.legacy_enum_flags',
    rowCount: '84',
    phase: messages.densitySample.phases.readComplete,
    conclusion: messages.densitySample.conclusions.unsupported,
  },
] as const;

const columns = messages.densitySample.columns;

function SampleTable({ density }: { density: 'condensed' | 'comfortable' }) {
  return (
    <table
      className={`dbx-density-sample__table dbx-density-sample--${density}`}
      data-testid={`density-sample-${density}`}
    >
      <thead>
        <tr>
          <th scope="col">{columns.sourceTable}</th>
          <th scope="col">{columns.targetTable}</th>
          <th scope="col">{columns.rowCount}</th>
          <th scope="col">{columns.phase}</th>
          <th scope="col">{columns.conclusion}</th>
        </tr>
      </thead>
      <tbody>
        {sampleRows.map((row) => (
          <tr key={row.sourceTable}>
            <td className="dbx-identifier">{row.sourceTable}</td>
            <td className="dbx-identifier">{row.targetTable}</td>
            <td className="dbx-identifier">{row.rowCount}</td>
            <td>{row.phase}</td>
            <td>{row.conclusion}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function DensitySamplePage() {
  return (
    <Page title={messages.densitySample.title} lead={messages.densitySample.lead}>
      <p className="cds--label">{messages.densitySample.condensedHeading}</p>
      <SampleTable density="condensed" />
      <p className="cds--label" style={{ marginTop: '2rem' }}>
        {messages.densitySample.comfortableHeading}
      </p>
      <SampleTable density="comfortable" />
    </Page>
  );
}
