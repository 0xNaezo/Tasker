package app.tasker.backend

import app.tasker.core.ai.contract.AiRoute
import app.tasker.core.ai.contract.RouteSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendConfigTest {

    private val minimal = mapOf(
        "DATABASE_URL" to "postgres://tasker:p%40ss%3Aword@db.internal:6543/tasker?sslmode=require",
        "OPENROUTER_API_KEY" to "sk-or-v1-test-key",
        "INSTALL_TOKEN_SECRET" to TEST_SECRET,
        "PLAY_PACKAGE_NAME" to "app.tasker",
    )

    @Test
    fun `minimal production environment uses the plan defaults`() {
        val config = BackendConfig.fromEnv(minimal)

        assertThat(config.port).isEqualTo(8080)
        assertThat(config.database.jdbcUrl).isEqualTo("jdbc:postgresql://db.internal:6543/tasker?sslmode=require")
        assertThat(config.database.user).isEqualTo("tasker")
        assertThat(config.database.password).isEqualTo("p@ss:word")
        assertThat(config.dailyRequestsPerInstall).isEqualTo(200)
        assertThat(config.dailyBudgetMicroUsd).isEqualTo(20_000_000)
        assertThat(config.devInstallKey).isNull()
        assertThat(config.openRouterBaseUrl).isEqualTo("https://openrouter.ai/api/v1")
        assertThat(
            config.routeSettings(AiRoute.ENRICH),
        ).isEqualTo(RouteSettings(model = "anthropic/claude-opus-5.5", effort = "low", maxTokens = 4_096, zeroDataRetention = true))
        assertThat(config.routeSettings(AiRoute.SPLIT).effort).isEqualTo("medium")
    }

    @Test
    fun `overrides are read and secrets never appear in toString`() {
        val config = BackendConfig.fromEnv(
            minimal + mapOf(
                "PORT" to "9000",
                "DATABASE_USER" to "override-user",
                "DEV_INSTALL_KEY" to TEST_DEV_KEY,
                "DAILY_REQUESTS_PER_INSTALL" to "50",
                "DAILY_BUDGET_USD" to "2.5",
                "OPENROUTER_BASE_URL" to "https://gateway.example.org/api/v1",
                "ROUTE_ENRICH_MODEL" to "anthropic/claude-sonnet-5.5",
                "ROUTE_ENRICH_EFFORT" to "medium",
                "ROUTE_ENRICH_MAX_TOKENS" to "2048",
                "ROUTE_ENRICH_ZDR" to "false",
                "INSTALL_TOKEN_SECRET_PREVIOUS" to "previous-secret-previous-secret-0000",
            ),
        )

        assertThat(config.port).isEqualTo(9000)
        assertThat(config.database.user).isEqualTo("override-user")
        assertThat(config.dailyRequestsPerInstall).isEqualTo(50)
        assertThat(config.dailyBudgetMicroUsd).isEqualTo(2_500_000)
        assertThat(config.openRouterBaseUrl).isEqualTo("https://gateway.example.org/api/v1")
        assertThat(config.routeSettings(AiRoute.ENRICH)).isEqualTo(
            RouteSettings(model = "anthropic/claude-sonnet-5.5", effort = "medium", maxTokens = 2_048, zeroDataRetention = false),
        )
        assertThat(config.installTokenPreviousSecret).isEqualTo("previous-secret-previous-secret-0000")

        val printed = config.toString()
        listOf("sk-or-v1-test-key", TEST_SECRET, TEST_DEV_KEY, "p@ss:word", "previous-secret").forEach { secret ->
            assertThat(printed).doesNotContain(secret)
        }
    }

    @Test
    fun `jdbc urls are taken as they are`() {
        val config = BackendConfig.fromEnv(minimal + ("DATABASE_URL" to "jdbc:postgresql://localhost/tasker"))
        assertThat(config.database.jdbcUrl).isEqualTo("jdbc:postgresql://localhost/tasker")
        assertThat(config.database.user).isNull()
        assertThat(BackendConfig.parseDatabaseUrl("postgresql://db/tasker")?.first).isEqualTo("jdbc:postgresql://db:5432/tasker")
        assertThat(BackendConfig.parseDatabaseUrl("mysql://db/tasker")).isNull()
        assertThat(BackendConfig.parseDatabaseUrl("postgres://db")).isNull()
    }

    @Test
    fun `all problems are reported together by variable name only`() {
        val error = assertThrows(ConfigException::class.java) {
            BackendConfig.fromEnv(
                mapOf(
                    "INSTALL_TOKEN_SECRET" to "too-short-secret-value",
                    "PORT" to "http",
                    "OPENROUTER_BASE_URL" to "http://openrouter.ai/api/v1",
                    "ROUTE_ENRICH_EFFORT" to "maximal",
                    "ROUTE_ENRICH_ZDR" to "maybe",
                    "DAILY_BUDGET_USD" to "-1",
                ),
            )
        }
        val message = checkNotNull(error.message)
        listOf(
            "DATABASE_URL is required",
            "OPENROUTER_API_KEY is required",
            "INSTALL_TOKEN_SECRET must be at least 32 characters",
            "PORT must be an integer",
            "OPENROUTER_BASE_URL must start with https://",
            "ROUTE_ENRICH_EFFORT",
            "ROUTE_ENRICH_ZDR",
            "DAILY_BUDGET_USD",
            "PLAY_PACKAGE_NAME",
        ).forEach { assertThat(message).contains(it) }
        assertThat(message).doesNotContain("too-short-secret-value")
    }

    @Test
    fun `a key that cannot go into a header is rejected without echoing it`() {
        val error = assertThrows(ConfigException::class.java) {
            BackendConfig.fromEnv(minimal + ("OPENROUTER_API_KEY" to "sk-or-v1-ключ"))
        }
        assertThat(error.message).contains("OPENROUTER_API_KEY must be printable ASCII")
        assertThat(error.message).doesNotContain("ключ")
    }

    @Test
    fun `a dev environment needs no play package but a long dev key`() {
        val dev = minimal - "PLAY_PACKAGE_NAME" + ("DEV_INSTALL_KEY" to TEST_DEV_KEY)
        assertThat(BackendConfig.fromEnv(dev).playPackageName).isNull()

        assertThrows(ConfigException::class.java) { BackendConfig.fromEnv(dev + ("DEV_INSTALL_KEY" to "short")) }
        assertThrows(ConfigException::class.java) { BackendConfig.fromEnv(minimal + ("PLAY_PACKAGE_NAME" to "not a package")) }
    }
}
