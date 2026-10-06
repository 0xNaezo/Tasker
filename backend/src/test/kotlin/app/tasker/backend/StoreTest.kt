package app.tasker.backend

import app.tasker.backend.db.Database
import app.tasker.backend.db.Migrations
import app.tasker.backend.db.queryList
import app.tasker.backend.db.update
import app.tasker.backend.store.CallOutcome
import app.tasker.backend.store.InstallState
import app.tasker.backend.store.InstallStore
import app.tasker.backend.store.Reservation
import app.tasker.backend.store.UsageStore
import app.tasker.backend.store.Verification
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.logging.Logger
import javax.sql.DataSource
import org.junit.Test

class StoreTest {

    private val clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)
    private val database = h2Database(clock)
    private val installs = InstallStore(database)
    private val usage = UsageStore(database)
    private val day = LocalDate.parse("2026-10-06")

    @Test
    fun `migrations are applied once and recorded`() {
        val migrations = Migrations(database)
        assertThat(migrations.appliedVersions()).containsExactly(1)
        assertThat(migrations.migrate(clock)).isEmpty()

        val tables = database.transaction { connection ->
            connection.queryList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'") { it.getString(1) }
        }
        assertThat(tables).containsAtLeast("schema_version", "installs", "daily_usage", "global_daily_cost", "metrics_weekly")
    }

    @Test
    fun `a fresh database gets every built-in migration`() {
        val fresh = Database(
            org.h2.jdbcx.JdbcDataSource().apply {
                setURL("jdbc:h2:mem:fresh-${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
            },
        )
        assertThat(Migrations(fresh).migrate(clock)).containsExactlyElementsIn(Migrations.BUILT_IN.map { it.version })
    }

    @Test
    fun `reservation checks the install, the budget and the daily limit`() {
        assertThat(usage.reserve(INSTALL_ID, day, dailyLimit = 2, budgetMicroUsd = 100)).isEqualTo(Reservation.UNKNOWN_INSTALL)

        assertThat(installs.upsertVerified(INSTALL_ID, Verification.PLAY, TEST_NOW)).isEqualTo(InstallState.ACTIVE)
        assertThat(usage.reserve(INSTALL_ID, day, 2, 100)).isEqualTo(Reservation.GRANTED)
        assertThat(usage.reserve(INSTALL_ID, day, 2, 100)).isEqualTo(Reservation.GRANTED)
        assertThat(usage.reserve(INSTALL_ID, day, 2, 100)).isEqualTo(Reservation.DAILY_LIMIT)
        assertThat(usage.reserve(INSTALL_ID, day.plusDays(1), 2, 100)).isEqualTo(Reservation.GRANTED)

        usage.record(INSTALL_ID, day, CallOutcome.SUCCEEDED, OPUS_USAGE, costMicroUsd = 100, latencyMs = 5, budgetMicroUsd = 100)
        assertThat(usage.reserve(INSTALL_ID, day, 10, 100)).isEqualTo(Reservation.BUDGET_EXHAUSTED)

        database.transaction { it.update("UPDATE installs SET blocked = TRUE WHERE install_id = ?", INSTALL_ID) }
        assertThat(usage.reserve(INSTALL_ID, day.plusDays(1), 10, 100)).isEqualTo(Reservation.BLOCKED)
        assertThat(installs.upsertVerified(INSTALL_ID, Verification.PLAY, TEST_NOW)).isEqualTo(InstallState.BLOCKED)
    }

    @Test
    fun `usage accumulates per install and day and budget alerts fire once`() {
        installs.upsertVerified(INSTALL_ID, Verification.DEV, TEST_NOW)
        repeat(3) { usage.reserve(INSTALL_ID, day, 10, 1_000) }

        val first = usage.record(INSTALL_ID, day, CallOutcome.SUCCEEDED, OPUS_USAGE, 500, latencyMs = 40, budgetMicroUsd = 1_000)
        val second = usage.record(INSTALL_ID, day, CallOutcome.REFUSED, null, 300, latencyMs = 90, budgetMicroUsd = 1_000)
        val third = usage.record(INSTALL_ID, day, CallOutcome.FAILED, null, 300, latencyMs = 10, budgetMicroUsd = 1_000)

        assertThat(first.reachedWarning || first.exhausted).isFalse()
        assertThat(second.reachedWarning).isTrue()
        assertThat(second.exhausted).isFalse()
        assertThat(third.reachedWarning).isFalse()
        assertThat(third.exhausted).isTrue()
        assertThat(third.spentMicroUsd).isEqualTo(1_100)

        val daily = checkNotNull(usage.dailyUsage(INSTALL_ID, day))
        assertThat(listOf(daily.requests, daily.succeeded, daily.refused, daily.failed)).containsExactly(3, 1, 1, 1).inOrder()
        assertThat(daily.inputTokens).isEqualTo(OPUS_USAGE.inputTokens)
        assertThat(daily.latencyMsTotal).isEqualTo(140)
        assertThat(daily.latencyMsMax).isEqualTo(90)
        assertThat(usage.globalCost(day)).isEqualTo(1_100)
        assertThat(usage.globalCost(day.plusDays(1))).isEqualTo(0)
    }

    @Test
    fun `no task text column exists anywhere`() {
        val columns = database.transaction { connection ->
            connection.queryList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'",
            ) { it.getString(1) }
        }
        assertThat(columns.filter { it.contains("text") || it.contains("title") || it.contains("note") }).isEmpty()
    }

    @Test
    fun `unreachable database is reported, not thrown`() {
        val broken = Database(FailingDataSource())
        assertThat(broken.isReachable()).isFalse()
        assertThat(database.isReachable()).isTrue()
    }

    private class FailingDataSource : DataSource {
        override fun getConnection(): Connection = throw SQLException("connection refused", "08001")

        override fun getConnection(username: String?, password: String?): Connection = getConnection()

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) = Unit

        override fun setLoginTimeout(seconds: Int) = Unit

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = Logger.getGlobal()

        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")

        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
