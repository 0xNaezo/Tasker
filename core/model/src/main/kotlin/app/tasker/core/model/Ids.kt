package app.tasker.core.model

import java.security.SecureRandom
import java.util.UUID
import kotlin.random.Random
import kotlin.random.asKotlinRandom

typealias TaskId = String
typealias ProjectId = String
typealias TagId = String
typealias BatchId = String

/**
 * UUID v7 (RFC 9562): 48-bit Unix millisecond timestamp followed by random bits.
 * Identifiers sort by creation time, which keeps indexes compact and is ready for future sync (tech plan §7.1).
 */
object UuidV7 {
    private val defaultRandom: Random = SecureRandom().asKotlinRandom()

    fun generate(epochMillis: Long, random: Random = defaultRandom): String {
        require(epochMillis >= 0) { "Timestamp must not be negative" }
        val randA = random.nextInt(1 shl 12).toLong()
        val randB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL
        val msb = (epochMillis and 0xFFFF_FFFF_FFFFL shl 16) or (0x7L shl 12) or randA
        val lsb = randB or Long.MIN_VALUE // variant bits 10xx
        return UUID(msb, lsb).toString()
    }

    fun timestampOf(id: String): Long = UUID.fromString(id).mostSignificantBits ushr 16
}
