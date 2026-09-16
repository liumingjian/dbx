#!/usr/bin/env python3
"""Enforce the character budgets of the directional documents.

An agent's context is a first-class constraint: a session that burns its window
on directional material has lost before it starts. `CONTEXT.md` and each
sub-spec therefore carry a character budget, and this check is what keeps them
there. Budgets used to be remembered rather than enforced, and drifted every
time a decision ticket folded its rulings back in (#100).

Budgets are measured in **characters** (not bytes), matching `wc -m` under a
UTF-8 locale.

Run: `python3 scripts/check-doc-budget.py`   (exit 1 lists every offender)

When the backend Gradle build lands, L1 `check` should depend on this script so
the budget rides the verification ladder of ADR-0022 rather than CI alone.
"""
import glob
import os
import sys

# --- budgets -----------------------------------------------------------------
# One place, deliberately: changing a budget should be a one-line, reviewable edit.

SUBSPEC_BUDGET = 15_000
CONTEXT_BUDGET = 24_000

# Provenance and navigation, not directional material an implementing agent
# reads to build a module. Exempt by intent, not by oversight.
EXEMPT = {
    'corpus-audit.md',   # predates ADR-0036-0039; grep hints only
    'conflicts.md',      # the sub-specs' resolved-conflict record (#100)
    'README.md',         # navigation
    'subspec-brief.md',  # the brief itself
}


def char_count(path):
    with open(path, encoding='utf-8') as handle:
        return len(handle.read())


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)

    checks = [('CONTEXT.md', CONTEXT_BUDGET)]
    for path in sorted(glob.glob('docs/spec/*.md')):
        if os.path.basename(path) not in EXEMPT:
            checks.append((path, SUBSPEC_BUDGET))

    offenders = []
    for path, budget in checks:
        count = char_count(path)
        status = 'ok'
        if count > budget:
            offenders.append((path, count, budget))
            status = f'OVER by {count - budget:,}'
        print(f'{path:<34} {count:6,} / {budget:,}  {status}')

    if offenders:
        print('\nOver budget:', file=sys.stderr)
        for path, count, budget in offenders:
            print(f'  {path}: {count:,} > {budget:,} '
                  f'(over by {count - budget:,})', file=sys.stderr)
        print('\nPush detail behind pointers rather than inline; rationale belongs '
              'in an ADR, not in the spec (docs/spec/subspec-brief.md §Budget).',
              file=sys.stderr)
        return 1

    print(f'\nAll {len(checks)} documents within budget.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
