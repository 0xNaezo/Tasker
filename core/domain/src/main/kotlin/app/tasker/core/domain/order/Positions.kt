package app.tasker.core.domain.order

/**
 * Manual order inside a bucket (DAT-5, tech plan §7.2): positions with a 2^16 step, midpoint insertion and
 * local rebalancing when there is no room left between neighbours.
 */
object Positions {
    const val STEP: Long = 1L shl 16

    fun afterLast(last: Long?): Long = (last ?: 0L) + STEP

    fun beforeFirst(first: Long?): Long = (first ?: 0L) - STEP

    /** Position strictly between [previous] and [next], or null if they are adjacent and a rebalance is needed. */
    fun between(previous: Long?, next: Long?): Long? = when {
        previous == null && next == null -> STEP
        previous == null -> next!! - STEP
        next == null -> previous + STEP
        next - previous < 2 -> null
        else -> previous + (next - previous) / 2
    }

    /** Evenly spaced positions for [count] items in their current order. */
    fun rebalanced(count: Int): List<Long> = List(count) { (it + 1) * STEP }

    /**
     * Moves the item at [from] to index [to] in [positions] (current order). Returns the new position of the moved
     * item, or null if the list must be rebalanced first.
     */
    fun positionForMove(positions: List<Long>, from: Int, to: Int): Long? {
        require(from in positions.indices && to in positions.indices) { "Index out of bounds" }
        if (from == to) return positions[from]
        val others = positions.toMutableList().apply { removeAt(from) }
        val previous = others.getOrNull(to - 1)
        val next = others.getOrNull(to)
        return between(previous, next)
    }
}
