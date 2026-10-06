package app.tasker.core.data.command

/** A field in an edit: either left as it is or set to a new value (which may be null, i.e. cleared). */
sealed interface FieldUpdate<out T> {
    data object Keep : FieldUpdate<Nothing>

    data class Set<out T>(val value: T) : FieldUpdate<T>

    companion object {
        fun <T> of(value: T): FieldUpdate<T> = Set(value)
    }
}

fun <T> FieldUpdate<T>.orElse(current: T): T = when (this) {
    is FieldUpdate.Set -> value
    FieldUpdate.Keep -> current
}

val FieldUpdate<*>.isSet: Boolean get() = this is FieldUpdate.Set
