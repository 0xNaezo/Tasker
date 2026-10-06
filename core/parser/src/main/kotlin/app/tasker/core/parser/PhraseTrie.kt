package app.tasker.core.parser

/** Read access to token keys for phrase matching. */
internal interface TokenKeys {
    /** Key of token [index] if it may be part of an expression, otherwise `null`. */
    fun keyAt(index: Int): String?

    /** Whether token [index] may continue an expression that already contains token `index - 1`. */
    fun continues(index: Int): Boolean
}

internal class PhraseMatch<V>(val length: Int, val value: V)

/**
 * Word-level prefix tree: phrases of one or more words mapped to values, matched against tokens by their comparison keys
 * (tech plan §9.1: dictionaries on prefix trees instead of regular expressions).
 */
internal class PhraseTrie<V : Any> {
    private class Node<V : Any> {
        var children: HashMap<String, Node<V>>? = null
        var value: V? = null
    }

    private val root = Node<V>()

    val isEmpty: Boolean get() = root.children == null

    /** Adds [phrase]: words separated by spaces, written naturally ("на этой неделе", "п'ятницю"). */
    fun put(phrase: String, value: V) {
        var node = root
        for (word in phrase.split(' ')) {
            if (word.isEmpty()) continue
            val children = node.children ?: HashMap<String, Node<V>>().also { node.children = it }
            node = children.getOrPut(Keys.of(word)) { Node() }
        }
        node.value = value
    }

    /** The longest phrase that starts at token [start]. */
    fun match(start: Int, keys: TokenKeys): PhraseMatch<V>? {
        var node = root
        var best: PhraseMatch<V>? = null
        var index = start
        while (true) {
            if (index > start && !keys.continues(index)) break
            val key = keys.keyAt(index) ?: break
            node = node.children?.get(key) ?: break
            index++
            node.value?.let { best = PhraseMatch(index - start, it) }
        }
        return best
    }

    companion object {
        fun <V : Any> of(entries: Map<String, V>): PhraseTrie<V> = PhraseTrie<V>().apply { entries.forEach { (k, v) -> put(k, v) } }
    }
}
