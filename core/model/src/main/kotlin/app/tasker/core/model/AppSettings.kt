package app.tasker.core.model

import kotlinx.serialization.Serializable

/**
 * All user settings with defaults (SET-1, SET-2, tech plan §7.7). The app works fully without opening
 * settings; self-tuned values keep their provenance (TUNE-4).
 */
@Serializable
data class AppSettings(
    val workDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val workStartMinutes: Int = 9 * 60,
    val workEndMinutes: Int = 18 * 60,
    val bufferPercent: Int = 30,
    val estimateMinutes: EstimateMinutes = EstimateMinutes(),
    val ttl: TtlDays = TtlDays(),
    val postponeThreshold: Int = 3,
    val wipLimit: Int = 3,
    val deadlineHorizonDays: Int = 2,
    val inboxTriageThreshold: Int = 20,
    val planTimeMinutes: Int? = null,
    val reviewDay: Int = 5,
    val reviewTimeMinutes: Int = 17 * 60,
    val quietStartMinutes: Int = 22 * 60,
    val quietEndMinutes: Int = 8 * 60,
    val dailyNotificationLimit: Int = 3,
    val deadlineReminderMinutes: List<Int> = listOf(24 * 60, 2 * 60),
    val notifyPlan: Boolean = true,
    val notifyDeadlines: Boolean = true,
    val notifyWeeklyReview: Boolean = true,
    val selectedCalendars: Set<String>? = null,
    val tentativeIsBusy: Boolean = true,
    val appLanguage: String? = null,
    val parserLanguages: Set<String>? = null,
    val ai: AiSettings = AiSettings(),
    val biometricLock: Boolean = false,
    val dayBoundaryMinutes: Int = 4 * 60,
    val onboardingDone: Boolean = false,
    val telemetryConsent: Boolean = false,
    val backupTreeUri: String? = null,
    val provenance: Map<String, SettingSource> = emptyMap(),
) {
    /** Morning plan time: 30 minutes before the working day unless set explicitly (§7.7). */
    val effectivePlanTimeMinutes: Int
        get() = planTimeMinutes ?: (workStartMinutes - 30).coerceAtLeast(0)

    fun isWorkDay(isoDayOfWeek: Int): Boolean = isoDayOfWeek in workDays
}

@Serializable
data class EstimateMinutes(
    val s: Int = 15,
    val m: Int = 60,
    val l: Int = 180,
) {
    /** A task without an estimate counts as M (PLN-1). */
    fun of(estimate: Estimate?): Int = when (estimate) {
        Estimate.S -> s
        Estimate.M, null -> m
        Estimate.L -> l
    }
}

@Serializable
data class TtlDays(
    val inbox: Int = 7,
    val todayWeek: Int = 14,
    val someday: Int = 60,
    val project: Int = 30,
) {
    fun forBucket(bucket: Bucket?): Int = when (bucket) {
        null -> inbox
        Bucket.TODAY, Bucket.WEEK -> todayWeek
        Bucket.SOMEDAY -> someday
    }
}

@Serializable
data class AiSettings(
    val enabled: Boolean = false,
    val consentAtEpochMillis: Long? = null,
    val consentPromptDismissed: Boolean = false,
    val mode: AiMode? = null,
    val model: String = DEFAULT_MODEL,
) {
    val hasConsent: Boolean get() = consentAtEpochMillis != null

    /** AI runs only after explicit consent and while the switch is on (SET-3). */
    val isActive: Boolean get() = enabled && hasConsent

    companion object {
        /** An OpenRouter model id (ADR 0011). */
        const val DEFAULT_MODEL = "anthropic/claude-opus-5.5"
    }
}
