import { useMemo } from 'react';
import { Select, SelectItem } from '@carbon/react';
import { DbxTable, type DbxTableColumn } from '@/components/DbxTable';
import type { MappingException, MappingRuleAction } from '@/contract';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';

/**
 * The structured 映射例外, collected in one place (stories 36 and 37).
 *
 * Automatic mapping is the default, so this list is short by design: what reaches it is
 * the coordinates DBX could not settle from the database pair's rules alone. Reviewing a
 * handful of exceptions is the whole reason a DBA does not have to check every field of a
 * sixty-column table by hand.
 *
 * Every control here is bounded. `CONTEXT.md` says a 映射规则 「names one source
 * coordinate, one bounded action, its target value」 and 「never contains arbitrary SQL or
 * regular expressions in v1」, so the interface offers a closed list of target values and
 * nothing that accepts typing. That is the same constraint Gate 4 states from the other
 * side: the DDL is read-only *because* this is where structure changes.
 */
interface MappingExceptionsProps {
  readonly exceptions: readonly MappingException[];
  readonly onChoose: (sourceColumn: string, action: MappingRuleAction, targetValue: string) => void;
  /** True while a recorded rule is still being written through. */
  readonly recording: boolean;
}

export function MappingExceptions({ exceptions, onChoose, recording }: MappingExceptionsProps) {
  const columns = useMemo<readonly DbxTableColumn<MappingException>[]>(
    () => [
      {
        id: 'sourceColumn',
        header: messages.wizard.tables.mappingColumns.sourceColumn,
        identifying: true,
        width: 180,
        textValue: (exception) => exception.sourceColumn,
        renderCell: (exception) => <Identifier>{exception.sourceColumn}</Identifier>,
      },
      {
        id: 'sourceType',
        header: messages.wizard.tables.mappingColumns.sourceType,
        width: 220,
        textValue: (exception) => exception.sourceType,
        renderCell: (exception) => <Identifier>{exception.sourceType}</Identifier>,
      },
      {
        id: 'reason',
        header: messages.wizard.tables.mappingColumns.reason,
        width: 360,
        textValue: (exception) => messages.wizard.tables.mappingReasons[exception.reason],
        renderCell: (exception) => (
          <span>{messages.wizard.tables.mappingReasons[exception.reason]}</span>
        ),
      },
      {
        id: 'rule',
        header: messages.wizard.tables.mappingColumns.rule,
        width: 420,
        textValue: (exception) => exception.rule?.targetValue ?? '',
        renderCell: (exception) => (
          <Select
            id={`mapping-rule-${exception.sourceColumn}-${exception.action}`}
            size="sm"
            // The label names the coordinate, because the same control appears once per
            // exception and 「目标类型」 alone would not say whose.
            labelText={messages.wizard.tables.ruleLabel(exception.sourceColumn)}
            hideLabel
            disabled={recording}
            value={exception.rule?.targetValue ?? ''}
            onChange={(event) => {
              const value = event.target.value;
              if (value !== '') {
                onChoose(exception.sourceColumn, exception.action, value);
              }
            }}
          >
            {exception.rule === null ? (
              <SelectItem value="" text={messages.wizard.tables.chooseRule} />
            ) : null}
            {exception.options.map((option) => (
              <SelectItem
                key={option.targetValue}
                value={option.targetValue}
                text={`${option.targetValue} — ${messages.wizard.tables.mappingConsequences[option.consequence]}`}
              />
            ))}
          </Select>
        ),
      },
      {
        id: 'origin',
        header: messages.wizard.tables.mappingColumns.origin,
        width: 140,
        textValue: (exception) =>
          exception.rule === null ? '' : messages.wizard.tables.ruleOrigins[exception.rule.origin],
        renderCell: (exception) =>
          exception.rule === null ? null : (
            <Identifier>{messages.wizard.tables.ruleOrigins[exception.rule.origin]}</Identifier>
          ),
      },
    ],
    [onChoose, recording],
  );

  return (
    <DbxTable
      label={messages.wizard.tables.mappingListLabel}
      columns={columns}
      rows={exceptions}
      rowId={(exception) => `${exception.sourceColumn}:${exception.action}`}
      empty={{
        title: messages.wizard.tables.noExceptions.title,
        body: messages.wizard.tables.noExceptions.body,
      }}
      densityPreferenceKey="wizard-mapping-exceptions"
    />
  );
}
