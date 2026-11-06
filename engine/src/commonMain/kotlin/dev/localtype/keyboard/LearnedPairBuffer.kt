package dev.localtype.keyboard

/**
 * The learned pairs the main thread sees while the journal's worker loads or writes them. Mutations
 * take effect before they are queued, so rebuilding a model never waits for an append to finish.
 * Like the n-gram model, this buffer is main-thread only; the worker receives immutable mutations.
 */
class LearnedPairBuffer(private val write: (Mutation) -> Unit) : AdaptiveNgramModel.LearningStore {
    private var state = LearnedPairState(emptyList())
    private val pending = ArrayList<Mutation>()

    var ready = false
        private set

    override fun entries(): List<Triple<String, String, Int>> = state.entries()

    override fun record(previous: String, next: String) = mutate(Mutation.Increment(previous, next))

    fun remove(previous: String, next: String) = mutate(Mutation.Remove(previous, next))

    override fun clear() = mutate(Mutation.Clear)

    private fun mutate(mutation: Mutation) {
        apply(state, mutation)
        if (!ready) pending += mutation
        write(mutation)
    }

    /**
     * Publishes the worker's initial load once, retaining every mutation made while it ran. The
     * returned baseline is for the existing live model: its pending increments already taught that
     * model, so only the saved pairs are added, after applying pending forgets to them.
     */
    fun publish(loadedEntries: List<Triple<String, String, Int>>): List<Triple<String, String, Int>> {
        return publish(LearnedPairState(loadedEntries), LearnedPairState(loadedEntries))
    }

    /**
     * Takes ownership of two distinct states prepared by the worker. It must not touch them again
     * after posting this publication; all further mutations belong to the main thread. Preparing
     * both copies off-thread keeps initial state construction out of the publication callback.
     */
    fun publish(loaded: LearnedPairState, baseline: LearnedPairState): List<Triple<String, String, Int>> {
        check(!ready) { "learned pairs already published" }
        require(loaded !== baseline) { "loaded state and baseline must be distinct" }
        pending.forEach { mutation ->
            apply(loaded, mutation)
            if (mutation !is Mutation.Increment) apply(baseline, mutation)
        }
        state = loaded
        pending.clear()
        ready = true
        return baseline.entries()
    }

    sealed interface Mutation {
        data class Increment(val previous: String, val next: String) : Mutation
        data class Remove(val previous: String, val next: String) : Mutation
        data object Clear : Mutation
    }

    private fun apply(target: LearnedPairState, mutation: Mutation) {
        when (mutation) {
            is Mutation.Increment -> target.record(mutation.previous, mutation.next)
            is Mutation.Remove -> target.remove(mutation.previous, mutation.next)
            Mutation.Clear -> target.clear()
        }
    }
}
