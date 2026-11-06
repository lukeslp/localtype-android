package dev.localtype.keyboard

/** Verified Android state, independent of whether an enable/select button was pressed. */
data class SetupStatus(val enabled: Boolean, val selected: Boolean) {
    val ready: Boolean get() = enabled && selected
}

/** TOUR shows what the keys do not: holds, slides, the clipboard and Saved text. */
enum class SetupStep { WELCOME, ENABLE, SELECT, TRY, TOUR, CONTROLS }

/** Resumable first-use flow; no editor text or model/learning state is stored here. */
object SetupFlow {
    /** Upgrades of an already enabled keyboard skip setup; an interrupted walkthrough resumes. */
    fun shouldStart(completed: Boolean, enabled: Boolean, hasProgress: Boolean): Boolean =
        !completed && (!enabled || hasProgress)

    fun restore(value: String?): SetupStep = SetupStep.entries.firstOrNull { it.name == value } ?: SetupStep.WELCOME

    fun missing(status: SetupStatus): SetupStep = when {
        !status.enabled -> SetupStep.ENABLE
        !status.selected -> SetupStep.SELECT
        else -> SetupStep.TRY
    }

    fun reconcile(step: SetupStep, status: SetupStatus): SetupStep = when (step) {
        SetupStep.ENABLE -> if (status.enabled) missing(status) else step
        SetupStep.SELECT -> if (!status.enabled) SetupStep.ENABLE else if (status.selected) SetupStep.TRY else step
        SetupStep.TRY, SetupStep.TOUR, SetupStep.CONTROLS -> if (!status.ready) missing(status) else step
        SetupStep.WELCOME -> step
    }

    /** Null is completion, allowed only while LocalType is actually enabled and selected. */
    fun next(step: SetupStep, status: SetupStatus): SetupStep? = when (step) {
        SetupStep.WELCOME, SetupStep.ENABLE -> missing(status)
        SetupStep.SELECT -> if (status.ready) SetupStep.TRY else missing(status)
        SetupStep.TRY -> if (status.ready) SetupStep.TOUR else missing(status)
        SetupStep.TOUR -> if (status.ready) SetupStep.CONTROLS else missing(status)
        SetupStep.CONTROLS -> if (status.ready) null else missing(status)
    }

    fun previous(step: SetupStep): SetupStep? = SetupStep.entries.getOrNull(step.ordinal - 1)
}
