/**
 * Making a 迁移运行 immutable in fact rather than in prose.
 *
 * `CONTEXT.md` defines a 迁移运行 as 「one **immutable** execution attempt over all or part
 * of a migration task」 and lists 「retry in place」 and 「resumed run」 under its `_Avoid_`.
 * The scope captured when the operator confirms is the hinge the whole audit chain hangs
 * from: every later statement — progress, 校验执行, 校验处置, a rerun's narrower scope —
 * is a statement about *that* recorded execution. If it could be edited afterwards, the
 * evidence would describe a run that never happened.
 *
 * TypeScript's `readonly` says so at compile time and nothing at run time. `deepFreeze`
 * makes the store say it at run time too: a write to a frozen object throws in strict
 * mode, which every ES module is. It is applied at the moment a run is recorded, so there
 * is no window in which a run exists and is still writable.
 */

/**
 * Recursively freezes a value and everything reachable from it.
 *
 * Arrays and plain objects only — the records this is used on are contract data, which is
 * JSON by construction. Cycles cannot occur in them, and `Object.isFrozen` guards against
 * one anyway.
 */
export function deepFreeze<T>(value: T): T {
  if (value === null || typeof value !== 'object' || Object.isFrozen(value)) {
    return value;
  }
  Object.freeze(value);
  for (const entry of Object.values(value as Record<string, unknown>)) {
    deepFreeze(entry);
  }
  return value;
}
