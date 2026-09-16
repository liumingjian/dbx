import org.gradle.language.base.plugins.LifecycleBasePlugin

// --- #107: the document budget rides L1 ---------------------------------------------------------
//
// The sub-specs carry character budgets because an agent's context window is a first-class
// constraint. Those budgets used to be remembered, and drifted every time a decision
// ticket folded its rulings back in (#100). CI already ran the check, but CI is the wrong place for
// it alone: an agent that has to open a pull request to learn it blew the budget has already spent
// the session. Hanging it off L1 (ADR-0022) makes it fail where the writing happens.
//
// The check is `scripts/check-doc-budget.py` — one implementation, shared with
// `.github/workflows/docs-budget.yml`. The script owns what is budgeted and what is exempt; this
// file only decides when it runs. The Gradle project root is `backend/`, so the repo root is its
// parent and the script is located from there rather than from a working directory.

val repoRoot = rootDir.parentFile
val docBudgetScript = File(repoRoot, "scripts/check-doc-budget.py")

val checkDocBudget = tasks.register<Exec>("checkDocBudget") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the sub-specs against their character budgets (#107)."

    workingDir = repoRoot
    // `python3` by name: the check is plain stdlib and the repo pins no interpreter, so asking for
    // an absolute path would only break on whichever machine installed Python somewhere else.
    commandLine("python3", docBudgetScript.absolutePath)

    // The budgets are checked against the real documents, never a fixture: a fixture would prove
    // the script runs, not that the corpus is within budget. The task produces nothing — its exit
    // code is the whole result — so it runs every time, which costs milliseconds.
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(checkDocBudget)
}
