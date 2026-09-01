import type { ReactNode } from 'react';

/**
 * A database or record identifier — a table name, a run id, a row count.
 *
 * Rendering these through one component keeps them on the Latin-first branch of the family
 * stack (ADR-0014), which is what stops `order_id` and `orderid` looking alike.
 */
export function Identifier({ children }: { children: ReactNode }) {
  return <span className="dbx-identifier">{children}</span>;
}
