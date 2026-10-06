package app.tasker.backend.store

import app.tasker.backend.db.Database
import app.tasker.backend.db.queryOne
import app.tasker.backend.db.update
import app.tasker.core.ai.contract.AiJson
import app.tasker.core.ai.contract.MetricsReport
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Weekly telemetry aggregates (tech plan §23): one row per install and ISO week, replaced on resend. */
class MetricsStore(private val database: Database) {

    fun upsert(report: MetricsReport, receivedAt: Instant) {
        val week = LocalDate.parse(report.weekStart)
        val counters = AiJson.wire.encodeToString(COUNTERS, report.counters.toSortedMap())
        val values = AiJson.wire.encodeToString(VALUES, report.values.toSortedMap())
        val time = OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC)
        database.transaction { connection ->
            if (replace(connection, report.installId, week, counters, values, time) == 0) {
                val inserted = connection.update(
                    "INSERT INTO metrics_weekly (install_id, week_start, counters_json, values_json, received_at) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT DO NOTHING",
                    report.installId,
                    week,
                    counters,
                    values,
                    time,
                )
                if (inserted == 0) replace(connection, report.installId, week, counters, values, time)
            }
        }
    }

    fun find(installId: String, weekStart: LocalDate): MetricsReport? = database.transaction { connection ->
        connection.queryOne(
            "SELECT counters_json, values_json FROM metrics_weekly WHERE install_id = ? AND week_start = ?",
            installId,
            weekStart,
        ) { row ->
            MetricsReport(
                installId = installId,
                weekStart = weekStart.toString(),
                counters = AiJson.wire.decodeFromString(COUNTERS, row.getString(1)),
                values = AiJson.wire.decodeFromString(VALUES, row.getString(2)),
            )
        }
    }

    private fun replace(
        connection: Connection,
        installId: String,
        week: LocalDate,
        counters: String,
        values: String,
        time: OffsetDateTime,
    ): Int = connection.update(
        "UPDATE metrics_weekly SET counters_json = ?, values_json = ?, received_at = ? WHERE install_id = ? AND week_start = ?",
        counters,
        values,
        time,
        installId,
        week,
    )

    private companion object {
        val COUNTERS = MapSerializer(String.serializer(), Long.serializer())
        val VALUES = MapSerializer(String.serializer(), Double.serializer())
    }
}
