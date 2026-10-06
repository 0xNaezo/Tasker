package app.tasker.backend.db

import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Versioned SQL migrations applied at startup. Each migration runs in its own transaction together with
 * its `schema_version` row, so a concurrent start of a second instance either sees it applied or loses
 * the race on the primary key, rolls back and moves on. The SQL is portable between PostgreSQL and H2.
 */
class Migrations(
    private val database: Database,
    private val migrations: List<Migration> = BUILT_IN,
) {
    data class Migration(val version: Int, val description: String, val resource: String)

    /** Applies pending migrations in version order; returns the versions applied by this call. */
    fun migrate(clock: Clock): List<Int> {
        database.transaction { it.update(CREATE_VERSION_TABLE) }
        val applied = mutableListOf<Int>()
        for (migration in migrations.sortedBy { it.version }) {
            if (migration.version in appliedVersions()) continue
            if (apply(migration, clock)) applied += migration.version
        }
        return applied
    }

    fun appliedVersions(): Set<Int> = database.transaction { connection ->
        connection.queryList("SELECT version FROM schema_version") { it.getInt(1) }.toSet()
    }

    private fun apply(migration: Migration, clock: Clock): Boolean = try {
        database.transaction { connection ->
            connection.update(
                "INSERT INTO schema_version (version, description, applied_at) VALUES (?, ?, ?)",
                migration.version,
                migration.description,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            )
            statements(migration).forEach { sql -> connection.update(sql) }
        }
        log.info("Applied migration V{} {}", migration.version, migration.description)
        true
    } catch (e: SQLException) {
        if (e.sqlState != Database.UNIQUE_VIOLATION || migration.version !in appliedVersions()) throw e
        log.info("Migration V{} was applied concurrently by another instance", migration.version)
        false
    }

    private fun statements(migration: Migration): List<String> {
        val sql = checkNotNull(javaClass.classLoader.getResourceAsStream(migration.resource)) {
            "Missing migration resource ${migration.resource}"
        }.use { it.readBytes().decodeToString() }
        return sql.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    companion object {
        private val log = LoggerFactory.getLogger(Migrations::class.java)

        val BUILT_IN: List<Migration> = listOf(
            Migration(1, "installs, usage, budget and weekly metrics", "db/migration/V1__init.sql"),
        )

        private const val CREATE_VERSION_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY,
                description VARCHAR(200) NOT NULL,
                applied_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
        """
    }
}
