import type { ReactNode } from 'react';
import type { MappingException, MappingRuleAction } from '@/contract';
import { messages } from '@/messages';
import { MappingExceptions } from './MappingExceptions';

/**
 * The workspace's third pane: 发现.
 *
 * It holds everything about this table that a person has to look at before the migration
 * runs, in one list rather than in tabs — the prototype's three-tab drawer is deliberately
 * not carried forward (ADR-0014:22 and #30 both specify three panes side by side).
 *
 * **#36 mounts the 预检 conclusions and their three exits in `children`**, above the
 * mapping exceptions: 「修正源 / 裁掉某个字段后重跑预检 / 显式排除该表」 belong with the
 * conclusion that produced them, and the blocking gate they feed is stage three's, in
 * `src/wizard/stageGates.ts`. Nothing else in this pane has to move for that.
 */
interface FindingsPaneProps {
  readonly exceptions: readonly MappingException[];
  readonly onChoose: (sourceColumn: string, action: MappingRuleAction, targetValue: string) => void;
  readonly recording: boolean;
  /** #36's 预检 panel. */
  readonly children?: ReactNode;
}

export function FindingsPane({ exceptions, onChoose, recording, children }: FindingsPaneProps) {
  const undecided = exceptions.filter((exception) => exception.rule === null).length;

  return (
    <section className="dbx-workspace__findings" aria-label={messages.wizard.tables.findingsLabel}>
      <h3 className="dbx-wizard__pane-title">{messages.wizard.tables.findingsLabel}</h3>
      {children}
      {undecided > 0 ? (
        <p className="dbx-workspace__undecided" role="status">
          {messages.wizard.tables.undecided(undecided)}
        </p>
      ) : null}
      <MappingExceptions exceptions={exceptions} onChoose={onChoose} recording={recording} />
    </section>
  );
}
