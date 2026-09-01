import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { messages } from '@/messages';
import { enterScenario, renderRoute } from '@/test/render';

/**
 * 单表证据抽屉 at seam ② (#39).
 *
 * The behaviour under test is the one that makes a conclusion citable: **the drawer is
 * restored from its URL**. Entering `/runs/:runId/tables/:unitId` directly must produce the
 * drawer *over* 运行监控 — not instead of it, and not an empty page that needs a click to
 * become the screen the colleague was looking at.
 *
 * What is asserted is domain language: the 根因域 that tells a source problem from a target
 * problem, the sections named 诊断 and 错误事件, and the honest states. Never a Carbon class
 * and never a DOM shape.
 */

const runId = 'run-monitored';
/** 「部分表失败」's failing tables, in the order the run reaches them. */
const sourceProblemUnitId = `${runId}-unit-2`;
const targetProblemUnitId = `${runId}-unit-6`;

function openAt(unitId: string, scenario: string): void {
  const path = `/runs/${runId}/tables/${unitId}`;
  // The scenario lives in the URL, the way a reviewer's link carries it.
  enterScenario(scenario, path);
  renderRoute(`${path}?scenario=${scenario}`);
}

function drawer() {
  return screen.findByRole('dialog', { name: messages.run.evidence.title });
}

/** A section of the drawer, waited for: the evidence read settles after the first paint. */
function drawerRegion(name: string) {
  return screen.findByRole('region', { name });
}

describe('a table’s evidence is restored from its own URL', () => {
  it('renders the drawer over 运行监控 rather than instead of it', async () => {
    openAt(sourceProblemUnitId, 'partial-table-failure');

    expect(await drawer()).toBeInTheDocument();
    // The run is still the page underneath: closing the drawer returns to it, and the
    // operator never lost their place to read one table's evidence.
    expect(await screen.findByRole('heading', { name: messages.run.title })).toBeInTheDocument();
  });

  it('tells a source problem from a target problem by 根因域', async () => {
    openAt(sourceProblemUnitId, 'partial-table-failure');
    const diagnosis = await drawerRegion(messages.run.evidence.diagnosis.heading);

    expect(diagnosis).toHaveTextContent(
      messages.run.evidence.diagnosis.rootCauseDomain('SOURCE_DATABASE'),
    );
    expect(diagnosis).toHaveTextContent(
      messages.run.evidence.diagnosis.codes['DBX-SOURCE-PERMISSION-DENIED'].summary,
    );
    // The one recommended action, stated rather than left to the reader.
    expect(diagnosis).toHaveTextContent(messages.run.evidence.diagnosis.actionHeading);
  });

  it('names the target database when the target refused the write', async () => {
    openAt(targetProblemUnitId, 'partial-table-failure');

    expect(await drawerRegion(messages.run.evidence.diagnosis.heading)).toHaveTextContent(
      messages.run.evidence.diagnosis.rootCauseDomain('TARGET_DATABASE'),
    );
  });

  it('shows the 错误事件 as observed facts, aggregated rather than repeated', async () => {
    openAt(sourceProblemUnitId, 'partial-table-failure');
    const occurrences = await drawerRegion(messages.run.evidence.occurrences.heading);

    // ADR-0005: an occurrence is an append-only fact with its own first and last
    // observation and a count — a diagnosis never rewrites it.
    expect(occurrences).toHaveTextContent(/首次观测 \d{4}-\d{2}-\d{2}/);
    expect(occurrences).toHaveTextContent(/最近观测 \d{4}-\d{2}-\d{2}/);
    expect(occurrences).toHaveTextContent(/共观测 \d+ 次/);
    // The immutable reference support can retrieve the raw evidence by.
    expect(occurrences).toHaveTextContent(/证据引用/);
  });

  it('presents the execution platform as 迁移平台 when a table is stopped alongside another', async () => {
    // Gate 7 binds this surface too: 根因域's `Kafka Connect` and `Kafka` are presented as
    // the single 迁移平台 domain, and the specific one stays in the evidence for support.
    openAt(`${runId}-unit-5`, 'stuck-table');
    const diagnosis = await drawerRegion(messages.run.evidence.diagnosis.heading);

    expect(diagnosis).toHaveTextContent(
      messages.run.evidence.diagnosis.rootCauseDomain('迁移平台'),
    );
    const panel = await drawer();
    for (const forbidden of ['箱', '连接器', 'topic', 'Kafka', 'connector']) {
      expect(panel.textContent ?? '').not.toContain(forbidden);
    }
  });

  it('reaches no diagnosis for a table that has not failed', async () => {
    openAt(`${runId}-unit-1`, 'default');
    const diagnosis = await drawerRegion(messages.run.evidence.diagnosis.heading);

    expect(diagnosis).toHaveTextContent(messages.run.evidence.diagnosis.none.title);
    expect(await drawerRegion(messages.run.evidence.occurrences.heading)).toHaveTextContent(
      messages.run.evidence.occurrences.empty,
    );
  });
});

/**
 * Closing — and browser back and forward across it — is asserted at **seam ①**
 * (`e2e/table-evidence.spec.ts`), not here.
 *
 * Not a weakening of the claim but a relocation of it (lead decision D26): a client-side
 * navigation in this jsdom environment never completes, because the data router builds a
 * `Request` for every navigation and MSW's interceptor rejects the `AbortSignal` jsdom
 * hands it. That is a property of the test environment rather than of the drawer, and the
 * behaviour it would check — the URL returning to the run, and back and forward retracing
 * it — is exactly what a real engine can check and jsdom cannot.
 */

describe('the drawer has a state for every answer it can get', () => {
  it('says the read is still running rather than showing an empty panel', async () => {
    openAt(sourceProblemUnitId, 'loading');
    expect(await drawer()).toHaveTextContent(messages.run.evidence.loading);
  });

  it('offers a retry when the read failed', async () => {
    openAt(sourceProblemUnitId, 'error');

    expect(await screen.findByText(messages.run.evidence.error.title)).toBeInTheDocument();
    const panel = await drawer();
    expect(within(panel).getByRole('button', { name: messages.common.retry })).toBeInTheDocument();
  });

  it('says a table this run does not contain is missing, not broken', async () => {
    // A link naming a stranger's table cannot be fixed by trying again, and offering a
    // retry would be an invitation to keep pressing it.
    openAt('no-such-unit', 'partial-table-failure');

    expect(await screen.findByText(messages.run.evidence.notFound.title)).toBeInTheDocument();
    const panel = await drawer();
    expect(within(panel).queryByRole('button', { name: messages.common.retry })).toBeNull();
  });
});
