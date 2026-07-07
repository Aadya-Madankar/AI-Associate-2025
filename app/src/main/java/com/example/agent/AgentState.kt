package com.example.agent

/**
 * Per-step state machine for the autonomous agent loop. The coordinator advances
 * through these states once per planned action; [AgentStepRecord] captures the
 * outcome of each step so a completed run can be replayed or saved as a skill.
 *
 * Pure data — no Android dependencies — so it can be unit-tested and shared freely.
 */
enum class AgentState {
    /** No task in progress; the loop is waiting for work. */
    IDLE,

    /** The model is deciding the next action for the current step. */
    PLANNING,

    /** Capturing the current screen / device state before acting. */
    OBSERVING,

    /** Inspecting the planned action to decide gating (e.g. is confirmation needed?). */
    CLASSIFYING,

    /** Action is risky/sensitive and the loop is paused for user confirmation. */
    AWAITING_CONFIRM,

    /** Running the chosen tool/action. */
    EXECUTING,

    /** Capturing the resulting screen / state after the action ran. */
    OBSERVING_RESULT,

    /** The step finished and its outcome was recorded; loop may continue. */
    STEP_DONE,

    /** Cannot proceed due to an external precondition (e.g. permission, lock screen). */
    BLOCKED,

    /** No forward progress is being made (loop / repetition detected). */
    STUCK,

    /** The step or task failed and cannot recover. */
    FAILED,

    /** The whole task finished successfully. */
    COMPLETE,

    /** The task was cancelled by the user or caller. */
    CANCELLED
}

/**
 * Immutable record of a single agent step. The coordinator appends one of these per
 * step to build a step history; a successful sequence can be promoted into a reusable
 * skill.
 *
 * @param index zero-based position of this step within the run.
 * @param tool name of the tool/action invoked for this step.
 * @param args arguments passed to the tool (empty when none).
 * @param state terminal [AgentState] reached for this step.
 * @param ok whether the step succeeded.
 */
data class AgentStepRecord(
    val index: Int,
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val state: AgentState,
    val ok: Boolean
)
