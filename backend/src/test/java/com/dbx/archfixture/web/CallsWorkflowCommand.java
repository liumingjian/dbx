package com.dbx.archfixture.web;

import com.dbx.archfixture.workflow.api.command.WorkflowCommand;

/** A caller that is not orchestration writing through the command side: rule 4's violation. */
public final class CallsWorkflowCommand {

    public void write(WorkflowCommand command) {
        command.submit();
    }
}
