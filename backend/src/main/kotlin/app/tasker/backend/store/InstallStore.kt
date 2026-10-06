package app.tasker.backend.store

import app.tasker.backend.db.Database
import app.tasker.backend.db.queryOne
import app.tasker.backend.db.update
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

enum class InstallState { ACTIVE, BLOCKED }

/** How an install proved itself: Play Integrity in production, the shared test key in the dev environment. */
enum class Verification(val dbValue: String) { PLAY("play"), DEV("dev") }

class InstallStore(private val database: Database) {

    /** Creates the install or refreshes its verification time; returns its state (an operator may block it). */
    fun upsertVerified(installId: String, verification: Verification, at: Instant): InstallState = database.transaction { connection ->
        val time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
        if (refresh(connection, installId, verification, time) == 0) {
            val inserted = connection.update(
                "INSERT INTO installs (install_id, created_at, last_verified_at, verification) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT DO NOTHING",
                installId,
                time,
                time,
                verification.dbValue,
            )
            if (inserted == 0) refresh(connection, installId, verification, time)
        }
        checkNotNull(state(connection, installId))
    }

    fun state(installId: String): InstallState? = database.transaction { state(it, installId) }

    private fun refresh(connection: Connection, installId: String, verification: Verification, time: OffsetDateTime): Int =
        connection.update(
            "UPDATE installs SET last_verified_at = ?, verification = ? WHERE install_id = ?",
            time,
            verification.dbValue,
            installId,
        )

    companion object {
        internal fun state(connection: Connection, installId: String): InstallState? =
            connection.queryOne("SELECT blocked FROM installs WHERE install_id = ?", installId) { row ->
                if (row.getBoolean(1)) InstallState.BLOCKED else InstallState.ACTIVE
            }
    }
}
