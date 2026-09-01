import type {
  DatabaseConnection,
  DraftTableConfiguration,
  ExecutionConfirmationSummary,
  MigrationDraft,
} from '@/contract';
import { messages } from '@/messages';
import { wizardStages, type WizardStage } from '@/routes/paths';

/**
 * The wizard's stage gating (ADR-0007).
 *
 * > "The stages are a safety sequence, not merely navigation. Forward movement is gated …
 * > Operators may return to completed stages, but cannot use progress navigation to bypass
 * > a gate."
 *
 * The mechanism is one idea: **each stage owns one gate, and a gate answers a single
 * question — may the operator leave this stage yet?** Reachability is then derived rather
 * than stored. Walk the stages in journey order; the first stage whose gate blocks is as
 * far as this draft goes, and every stage before it is complete and revisitable.
 *
 * Deriving it is what makes the gate hold against a typed URL as well as a click. A stored
 * "furthest stage reached" would be a second source of truth that a deep link can walk
 * straight past — and every stage has its own URL by design (#30), so a deep link is the
 * normal way in, not an edge case. Here, `/tasks/new/:draftId/tables` on a draft with no
 * tables selected is blocked by exactly the same evaluation that disables 下一步.
 *
 * **How a later stage declares its gate**: add its rule to `wizardStageGates` below, in the
 * stage's own entry, returning the sentence from `messages.wizard.gates`. Nothing else has
 * to change — the progress indicator, the footer, the redirect and the tests all read this
 * table. Every one of the four configuration stages now has a real rule; nothing here is a
 * placeholder.
 */

/** Everything a gate is allowed to look at. Gates are pure functions of this. */
export interface WizardGateContext {
  readonly draft: MigrationDraft;
  /** Needed by stage one: a connection can go bad after it was chosen. */
  readonly connections: readonly DatabaseConnection[];
  /**
   * The 逐表配置 of every table in the 迁移范围, or `null` while it is still being read.
   *
   * Null is a state rather than an omission: until the summaries are in, stage three's
   * gate cannot say whether every table has a 表写入契约, and answering 「not blocked」 on
   * missing evidence is how a safety sequence quietly stops being one. Added by #35; #36
   * reads the same list for Gate 2.
   */
  readonly tableConfigurations: readonly DraftTableConfiguration[] | null;
  /**
   * The 执行确认 summary of this draft, or `null` while it is still being read.
   *
   * Same polarity as `tableConfigurations`, for the same reason: an unknown safety fact is
   * not a satisfied one. Gate 6's whole content is a statement the *server* makes — which
   * tables it can establish a 结构证明 for — so the gate cannot compute it and must not
   * guess at it. Added by #37.
   */
  readonly executionSummary: ExecutionConfirmationSummary | null;
}

/**
 * Whether a stage lets the operator move on, and if not, why in domain language.
 *
 * A gate returns the reason rather than a boolean because a constraint the interface
 * cannot explain is indistinguishable from a bug: #30 requires a blocked state to say what
 * would unblock it, and requires that the state cannot simply be dismissed.
 */
export type StageGateResult =
  { readonly blocked: false } | { readonly blocked: true; readonly reason: string };

const passes: StageGateResult = { blocked: false };

const blockedBy = (reason: string): StageGateResult => ({ blocked: true, reason });

function connectionById(
  connections: readonly DatabaseConnection[],
  id: string | null,
): DatabaseConnection | undefined {
  return id === null ? undefined : connections.find((connection) => connection.id === id);
}

/**
 * Stage one: 连接与数据库.
 *
 * Two rules, both from the acceptance criteria of #34. The pair must be complete — a half
 * configured draft must not travel forward — and a chosen 数据库连接 whose 最近校验 is no
 * longer `SUCCEEDED` blocks immediately, rather than surfacing three stages later when the
 * operator is about to start a production migration.
 */
function connectionsGate({ draft, connections }: WizardGateContext): StageGateResult {
  if (
    draft.sourceConnectionId === null ||
    draft.sourceDatabase === null ||
    draft.sourceDatabase === '' ||
    draft.targetConnectionId === null ||
    draft.targetSchema === null ||
    draft.targetSchema === ''
  ) {
    return blockedBy(messages.wizard.gates.connectionsIncomplete);
  }

  for (const id of [draft.sourceConnectionId, draft.targetConnectionId]) {
    const connection = connectionById(connections, id);
    // A connection the wizard cannot even see is not one it may migrate through.
    if (connection === undefined) {
      return blockedBy(messages.wizard.gates.connectionsIncomplete);
    }
    if (connection.latestCheck.outcome !== 'SUCCEEDED') {
      return blockedBy(
        messages.wizard.gates.connectionUnusable(connection.name, connection.latestCheck.outcome),
      );
    }
  }

  return passes;
}

/**
 * Stage two: 迁移范围. **This is Gate 1** of the nine journey gates (#30 §15.4).
 *
 * An empty 迁移范围 would produce a migration task with nothing in it, so the wizard stops
 * here rather than letting the operator discover it at 执行确认.
 */
function scopeGate({ draft }: WizardGateContext): StageGateResult {
  return draft.selectedTables.length > 0
    ? passes
    : blockedBy(messages.wizard.gates.noTableSelected);
}

/**
 * Stage three: 逐表配置与预检.
 *
 * The clause below is #35's, and it is the one ADR-0011 forces: a 表写入契约 is 「the
 * immutable, single-table write intent」, and DBX may not assemble one while a mapping
 * exception it refuses to decide — the *approved* per-column zero-date relaxation — is
 * still undecided. A table in the 迁移范围 with no contract has nothing for 执行确认 to
 * summarise and nothing for a 结构证明 to compare against, so the wizard stops here.
 *
 * **This is also Gate 2** (#36): 「`UNSUPPORTED` 或 `INCONCLUSIVE` 的预检不能被批准」.
 * `CONTEXT.md` states it as a property of 预检 itself — 「only `SUPPORTED` may proceed」 —
 * and ADR-0011 repeats it for approval. The three preflight clauses are evaluated *before*
 * #35's contract clause, in the order a safety sequence reads: an unfinished scan is not a
 * conclusion, a conclusion that is not `SUPPORTED` cannot be approved whatever its mapping
 * says, and only then is an undecided mapping worth mentioning. Deciding a mapping does not
 * make an `UNSUPPORTED` table migratable, so naming the contract first would send the
 * operator to do work that changes nothing.
 *
 * None of these clauses can be satisfied by a click. The exits are stage three's — fix the
 * source and rerun, cut the offending column and rerun, or take the table out of the
 * 迁移范围 — and every one of them changes a fact rather than a judgement.
 */
function tablesGate({ tableConfigurations }: WizardGateContext): StageGateResult {
  if (tableConfigurations === null) {
    return blockedBy(messages.wizard.gates.tableConfigurationsUnread);
  }

  // A conclusion that has not been reached is not a conclusion in DBX's favour.
  const running = tableConfigurations.filter(
    (configuration) => configuration.preflightConclusion === null,
  );
  const firstRunning = running[0];
  if (firstRunning !== undefined) {
    return blockedBy(
      messages.wizard.gates.preflightInFlight(running.length, firstRunning.sourceTable),
    );
  }

  // Gate 2 itself.
  const notSupported = tableConfigurations.filter(
    (configuration) => configuration.preflightConclusion !== 'SUPPORTED',
  );
  const firstNotSupported = notSupported[0];
  if (firstNotSupported !== undefined) {
    return blockedBy(
      messages.wizard.gates.preflightNotSupported(
        notSupported.length,
        firstNotSupported.sourceTable,
        firstNotSupported.preflightConclusion ?? '',
      ),
    );
  }

  // A `SUPPORTED` conclusion carrying blocking findings would be a contradiction, and the
  // gate refuses the contradiction rather than picking whichever half it likes better.
  const withBlockingFindings = tableConfigurations.filter(
    (configuration) => configuration.blockingFindingCount > 0,
  );
  const firstBlocked = withBlockingFindings[0];
  if (firstBlocked !== undefined) {
    return blockedBy(
      messages.wizard.gates.preflightBlockingFindings(
        withBlockingFindings.length,
        firstBlocked.sourceTable,
      ),
    );
  }

  const withoutContract = tableConfigurations.filter(
    (configuration) => configuration.contractVersion === null,
  );
  const first = withoutContract[0];
  if (first !== undefined) {
    return blockedBy(
      messages.wizard.gates.contractNotGenerated(withoutContract.length, first.sourceTable),
    );
  }

  return passes;
}

/**
 * Stage four: 执行确认. **This is Gate 5 and Gate 6** (#30 §15.4, #37).
 *
 * The gate never passes, and that is the point rather than an omission. Leaving 执行确认
 * is not something 「下一步」 does: what leaves it is 「开始迁移」, which creates a 迁移任务
 * and an immutable 迁移运行 and ends the draft. 运行监控 belongs to that run, so a draft is
 * never inside it — exactly as it is never inside 校验报告.
 *
 * What the gate is for is the two constraints the start button reads, in the order a
 * safety sequence reads them:
 *
 *  - **Gate 5** — 「没有写冻结确认就无法启动」. `CONTEXT.md` makes a 写冻结 an externally
 *    enforced, time-bounded commitment with an accountable operator, and lists
 *    「permanent checkbox」 under `_Avoid_`: a declaration with no named 责任人 or no 时限
 *    is not one, so it blocks exactly as an absent one does.
 *  - **Gate 6** — 「没有结构证明就不会开始写入目标」. This one is deliberately weaker than
 *    the other eight (lead decision D11), because 结构证明 is a deterministic catalog
 *    comparison the platform performs inside the run, after DDL. The frontend cannot
 *    perform it and does not pretend to: it refuses to start while the summary reports a
 *    table no 结构证明 can be established for, and the stage states the constraint in
 *    domain language beside the refusal.
 */
function confirmGate({ executionSummary, draft }: WizardGateContext): StageGateResult {
  if (executionSummary === null) {
    return blockedBy(messages.wizard.gates.executionSummaryUnread);
  }

  const freeze = draft.writeFreeze;
  if (
    freeze === null ||
    freeze.accountableOperator.trim() === '' ||
    !Number.isFinite(freeze.durationHours) ||
    freeze.durationHours <= 0
  ) {
    return blockedBy(messages.wizard.gates.writeFreezeNotConfirmed);
  }

  const gaps = executionSummary.structuralProof.gaps;
  const first = gaps[0];
  if (first !== undefined) {
    return blockedBy(
      messages.wizard.gates.structuralProofMissing(
        new Set(gaps.map((gap) => gap.sourceTable)).size,
        first.sourceTable,
      ),
    );
  }

  return blockedBy(messages.wizard.gates.runNotStarted);
}

/**
 * Whether 执行确认 would let the migration start, as the start button reads it.
 *
 * The stage's own gate never passes — 「下一步」 is not what leaves this stage — so the
 * button cannot simply ask whether the stage is blocked. It asks whether the only reason
 * left is that nobody has pressed it yet, which keeps one evaluation behind both the
 * refusal on screen and the refusal to act.
 */
export function mayStartMigration(context: WizardGateContext): boolean {
  const gate = confirmGate(context);
  return gate.blocked && gate.reason === messages.wizard.gates.runNotStarted;
}

/**
 * 运行监控 and 校验报告 observe a 迁移运行, and a draft has none: a draft "produces no
 * migration run" by definition (`CONTEXT.md`). They keep their routes — every stage has its
 * own URL — but no draft is ever inside them.
 */
const belongsToRun = (): StageGateResult => blockedBy(messages.wizard.gates.stageBelongsToRun);

export const wizardStageGates: Readonly<
  Record<WizardStage, (context: WizardGateContext) => StageGateResult>
> = {
  connections: connectionsGate,
  scope: scopeGate,
  tables: tablesGate,
  confirm: confirmGate,
  monitor: belongsToRun,
  validation: belongsToRun,
};

export function evaluateStageGate(stage: WizardStage, context: WizardGateContext): StageGateResult {
  return wizardStageGates[stage](context);
}

/**
 * How far this draft may go: the first stage whose own gate blocks.
 *
 * Every earlier stage has been satisfied and stays open, which is exactly what ADR-0007
 * asks for — the operator may go back, but the same evaluation that let them forward is
 * what has to let them forward again.
 */
export function furthestReachableStage(context: WizardGateContext): WizardStage {
  for (const stage of wizardStages) {
    if (evaluateStageGate(stage, context).blocked) {
      return stage;
    }
  }
  return wizardStages[wizardStages.length - 1] as WizardStage;
}

export function isStageReachable(stage: WizardStage, context: WizardGateContext): boolean {
  return wizardStages.indexOf(stage) <= wizardStages.indexOf(furthestReachableStage(context));
}

/** A stage the operator has already satisfied, and may return to. */
export function isStageComplete(stage: WizardStage, context: WizardGateContext): boolean {
  return wizardStages.indexOf(stage) < wizardStages.indexOf(furthestReachableStage(context));
}

/**
 * The gate reasons that mean 「not read yet」 rather than 「not allowed」.
 *
 * A gate blocks on an unread fact for the same reason it blocks on a failed rule — an
 * unknown safety fact is not a satisfied one — but the two are not the same instruction
 * about *where the operator belongs*. A failed rule says: this draft's place is the stage
 * that stopped it. An unread fact says only: DBX does not know yet.
 *
 * Compared by identity against the messages, exactly as `mayStartMigration` compares
 * `runNotStarted`. The alternative is a second flag on `StageGateResult` that every gate
 * would have to remember to set.
 */
const unreadGateReasons: readonly string[] = [
  messages.wizard.gates.tableConfigurationsUnread,
  messages.wizard.gates.executionSummaryUnread,
];

/**
 * The stage a request for `requested` actually lands on.
 *
 * A reachable stage is served as asked. An unreachable one is not a 404 and not an error:
 * the operator asked for somewhere real that this draft has not earned yet, so they land on
 * the stage that is actually stopping them, where the reason is written down.
 *
 * **A draft is never moved on the strength of a fact DBX has not read.** Every stage is
 * deep-linkable, and the reads behind the later gates — the 逐表配置 summaries, the 执行确认
 * summary — settle after the 迁移草稿 itself does. Redirecting during that window would
 * throw an operator who typed `/confirm` back to 逐表配置与预检 depending on which request
 * happened to answer first, and the address they asked for would be lost. So while the only
 * thing stopping this draft is an unread fact, the requested stage is served and its own
 * loading state does the talking; the redirect follows the moment the fact arrives and turns
 * out to block. Nothing is unlocked by waiting: the gates still refuse 下一步 and 开始迁移
 * on the same unread reason.
 */
export function resolveStageEntry(requested: WizardStage, context: WizardGateContext): WizardStage {
  if (isStageReachable(requested, context)) {
    return requested;
  }
  const destination = furthestReachableStage(context);
  const gate = evaluateStageGate(destination, context);
  return gate.blocked && unreadGateReasons.includes(gate.reason) ? requested : destination;
}

export function nextStage(stage: WizardStage): WizardStage | null {
  return wizardStages[wizardStages.indexOf(stage) + 1] ?? null;
}

export function previousStage(stage: WizardStage): WizardStage | null {
  const index = wizardStages.indexOf(stage);
  return index <= 0 ? null : (wizardStages[index - 1] as WizardStage);
}
