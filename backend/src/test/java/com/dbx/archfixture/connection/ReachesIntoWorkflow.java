package com.dbx.archfixture.connection;

import com.dbx.archfixture.workflow.api.command.WorkflowCommand;

/**
 * The leaf rule's violation: {@code connection} naming another module.
 *
 * <p>{@code workflow} is the module that matters. It owns H2, the append-only ledger and
 * {@code backups/}; if {@code connection} could call it, the wrapped backup key could end up in the
 * ledger and ADR-0006's erasure would be a promise DBX cannot keep. So the edge stays one-way, and this
 * fixture is the proof that the rule bites rather than passing over an empty module.
 */
public final class ReachesIntoWorkflow {

    private final WorkflowCommand command = null;
}
