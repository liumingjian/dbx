import { Button, InlineNotification, Tag } from '@carbon/react';
import { ConclusionIndicator, preflightNoticeKind } from '@/conclusions';
import { findingDetail, findingLabel } from '@/features/preflight/preflightVocabulary';
import type { DraftTableWorkspace, PreflightFinding } from '@/contract';
import { formatCount, formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { preflightBlocks, preflightIndicatorConclusion, prunableColumnsOf } from './preflightExits';

/**
 * 预检证据与阻断门禁 (#36) — the front of the findings pane.
 *
 * This is where DBX either holds a safety constraint or quietly gives it away, so three
 * things are structural rather than decorative:
 *
 * 1. **`INCONCLUSIVE` has its own form.** The indicator comes from `src/conclusions/`,
 *    where the mapping to `unknown` is fixed once for the whole product, and the notice
 *    below it is `info` rather than any caution variant (`./preflightExits.ts`). 「无法
 *    判定」 is a different statement from 「有点风险」, and the interface never blurs them.
 * 2. **Nothing here can be confirmed away.** There is no acknowledge control, no dismiss
 *    on the blocking notice, and no path from this pane to a conclusion. `CONTEXT.md`
 *    lists 「warning acknowledgement」 under 预检's `_Avoid_` for exactly this reason.
 * 3. **The three exits are the only things on offer**, and each of them changes a *fact*:
 *    the source is fixed and the scan rerun, an offending column is cut and the scan rerun
 *    against the remaining selected columns, or the table leaves the 迁移范围. ADR-0003
 *    fixes that list — 「exclude the table, exclude an offending selected column …, or
 *    cancel review and change the source outside DBX」 — and there is no fourth.
 *
 * A table can be several things at once, and all of them are stated: a 大记录表 that also
 * carries 映射例外 shows its bytes, its findings and its exception list together, because
 * the fixture guarantees such tables exist and a screen that showed the more dramatic half
 * would be worse than one that showed neither (story 47).
 */
interface PreflightPaneProps {
  readonly workspace: DraftTableWorkspace;
  readonly onRerun: () => void;
  readonly onPrune: (sourceColumn: string, pruned: boolean) => void;
  readonly onExclude: () => void;
  /** True while one of the three exits is still being written through. */
  readonly busy: boolean;
}

function findingRow(finding: PreflightFinding) {
  const copy = messages.wizard.tables.preflight;
  return (
    <li key={`${finding.code}:${finding.sourceColumn ?? ''}`} className="dbx-preflight__finding">
      <span className="dbx-preflight__finding-head">
        {/* The finding's name comes from `CONTEXT.md`; the sentence below states the
            exact fact behind it. The stable code stays a coordinate in the contract and
            in the diagnostic evidence, not something a DBA is asked to decode. */}
        <span className="dbx-preflight__finding-name">{findingLabel(finding.code)}</span>
        <Tag type={finding.blocking ? 'red' : 'cool-gray'} size="sm">
          {finding.blocking ? copy.blocking : copy.nonBlocking}
        </Tag>
        {finding.sourceColumn === null ? null : <Identifier>{finding.sourceColumn}</Identifier>}
        <Identifier>{findingDetail(finding.detail)}</Identifier>
      </span>
      <span className="dbx-preflight__finding-body">{copy.codes[finding.code]}</span>
    </li>
  );
}

export function PreflightPane({
  workspace,
  onRerun,
  onPrune,
  onExclude,
  busy,
}: PreflightPaneProps) {
  const copy = messages.wizard.tables.preflight;
  const { preflight } = workspace;
  const inFlight = preflight.conclusion === null;
  const blocked = preflightBlocks(preflight);
  const prunable = prunableColumnsOf(preflight);

  const envelopeFinding = preflight.findings.find(
    (finding) => finding.blocking && finding.code === 'LARGE_RECORD_VALUE',
  );
  const scanFinding = preflight.findings.find(
    (finding) => finding.code === 'ENVELOPE_SCAN_INCONCLUSIVE',
  );

  const blockingTitle =
    preflight.conclusion === 'INCONCLUSIVE'
      ? copy.inconclusiveTitle(findingDetail(scanFinding?.detail ?? ''))
      : envelopeFinding !== undefined
        ? copy.overEnvelopeTitle(
            workspace.sourceTable,
            envelopeFinding.sourceColumn ?? '',
            formatCount(Number(envelopeFinding.detail)),
          )
        : copy.unsupportedTitle(workspace.sourceTable);

  return (
    <section className="dbx-preflight" aria-label={copy.label}>
      <h4 className="dbx-wizard__pane-title">{copy.conclusionLabel}</h4>

      <p className="dbx-preflight__conclusion">
        <ConclusionIndicator conclusion={preflightIndicatorConclusion(preflight.conclusion)} />
        {inFlight ? (
          // Story 48: a scan that takes time says so, so nobody reads the screen as frozen.
          <span role="status">{copy.inFlight}</span>
        ) : (
          <span>
            {copy.evaluatedAt(
              preflight.evaluatedAt === null ? '' : formatTimestamp(preflight.evaluatedAt),
            )}
          </span>
        )}
      </p>
      {inFlight ? <p className="dbx-wizard__fact">{copy.inFlightNotice}</p> : null}

      {preflight.largeRecordTable ? (
        <p className="dbx-preflight__facts">
          <Tag type="cool-gray" size="sm">
            {copy.largeRecordTable}
          </Tag>
          {preflight.largestValueBytes === null ? null : (
            <span>{copy.largestValue(formatCount(preflight.largestValueBytes))}</span>
          )}
          {preflight.largestRowBytes === null ? null : (
            <span>{copy.largestRow(formatCount(preflight.largestRowBytes))}</span>
          )}
        </p>
      ) : null}
      {preflight.largeRecordTable ? <p className="dbx-wizard__fact">{copy.envelope}</p> : null}

      {inFlight ? null : (
        <>
          <h5 className="dbx-preflight__subtitle">{copy.findingsLabel}</h5>
          {preflight.findings.length === 0 ? (
            <p className="dbx-wizard__fact">{copy.noFindings}</p>
          ) : (
            <ul className="dbx-preflight__findings">{preflight.findings.map(findingRow)}</ul>
          )}
        </>
      )}

      {workspace.prunedColumns.length === 0 ? null : (
        <p className="dbx-preflight__facts">
          <span>{copy.exits.pruneColumn.prunedHeading}</span>
          {workspace.prunedColumns.map((column) => (
            <span key={column} className="dbx-preflight__pruned">
              <Identifier>{column}</Identifier>
              <Button kind="ghost" size="sm" disabled={busy} onClick={() => onPrune(column, false)}>
                {copy.exits.pruneColumn.restoreAction(column)}
              </Button>
            </span>
          ))}
        </p>
      )}

      {blocked && !inFlight ? (
        <>
          <InlineNotification
            kind={preflightNoticeKind(preflight.conclusion ?? 'UNSUPPORTED')}
            lowContrast
            // Story 103: a blocked state cannot be dismissed. A close button here would be
            // one click between a DBA and a safety constraint they never decided to drop.
            hideCloseButton
            role="alert"
            title={copy.conclusionLabel}
            subtitle={blockingTitle}
          />

          <section className="dbx-preflight__exits" aria-label={copy.exits.heading}>
            <h5 className="dbx-preflight__subtitle">{copy.exits.heading}</h5>
            <p className="dbx-wizard__fact">{copy.exits.noFourth}</p>

            <div className="dbx-preflight__exit">
              <h6>{copy.exits.fixSource.title}</h6>
              <p>{copy.exits.fixSource.body}</p>
              <Button kind="tertiary" size="sm" disabled={busy} onClick={onRerun}>
                {copy.exits.fixSource.action}
              </Button>
            </div>

            <div className="dbx-preflight__exit">
              <h6>{copy.exits.pruneColumn.title}</h6>
              <p>{copy.exits.pruneColumn.body}</p>
              {prunable.length === 0 ? (
                <p className="dbx-wizard__fact">{copy.exits.pruneColumn.unavailable}</p>
              ) : (
                prunable.map((column) => (
                  <Button
                    key={column}
                    kind="tertiary"
                    size="sm"
                    disabled={busy}
                    onClick={() => onPrune(column, true)}
                  >
                    {copy.exits.pruneColumn.action(column)}
                  </Button>
                ))
              )}
            </div>

            <div className="dbx-preflight__exit">
              <h6>{copy.exits.excludeTable.title}</h6>
              <p>{copy.exits.excludeTable.body}</p>
              <Button kind="danger--tertiary" size="sm" disabled={busy} onClick={onExclude}>
                {copy.exits.excludeTable.action}
              </Button>
            </div>
          </section>
        </>
      ) : null}
    </section>
  );
}
