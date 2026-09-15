# Sub-spec brief (shared by every sub-spec writer)

A sub-spec is the one document an implementing agent reads to build one module in one 100K-token session, alongside the ADRs it points at. Implementation tickets are generated from it, one per slice.

## Rules

- **Compile, don't restate.** ADRs and `CONTEXT.md` are the single source of truth for rationale and term definitions. The sub-spec states *what the module must do* as checkable obligations, each ending with its source pointer, e.g. `(ADR-0031 §Admission)`. Never copy rationale.
- **Later wins.** Where documents disagree, follow the audit's verdict ([`corpus-audit.md`](corpus-audit.md), sections 3–4, as updated by the rulings of the tickets that blocked #82). Record every conflict you meet in the sub-spec's "Conflicts resolved" section with the winning pointer. Treat `docs/technical-plan.md` as stale wherever a later ADR speaks.
- **Vocabulary**: use `CONTEXT.md` terms exactly; English prose, domain terms in Chinese glossed `English (原文)` on first use.
- **Budget**: at most 12,000 characters. Push detail behind pointers rather than inline.
- **Checkable**: every obligation must be verifiable by a named test at a named rung of the verification ladder (ADR-0022: L1 `check`, L2 `seamTest`, L3 `e2eTest`, L4 `packageTest`).
- Invent nothing. If the corpus leaves something undecided, list it under "Open items" and do not decide it.

## Shape

```markdown
# <module> — v1 sub-spec

<one-line responsibility>

**Read first**: <ADRs, in reading order>; CONTEXT.md terms: <list>

## Interface (`<module>.api`)
<entry points: name, input, output, purity; one line each>

## Consumes
<other modules' api entry points this module calls; "none" is valid>

## Obligations
<numbered, grouped by concern; each one line, checkable, with source pointer>

## Verification
<per obligation group: rung + test name (`*ContractTest`, golden files, seam tests)>

## Slices
<ordered, independently mergeable implementation slices, each sized to one session, with blocking edges between them; these become tickets>

## Conflicts resolved
<losing text → winning text, pointers>

## Open items
<undecided in the corpus; empty is fine>
```
