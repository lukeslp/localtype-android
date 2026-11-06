package dev.localtype.keyboard

/**
 * Limits automatic model work. Tapped suggestions and proofreading are always allowed; automatic
 * generation and quiet lookahead back off on low power, heat, or a busy minute.
 */
class ModelBudget(private val clock: () -> Long = System::currentTimeMillis) {
    data class PowerState(val powerSave: Boolean, val batteryPercent: Int, val charging: Boolean, val thermalSevere: Boolean)

    private val recent = ArrayDeque<Long>()

    /** Why automatic work is paused, or null when it may run. */
    fun blockReason(power: PowerState, lookahead: Boolean): String? {
        trim()
        return when {
            power.thermalSevere -> "thermal"
            power.powerSave -> "power saver"
            !power.charging && power.batteryPercent in 0 until LOW_BATTERY_PERCENT -> "low battery"
            recent.size >= MAX_PER_MINUTE -> "per-minute cap"
            lookahead && recent.size >= MAX_LOOKAHEAD_PER_MINUTE -> "lookahead cap"
            else -> null
        }
    }

    fun record() {
        recent.addLast(clock())
        trim()
    }

    private fun trim() {
        val cutoff = clock() - WINDOW_MS
        while (recent.isNotEmpty() && recent.first() < cutoff) recent.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_PER_MINUTE = 10
        const val MAX_LOOKAHEAD_PER_MINUTE = 6
        const val LOW_BATTERY_PERCENT = 20
    }
}
