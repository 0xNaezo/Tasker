package app.tasker.core.parser

/** Structural guarantees of [ParseResult]; an empty list means the result is well-formed. */
object ParseInvariants {
    fun problems(result: ParseResult): List<String> {
        val problems = ArrayList<String>()
        val input = result.input
        val ranges = ArrayList<Pair<IntRange, Int>>()
        result.fields.forEachIndexed { index, field ->
            if (field.ranges.isEmpty()) problems += "field without ranges: $field"
            if (field.ranges.zipWithNext().any { (a, b) -> b.first <= a.last }) problems += "unordered fragments: $field"
            for (range in field.ranges) {
                if (range.isEmpty() || range.first < 0 || range.last >= input.length) problems += "range $range outside the input"
                ranges += range to index
            }
        }
        for (i in ranges.indices) {
            for (j in i + 1 until ranges.size) {
                val (a, fieldA) = ranges[i]
                val (b, fieldB) = ranges[j]
                if (fieldA != fieldB && a.overlaps(b)) problems += "fields overlap: $a and $b"
            }
        }
        val starts = result.fields.mapNotNull { it.ranges.firstOrNull()?.first }
        if (starts != starts.sorted()) problems += "fields are not ordered by their first range"
        for (link in result.links) {
            if (link.range.isEmpty() || link.range.first < 0 || link.range.last >= input.length) problems += "link outside the input"
            if (ranges.any { (r, _) -> r.overlaps(link.range) }) problems += "link overlaps a field: $link"
        }
        val singles = result.fields.groupBy { it.value::class }.filterKeys { it != ParsedValue.Tag::class }
        singles.filterValues { it.size > 1 }.keys.forEach { problems += "more than one ${it.simpleName}" }
        if (input.isNotBlank() && result.title.isBlank()) problems += "blank title for a non-blank input"
        return problems
    }

    fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}
