import { useEffect, useRef } from 'react';
import { Button } from '@carbon/react';
import { EmptyState, ErrorState, LoadingState } from '@/components/ViewState';
import { ConclusionIndicator, tableMigrationConclusion } from '@/conclusions';
import type { Diagnosis, ErrorOccurrence, TableMigrationUnitEvidence } from '@/contract';
import { formatCount, formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { phaseLabel, presentRootCauseDomain, unitOutcomeLabel } from './runVocabulary';

/**
 * 单表证据抽屉 — one 表迁移单元's evidence, over 运行监控 (#39).
 *
 * It is an overlay **with its own URL**. That combination is the whole ticket: a DBA who
 * has found the table that failed must be able to paste an address into a ticket and have
 * a colleague land on this exact screen, and a refresh must restore it rather than drop
 * them back on the run. The prototype's `?variant=A` switch — a drawer whose state lives
 * in a component — is explicitly not carried forward, which is why the drawer is a nested
 * **route** rather than a piece of local state (ADR-0016).
 *
 * What it shows is ADR-0005's separation, kept visible rather than flattened into a single
 * red message:
 *
 *  - the **错误事件** are observed facts, aggregated by fingerprint with their first and
 *    last observation and a count, each carrying the evidence reference support can
 *    retrieve. A later interpretation never rewrites them.
 *  - the **诊断** is a separate, versioned interpretation, with a stable code, the catalog
 *    version it was reached under, and exactly one 根因域.
 *  - the unit's **技术结果** is neither, and the drawer never lets a diagnosis look like
 *    one — a stalled table has a diagnosis and no outcome at all.
 *
 * The 根因域 is what answers 「源问题还是目标问题」, and it is presented through
 * `presentRootCauseDomain`, the single site for that translation: the specific execution
 * platform domain stays in the evidence for support, and the operator reads 迁移平台
 * (`CONTEXT.md`, Gate 7). An unidentified failure says so outright rather than picking the
 * nearest plausible cause.
 */

interface TableEvidenceDrawerProps {
  readonly evidence: TableMigrationUnitEvidence | null;
  readonly pending: boolean;
  /** A read that failed and can be tried again. */
  readonly failed: boolean;
  /** The link names a 表迁移单元 this run does not contain. A retry cannot help. */
  readonly missing: boolean;
  readonly onRetry: () => void;
  readonly onClose: () => void;
}

function DiagnosisSection({ diagnosis }: { diagnosis: Diagnosis | null }) {
  const copy = messages.run.evidence.diagnosis;

  if (diagnosis === null) {
    return (
      <section className="dbx-drawer__section" aria-label={copy.heading}>
        <h3 className="dbx-drawer__section-title">{copy.heading}</h3>
        <p className="dbx-drawer__statement">{copy.none.title}</p>
        <p className="dbx-run__muted">{copy.none.body}</p>
      </section>
    );
  }

  const entry = copy.codes[diagnosis.code];
  return (
    <section className="dbx-drawer__section" aria-label={copy.heading}>
      <h3 className="dbx-drawer__section-title">{copy.heading}</h3>
      {/* What happened, first — before any classification of it. */}
      <p className="dbx-drawer__statement">{entry.summary}</p>
      <p>
        <strong>{copy.affectedHeading}</strong>：{entry.affected}
      </p>
      <p>
        <strong>{copy.actionHeading}</strong>：{entry.action}
      </p>
      <ul className="dbx-run__facts">
        {/*
          根因域 is the answer to 「源问题还是目标问题」. `Kafka Connect` and `Kafka` are
          presented as the single 迁移平台 domain; the specific one is retained in the
          evidence for support use, and is not the operator's business.
        */}
        <li>{copy.rootCauseDomain(presentRootCauseDomain(diagnosis.rootCauseDomain))}</li>
        <li>{copy.code(diagnosis.code)}</li>
        <li>{copy.catalogVersion(diagnosis.catalogVersion)}</li>
        <li>{copy.sourceKind(copy.sourceKinds[diagnosis.sourceKind])}</li>
      </ul>
    </section>
  );
}

function OccurrenceItem({ occurrence }: { occurrence: ErrorOccurrence }) {
  const copy = messages.run.evidence.occurrences;
  return (
    <li className="dbx-drawer__occurrence">
      <ul className="dbx-run__facts">
        <li>{copy.observedPhase(phaseLabel(occurrence.observedPhase))}</li>
        <li>{copy.firstObservedAt(formatTimestamp(occurrence.firstObservedAt))}</li>
        <li>{copy.lastObservedAt(formatTimestamp(occurrence.lastObservedAt))}</li>
        <li>{copy.observationCount(occurrence.observationCount)}</li>
        <li>{copy.evidenceReference(occurrence.evidenceReference)}</li>
      </ul>
      <p className="dbx-drawer__detail-heading">{copy.detailHeading}</p>
      {/*
        Server-produced evidence, quoted verbatim: it is what a DBA pastes into a ticket,
        so it is neither translated nor summarised.
      */}
      <pre className="dbx-drawer__detail">{occurrence.detail}</pre>
    </li>
  );
}

function EvidenceBody({ evidence }: { evidence: TableMigrationUnitEvidence }) {
  const copy = messages.run.evidence;
  const unit = evidence.unit;
  const contract = unit.tableWriteContract;
  const proof = unit.structuralProof;

  return (
    <>
      <p className="dbx-run__notice">{copy.lead}</p>

      <section className="dbx-drawer__section" aria-label={copy.identityHeading}>
        <h3 className="dbx-drawer__section-title">{copy.identityHeading}</h3>
        <p>
          {messages.run.sourceLabel} <Identifier>{unit.sourceTable}</Identifier>
          {' → '}
          {messages.run.targetLabel} <Identifier>{unit.targetTable}</Identifier>
        </p>
        <ul className="dbx-run__facts">
          <li>
            {copy.phaseLabel} {phaseLabel(unit.phase)}
          </li>
          <li>
            {copy.outcomeLabel}{' '}
            <ConclusionIndicator
              conclusion={tableMigrationConclusion(unit.outcome)}
              label={unitOutcomeLabel(unit.outcome)}
            />
          </li>
          <li>
            {copy.progressLabel}{' '}
            {unit.progress === null
              ? messages.run.noObservation
              : messages.run.matrix.progressOf(
                  formatCount(unit.progress.sourceRowsRead),
                  formatCount(unit.sourceBaselineRowCount ?? 0),
                )}
          </li>
          {/* An observation carries its own instant here too (ADR-0004). */}
          <li>{copy.observedAt(formatTimestamp(evidence.observedAt))}</li>
        </ul>
      </section>

      <DiagnosisSection diagnosis={evidence.diagnosis} />

      <section className="dbx-drawer__section" aria-label={copy.occurrences.heading}>
        <h3 className="dbx-drawer__section-title">{copy.occurrences.heading}</h3>
        <p className="dbx-run__muted">{copy.occurrences.lead}</p>
        {evidence.occurrences.length === 0 ? (
          <p>{copy.occurrences.empty}</p>
        ) : (
          <ul className="dbx-drawer__occurrences">
            {evidence.occurrences.map((occurrence) => (
              <OccurrenceItem key={occurrence.id} occurrence={occurrence} />
            ))}
          </ul>
        )}
      </section>

      <section className="dbx-drawer__section" aria-label={copy.contract.heading}>
        <h3 className="dbx-drawer__section-title">{copy.contract.heading}</h3>
        {contract === null || proof === null ? (
          <p>{copy.contract.none}</p>
        ) : (
          <ul className="dbx-run__facts">
            <li>{copy.contract.version(contract.version)}</li>
            <li>
              {contract.approvedAt === null
                ? copy.contract.none
                : copy.contract.approvedAt(formatTimestamp(contract.approvedAt))}
            </li>
            <li>{copy.contract.columnCount(contract.columns.length)}</li>
            <li>
              {proof.matchesContract && proof.provenAt !== null
                ? copy.contract.proven(formatTimestamp(proof.provenAt))
                : copy.contract.notProven}
            </li>
            {proof.differences.length === 0 ? null : (
              <li>
                {copy.contract.differencesHeading}：{proof.differences.join('；')}
              </li>
            )}
          </ul>
        )}
      </section>
    </>
  );
}

export function TableEvidenceDrawer({
  evidence,
  pending,
  failed,
  missing,
  onRetry,
  onClose,
}: TableEvidenceDrawerProps) {
  const copy = messages.run.evidence;
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    panel.current?.focus();
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className="dbx-drawer">
      {/*
        The scrim dismisses, and dismissing is a navigation: closing returns to the run's
        own URL rather than hiding a panel the address bar still claims is open.
      */}
      <div className="dbx-drawer__scrim" role="presentation" onClick={onClose} />
      <div
        className="dbx-drawer__panel"
        role="dialog"
        aria-modal="true"
        aria-label={copy.title}
        tabIndex={-1}
        ref={panel}
      >
        <div className="dbx-drawer__header">
          <h2 className="dbx-drawer__title">{copy.title}</h2>
          <Button kind="ghost" size="sm" onClick={onClose}>
            {copy.close}
          </Button>
        </div>
        <div className="dbx-drawer__body">
          {pending ? <LoadingState description={copy.loading} /> : null}
          {!pending && missing ? (
            <EmptyState title={copy.notFound.title} body={copy.notFound.body} />
          ) : null}
          {!pending && failed && !missing ? (
            <ErrorState title={copy.error.title} body={copy.error.body} onRetry={onRetry} />
          ) : null}
          {!pending && evidence !== null ? <EvidenceBody evidence={evidence} /> : null}
        </div>
      </div>
    </div>
  );
}
