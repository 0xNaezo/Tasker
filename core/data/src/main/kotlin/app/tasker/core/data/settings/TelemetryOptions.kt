package app.tasker.core.data.settings

/**
 * What the telemetry consent covers in this build (tech plan §21, §23). The app module provides it from its build
 * configuration: crash reports need a Sentry DSN, weekly statistics need the backend of Google Play builds. When the
 * build sends neither, settings do not offer the switch.
 */
data class TelemetryOptions(val crashReports: Boolean, val statistics: Boolean) {
    val any: Boolean get() = crashReports || statistics
}
