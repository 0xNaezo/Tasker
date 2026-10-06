package app.tasker.backend

import app.tasker.core.ai.contract.AiRoute
import app.tasker.core.ai.contract.RouteSettings
import app.tasker.core.ai.openrouter.OpenRouter
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** Startup configuration problem; the message lists variable names only, never values. */
class ConfigException(message: String) : RuntimeException(message)

data class DatabaseSettings(
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
    val maxPoolSize: Int,
) {
    override fun toString(): String = "DatabaseSettings(jdbcUrl=${jdbcUrl.substringBefore('?')}, maxPoolSize=$maxPoolSize)"
}

/**
 * Configuration from environment variables (documented in backend/README.md). Secrets never appear in
 * [toString] or in error messages.
 */
class BackendConfig(
    val port: Int,
    val database: DatabaseSettings,
    val openRouterApiKey: String,
    val openRouterBaseUrl: String,
    val installTokenSecret: String,
    val installTokenPreviousSecret: String?,
    val playPackageName: String?,
    val googleCredentialsJson: String?,
    val devInstallKey: String?,
    val dailyRequestsPerInstall: Int,
    val dailyBudgetUsd: Double,
    val routes: Map<AiRoute, RouteSettings>,
) {
    val dailyBudgetMicroUsd: Long get() = (dailyBudgetUsd * MICROS_PER_USD).toLong()

    fun routeSettings(route: AiRoute): RouteSettings = routes.getValue(route)

    override fun toString(): String = "BackendConfig(port=$port, database=$database, playPackageName=$playPackageName, " +
        "devInstallKey=${if (devInstallKey != null) "set" else "unset"}, dailyRequestsPerInstall=$dailyRequestsPerInstall, " +
        "dailyBudgetUsd=$dailyBudgetUsd, enrich=${routeSettings(AiRoute.ENRICH)})"

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_DAILY_REQUESTS_PER_INSTALL = 200
        const val DEFAULT_DAILY_BUDGET_USD = 20.0
        const val DEFAULT_POOL_SIZE = 5
        const val MIN_SECRET_LENGTH = 32
        const val MIN_DEV_KEY_LENGTH = 16
        private const val MICROS_PER_USD = 1_000_000.0
        private const val POSTGRES_PORT = 5432

        fun fromEnv(env: Map<String, String>): BackendConfig = EnvReader(env).read()

        /**
         * Accepts a JDBC URL or the `postgres://user:password@host:port/db?params` form that PaaS providers
         * (Railway, Fly.io, Heroku-style) put into DATABASE_URL.
         */
        fun parseDatabaseUrl(raw: String): Triple<String, String?, String?>? {
            if (raw.startsWith("jdbc:")) return Triple(raw, null, null)
            if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) return null
            val uri = try {
                URI(raw)
            } catch (ignored: URISyntaxException) {
                return null
            }
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else POSTGRES_PORT
            val database = uri.path.orEmpty().removePrefix("/")
            if (database.isEmpty()) return null
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val userInfo = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
            val user = userInfo.getOrNull(0)?.let(::decode)?.takeIf { it.isNotEmpty() }
            val password = userInfo.getOrNull(1)?.let(::decode)
            return Triple("jdbc:postgresql://$host:$port/$database$query", user, password)
        }

        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)
    }
}

private class EnvReader(private val env: Map<String, String>) {
    private val problems = mutableListOf<String>()

    fun read(): BackendConfig {
        val port = int("PORT", BackendConfig.DEFAULT_PORT, 1..MAX_PORT)
        val database = database()
        val apiKey = required("OPENROUTER_API_KEY")
        // The key goes into an HTTP header; the message never repeats the value.
        if (apiKey != null && !apiKey.all { it in '!'..'~' }) problems += "OPENROUTER_API_KEY must be printable ASCII without spaces"
        val baseUrl = optional("OPENROUTER_BASE_URL") ?: OpenRouter.BASE_URL
        if (!baseUrl.startsWith("https://")) problems += "OPENROUTER_BASE_URL must start with https://"
        val secret = required("INSTALL_TOKEN_SECRET")
        if (secret != null && secret.length < BackendConfig.MIN_SECRET_LENGTH) {
            problems += "INSTALL_TOKEN_SECRET must be at least ${BackendConfig.MIN_SECRET_LENGTH} characters"
        }
        val previousSecret = optional("INSTALL_TOKEN_SECRET_PREVIOUS")
        val packageName = optional("PLAY_PACKAGE_NAME")
        if (packageName != null && !PACKAGE_NAME.matches(packageName)) problems += "PLAY_PACKAGE_NAME is not a valid package name"
        val devKey = optional("DEV_INSTALL_KEY")
        if (devKey != null && devKey.length < BackendConfig.MIN_DEV_KEY_LENGTH) {
            problems += "DEV_INSTALL_KEY must be at least ${BackendConfig.MIN_DEV_KEY_LENGTH} characters"
        }
        if (packageName == null && devKey == null) problems += "set PLAY_PACKAGE_NAME (production) or DEV_INSTALL_KEY (dev environment)"
        val requestsPerInstall = int("DAILY_REQUESTS_PER_INSTALL", BackendConfig.DEFAULT_DAILY_REQUESTS_PER_INSTALL, 1..MAX_DAILY_REQUESTS)
        val budget = double("DAILY_BUDGET_USD", BackendConfig.DEFAULT_DAILY_BUDGET_USD)
        val routes = AiRoute.entries.associateWith(::routeSettings)
        if (problems.isNotEmpty()) throw ConfigException("Invalid configuration:\n- " + problems.joinToString("\n- "))
        return BackendConfig(
            port = port,
            database = checkNotNull(database),
            openRouterApiKey = checkNotNull(apiKey),
            openRouterBaseUrl = baseUrl,
            installTokenSecret = checkNotNull(secret),
            installTokenPreviousSecret = previousSecret,
            playPackageName = packageName,
            googleCredentialsJson = optional("GOOGLE_APPLICATION_CREDENTIALS_JSON"),
            devInstallKey = devKey,
            dailyRequestsPerInstall = requestsPerInstall,
            dailyBudgetUsd = budget,
            routes = routes,
        )
    }

    private fun database(): DatabaseSettings? {
        val raw = required("DATABASE_URL") ?: return null
        val parsed = BackendConfig.parseDatabaseUrl(raw)
        if (parsed == null) {
            problems += "DATABASE_URL must be a jdbc:postgresql:// or postgres:// URL"
            return null
        }
        val (jdbcUrl, user, password) = parsed
        return DatabaseSettings(
            jdbcUrl = jdbcUrl,
            user = optional("DATABASE_USER") ?: user,
            password = optional("DATABASE_PASSWORD") ?: password,
            maxPoolSize = int("DATABASE_POOL_SIZE", BackendConfig.DEFAULT_POOL_SIZE, 1..MAX_POOL_SIZE),
        )
    }

    /** ROUTE_<NAME>_MODEL / _EFFORT / _MAX_TOKENS / _ZDR override the defaults of tech plan §17.4 and ADR 0011. */
    private fun routeSettings(route: AiRoute): RouteSettings {
        val prefix = "ROUTE_${route.name}_"
        val defaults = route.defaultSettings
        val effort = optional(prefix + "EFFORT") ?: defaults.effort
        if (effort !in RouteSettings.EFFORTS) problems += "${prefix}EFFORT must be one of ${RouteSettings.EFFORTS}"
        val maxTokens = int(prefix + "MAX_TOKENS", defaults.maxTokens.toInt(), 1..RouteSettings.MAX_OUTPUT_TOKENS.toInt())
        val zeroDataRetention = when (val value = optional(prefix + "ZDR")?.lowercase()) {
            null -> defaults.zeroDataRetention
            "true" -> true
            "false" -> false
            else -> {
                problems += "${prefix}ZDR must be true or false (was '$value')"
                defaults.zeroDataRetention
            }
        }
        return RouteSettings(
            model = optional(prefix + "MODEL") ?: defaults.model,
            effort = effort.takeIf { it in RouteSettings.EFFORTS } ?: defaults.effort,
            maxTokens = maxTokens.toLong(),
            zeroDataRetention = zeroDataRetention,
        )
    }

    private fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    private fun required(name: String): String? = optional(name) ?: run {
        problems += "$name is required"
        null
    }

    private fun int(name: String, default: Int, range: IntRange): Int {
        val raw = optional(name) ?: return default
        val value = raw.toIntOrNull()
        if (value == null || value !in range) {
            problems += "$name must be an integer in $range"
            return default
        }
        return value
    }

    private fun double(name: String, default: Double): Double {
        val raw = optional(name) ?: return default
        val value = raw.toDoubleOrNull()
        if (value == null || !value.isFinite() || value <= 0.0) {
            problems += "$name must be a positive number"
            return default
        }
        return value
    }

    private companion object {
        const val MAX_PORT = 65_535
        const val MAX_DAILY_REQUESTS = 1_000_000
        const val MAX_POOL_SIZE = 100
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
