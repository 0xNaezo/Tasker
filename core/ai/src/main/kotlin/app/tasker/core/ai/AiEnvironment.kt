package app.tasker.core.ai

import app.tasker.core.model.AiMode

/**
 * What the build offers for AI (tech plan §17.2, §24.1). The access mode follows the install channel:
 * - `github` flavor (Android Studio, GitHub Releases): direct mode only, with the user's own OpenRouter API key;
 * - `play` flavor (Google Play): proxy mode only, with an install token after a Play Integrity check.
 *
 * **Required binding.** `core:ai` declares no default: the `app` module must provide it from its flavor, e.g.
 *
 * ```
 * @Provides fun aiEnvironment(): AiEnvironment = when (BuildConfig.CHANNEL) {
 *     "play" -> AiEnvironment(directAvailable = false, proxyAvailable = true, proxyBaseUrl = BuildConfig.AI_PROXY_URL)
 *     else -> AiEnvironment(directAvailable = true, proxyAvailable = false, proxyBaseUrl = null)
 * }
 * ```
 *
 * @property directAvailable the direct mode (own API key) may be used.
 * @property proxyAvailable the proxy mode may be used; requires [proxyBaseUrl].
 * @property proxyBaseUrl base URL of the AI proxy, e.g. `https://ai.example.org` (no trailing path).
 * @property integrityCloudProjectNumber Google Cloud project number for Play Integrity; optional for apps that are
 *   distributed only through Google Play.
 * @property proxyDevInstallKey dev environment of the proxy only (§18.1): the shared test key is sent in
 *   `X-Dev-Install-Key` instead of a Play Integrity token. Never set it in release builds.
 */
data class AiEnvironment(
    val directAvailable: Boolean,
    val proxyAvailable: Boolean,
    val proxyBaseUrl: String?,
    val integrityCloudProjectNumber: Long? = null,
    val proxyDevInstallKey: String? = null,
) {
    init {
        require(!proxyAvailable || !proxyBaseUrl.isNullOrBlank()) { "The proxy mode needs a base URL" }
    }

    /** Modes the user can choose from in this build. */
    val availableModes: Set<AiMode>
        get() = buildSet {
            if (directAvailable) add(AiMode.DIRECT)
            if (proxyAvailable) add(AiMode.PROXY)
        }

    // The dev key is a credential: keep it out of logs and crash reports.
    override fun toString(): String = "AiEnvironment(directAvailable=$directAvailable, proxyAvailable=$proxyAvailable, " +
        "proxyBaseUrl=$proxyBaseUrl, integrityCloudProjectNumber=$integrityCloudProjectNumber, " +
        "proxyDevInstallKey=${if (proxyDevInstallKey == null) "null" else "<set>"})"
}
