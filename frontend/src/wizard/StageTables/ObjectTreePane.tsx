import { useMemo, useState } from 'react';
import { Search, Tag, TreeNode, TreeView } from '@carbon/react';
import type { DraftTableConfiguration, TableObjectNode } from '@/contract';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';

/**
 * The workspace's first pane: the object tree.
 *
 * It is rooted at the 迁移范围 rather than at one table, which is what lets the stage hold
 * a single-table workspace and still let the operator move between tables without leaving
 * the screen (story 38 — 「在一个屏幕内做出判断」). Only the open table is expanded, so a
 * 1200-table 迁移范围 costs one level of names rather than a database of them.
 *
 * The tree earns its pane by saying which source objects the migration actually carries.
 * ADR-0011 puts unique constraints other than the primary key, ordinary indexes and
 * foreign keys **outside** the v1 writable-table boundary: DBX preserves them as 补建 SQL
 * and does not execute that script during migration. A tree that listed them without
 * saying so would imply a structural migration DBX does not perform.
 */
interface ObjectTreePaneProps {
  readonly tables: readonly DraftTableConfiguration[];
  readonly selectedTable: string | null;
  readonly onSelectTable: (sourceTable: string) => void;
  /** The open table's objects, or null while they are being read. */
  readonly objects: readonly TableObjectNode[] | null;
}

/**
 * How many tables the tree mounts at once.
 *
 * A 迁移范围 can hold 1200 tables and this is a tree, not a virtualised table — mounting
 * the whole database would cost a second of layout on every keystroke. The bound is stated
 * on screen with the true total beside it, so a table that is not listed is a table the
 * search has not been narrowed to, never one silently dropped from the 迁移范围.
 */
const treeWindow = 300;

const groupOrder: readonly TableObjectNode['kind'][] = [
  'COLUMN',
  'PRIMARY_KEY',
  'UNIQUE_CONSTRAINT',
  'INDEX',
  'FOREIGN_KEY',
];

export function ObjectTreePane({
  tables,
  selectedTable,
  onSelectTable,
  objects,
}: ObjectTreePaneProps) {
  const [search, setSearch] = useState('');

  const matching = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return needle === ''
      ? tables
      : tables.filter((table) => table.sourceTable.toLowerCase().includes(needle));
  }, [tables, search]);

  const visible = useMemo(() => matching.slice(0, treeWindow), [matching]);

  const names = useMemo(() => new Set(tables.map((table) => table.sourceTable)), [tables]);

  const groups = useMemo(() => {
    if (objects === null) {
      return [];
    }
    return groupOrder.flatMap((kind) => {
      const members = objects.filter((object) => object.kind === kind);
      return members.length === 0 ? [] : [{ kind, members }];
    });
  }, [objects]);

  return (
    <section className="dbx-workspace__tree" aria-label={messages.wizard.tables.treeLabel}>
      <Search
        id="wizard-tables-search"
        size="sm"
        labelText={messages.wizard.tables.treeSearchLabel}
        placeholder={messages.wizard.tables.treeSearchPlaceholder}
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        onClear={() => setSearch('')}
      />
      <TreeView
        label={messages.wizard.tables.treeLabel}
        hideLabel
        size="xs"
        selected={selectedTable === null ? [] : [selectedTable]}
        active={selectedTable ?? undefined}
        onSelect={(_event: unknown, node: { id?: unknown } | undefined) => {
          const id = node?.id;
          // Only a table opens a workspace; the object nodes below it are facts, not
          // navigation.
          if (typeof id === 'string' && names.has(id)) {
            onSelectTable(id);
          }
        }}
      >
        {visible.map((table) => (
          <TreeNode
            key={table.sourceTable}
            id={table.sourceTable}
            value={table.sourceTable}
            isExpanded={table.sourceTable === selectedTable}
            label={
              <span className="dbx-workspace__tree-label">
                <Identifier>{table.sourceTable}</Identifier>
                {/* A table can hit several conditions at once, and the tree says all of
                    them rather than the most dramatic one (story 47). */}
                {table.largeRecordTable ? (
                  <Tag type="cool-gray" size="sm">
                    {messages.wizard.tables.largeRecordTable}
                  </Tag>
                ) : null}
                {table.mappingExceptionCount > 0 ? (
                  <span className="dbx-workspace__tree-detail">
                    {messages.wizard.scope.mappingExceptions(table.mappingExceptionCount)}
                  </span>
                ) : null}
                {table.contractVersion === null ? (
                  <Tag type="magenta" size="sm">
                    {messages.wizard.tables.contractMissing.title}
                  </Tag>
                ) : null}
              </span>
            }
          >
            {table.sourceTable === selectedTable
              ? groups.map((group) => (
                  <TreeNode
                    key={group.kind}
                    id={`${table.sourceTable}:${group.kind}`}
                    value={group.kind}
                    isExpanded={group.kind === 'COLUMN'}
                    label={
                      <span className="dbx-workspace__tree-label">
                        {messages.wizard.tables.objectKinds[group.kind]}
                        {group.members.every((member) => !member.inWritableContract) ? (
                          <Tag type="warm-gray" size="sm">
                            {messages.wizard.tables.outOfContract}
                          </Tag>
                        ) : null}
                      </span>
                    }
                  >
                    {group.members.map((member) => (
                      <TreeNode
                        key={member.id}
                        id={`${table.sourceTable}:${member.id}`}
                        value={member.name}
                        label={
                          <span className="dbx-workspace__tree-label">
                            <Identifier>{member.name}</Identifier>
                            <span className="dbx-workspace__tree-detail">{member.detail}</span>
                            {member.hasMappingException ? (
                              <Tag type="blue" size="sm">
                                {messages.wizard.tables.mappingListLabel}
                              </Tag>
                            ) : null}
                          </span>
                        }
                      />
                    ))}
                  </TreeNode>
                ))
              : null}
          </TreeNode>
        ))}
      </TreeView>
      {matching.length > visible.length ? (
        <p className="dbx-wizard__fact">
          {messages.wizard.tables.treeTruncated(visible.length, matching.length)}
        </p>
      ) : null}
      <p className="dbx-wizard__fact">{messages.wizard.tables.outOfContractNotice}</p>
    </section>
  );
}
