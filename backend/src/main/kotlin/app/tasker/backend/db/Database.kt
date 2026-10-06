package app.tasker.backend.db

import app.tasker.backend.DatabaseSettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import org.slf4j.LoggerFactory

/** Plain JDBC access (no ORM). Calls block: run them on Dispatchers.IO. */
class Database(private val dataSource: DataSource) {

    /** Runs [block] in one transaction: committed when it returns, rolled back when it throws. */
    fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (e: SQLException) {
            rollback(connection, e)
            throw e
        } catch (e: RuntimeException) {
            rollback(connection, e)
            throw e
        }
    }

    /** Health probe: true when a connection can be borrowed and answers a trivial query. */
    fun isReachable(): Boolean = try {
        transaction { connection -> connection.queryOne("SELECT 1") { it.getInt(1) } } == 1
    } catch (e: SQLException) {
        log.warn("Database unreachable: {} (SQLSTATE {})", e.javaClass.simpleName, e.sqlState)
        false
    }

    private fun rollback(connection: Connection, cause: Exception) {
        try {
            connection.rollback()
        } catch (e: SQLException) {
            cause.addSuppressed(e)
        }
    }

    companion object {
        /** SQLSTATE of a unique-key violation in both PostgreSQL and H2. */
        const val UNIQUE_VIOLATION = "23505"

        fun pooled(settings: DatabaseSettings): HikariDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                settings.user?.let { username = it }
                settings.password?.let { password = it }
                maximumPoolSize = settings.maxPoolSize
                poolName = "tasker-backend"
                connectionTimeout = CONNECTION_TIMEOUT_MS
            },
        )

        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private val log = LoggerFactory.getLogger(Database::class.java)
    }
}

internal fun Connection.update(sql: String, vararg params: Any?): Int = prepareStatement(sql).use { statement ->
    statement.bind(params)
    statement.executeUpdate()
}

internal fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeQuery().use { rows -> if (rows.next()) map(rows) else null }
    }

internal fun <T> Connection.queryList(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(map(rows)) }
        }
    }

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { index, value -> setObject(index + 1, value) }
}
