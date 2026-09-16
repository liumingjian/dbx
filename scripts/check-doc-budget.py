#!/usr/bin/env python3
"""Enforce the character budgets of the directional documents.

An agent's context is a first-class constraint: a session that burns its window
on directional material has lost before it starts. Each sub-spec therefore
carries a character budget, and this check is what keeps it there. Budgets used
to be remembered rather than enforced, and drifted every time a decision ticket
folded its rulings back in (#100).

Root `CONTEXT.md` is deliberately not budgeted; see the note beside the budget
constants below.

Budgets are measured in **characters** (not bytes), matching `wc -m` under a
UTF-8 locale.

Run: `python3 scripts/check-doc-budget.py`   (exit 1 lists every offender)

L1 `check` depends on this script through `backend/gradle/docbudget.gradle.kts`,
so the budget rides the verification ladder of ADR-0022 and fails on the
developer's machine rather than waiting for CI.
"""
import glob
import os
import sys

# --- budgets -----------------------------------------------------------------
# One place, deliberately: changing a budget should be a one-line, reviewable edit.
# Neither figure is this script's to choose. Each is owned by a document and the
# constant here only mirrors it, so move the owning document first.

# Owned by `docs/spec/subspec-brief.md` §Budget. The brief said 12,000 until #100
# measured that the three largest modules cannot reach that without deleting
# obligations, and amended itself to 15,000. It caps restatement, not how many
# obligations a module carries.
SUBSPEC_BUDGET = 15_000

# `CONTEXT.md` carries no budget: the maintainer decided its length is not to be
# limited, so the file is deliberately unchecked rather than checked against a
# large number.
#
# NOTE for whoever reads this next: ADR-0018 §Module context still says "Root
# `CONTEXT.md` is capped at about 20K characters. The cap stands." That sentence
# and this script now disagree, and the repository's precedence rule is that the
# ADR wins — so amend ADR-0018 rather than "restoring" a cap here on the strength
# of it. Changing an ADR was out of scope for the ticket that made this change.

# Provenance and navigation, not directional material an implementing agent reads
# to build a module, so no budget applies; `docs/spec/README.md` §Provenance draws
# the same line. Exempt by intent, not by oversight. Everything else in
# docs/spec/ is a sub-spec and is checked, so a new sub-spec is covered the day it
# lands without anybody remembering to list it here.
EXEMPT = {
    # 61K of pre-compile audit, superseded by ADR-0036-0039; grep hints only.
    # Budgeting it would put the check red on day one over material nobody reads
    # end to end.
    'corpus-audit.md',
    'conflicts.md',      # the resolved-conflict record (#100); traced, not read through
    'README.md',         # navigation, not a sub-spec
    'subspec-brief.md',  # the brief that owns SUBSPEC_BUDGET, not a sub-spec
}


def char_count(path):
    with open(path, encoding='utf-8') as handle:
        return len(handle.read())


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)

    checks = []
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
