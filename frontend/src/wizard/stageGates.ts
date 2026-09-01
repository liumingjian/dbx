import type { DatabaseConnection, MigrationDraft } from '@/contract';
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
 * table. #35 (`tables`) and #37 (`confirm`) replace the placeholders that stand there now.
 */

/** Everything a gate is allowed to look at. Gates are pure functions of this. */
export interface WizardGateContext {
  readonly draft: MigrationDraft;
  /** Needed by stage one: a connection can go bad after it was chosen. */
  readonly connections: readonly DatabaseConnection[];
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
 * A stage whose rule belongs to a ticket that has not landed yet.
 *
 * It blocks, and says so plainly. The alternative — letting the draft walk through a stage
 * that enforces nothing — would put an unguarded stage into the journey and quietly make
 * the gating mechanism look weaker than it is.
 */
const notYetDelivered = (): StageGateResult =>
  blockedBy(messages.wizard.gates.stageNotYetDelivered);

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
  /** #35 replaces this with 「预检结论与生成的契约」. */
  tables: notYetDelivered,
  /** #37 replaces this with Gate 5 (写冻结) and Gate 6 (结构证明). */
  confirm: notYetDelivered,
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
 * The stage a request for `requested` actually lands on.
 *
 * A reachable stage is served as asked. An unreachable one is not a 404 and not an error:
 * the operator asked for somewhere real that this draft has not earned yet, so they land on
 * the stage that is actually stopping them, where the reason is written down.
 */
export function resolveStageEntry(requested: WizardStage, context: WizardGateContext): WizardStage {
  return isStageReachable(requested, context) ? requested : furthestReachableStage(context);
}

export function nextStage(stage: WizardStage): WizardStage | null {
  return wizardStages[wizardStages.indexOf(stage) + 1] ?? null;
}

export function previousStage(stage: WizardStage): WizardStage | null {
  const index = wizardStages.indexOf(stage);
  return index <= 0 ? null : (wizardStages[index - 1] as WizardStage);
}
