package app.tasker.core.model

import kotlinx.serialization.Serializable

/** Task lifecycle (ТЗ §4, tech plan §8.1). "In review" is a flag on an OPEN task, not a status. */
@Serializable
enum class TaskStatus {
    OPEN,
    IN_PROGRESS,
    PAUSED,
    DONE,
    ARCHIVED,
    ;

    /** Open, in progress or paused: the task still needs work. */
    val isActive: Boolean get() = this == OPEN || this == IN_PROGRESS || this == PAUSED
}

/** Horizon of a task. `null` bucket means Inbox (CAP-5). */
@Serializable
enum class Bucket { TODAY, WEEK, SOMEDAY }

/** Rough size (PLN-1). `null` estimate is "not set" and counts as M in calculations. */
@Serializable
enum class Estimate { S, M, L }

@Serializable
enum class ProjectStatus { ACTIVE, COMPLETED, ARCHIVED }

@Serializable
enum class ArchiveReason { USER, TTL_SKIPS, PROJECT_ARCHIVED, SPLIT, MERGED, TELEGRAM_CANCELLED }

/** Capture channel, used for the "cheap capture" metric (ТЗ §11). */
@Serializable
enum class CaptureChannel { BAR, WIDGET, TILE, SHORTCUT, SHARE, VOICE, TELEGRAM, GITHUB, IMPORT }

@Serializable
enum class EnrichState { NONE, PENDING, DONE, FAILED }

/**
 * Field provenance (tech plan §8.3). Priority USER > PARSER > INTEGRATION > AI > DEFAULT:
 * a writer may overwrite a field only if its priority is at least the current one.
 */
@Serializable
enum class FieldSource(val priority: Int) {
    DEFAULT(0),
    AI(1),
    INTEGRATION(2),
    PARSER(3),
    USER(4),
}

/** Task fields whose provenance is tracked. */
@Serializable
enum class TaskField { TITLE, NOTE, BUCKET, DEADLINE, PLAN_DATE, ESTIMATE, PROJECT, TAGS }

@Serializable
enum class SourceKind { TELEGRAM, GITHUB_ISSUE, GITHUB_PR, LINK, APP }

@Serializable
enum class SnapshotInputKind { TEXT, VOICE }

@Serializable
enum class Actor { USER, RULE, AI, INTEGRATION }

@Serializable
enum class EntityType { TASK, PROJECT, PLAN, SETTINGS }

@Serializable
enum class EventType {
    CREATED,
    UPDATED,
    STATUS_CHANGED,
    POSTPONED,
    ROLLED_OVER,
    MOVED,
    REORDERED,
    SNAPSHOT_ADDED,
    REVIEW_ENTERED,
    REVIEW_DECIDED,
    REVIEW_SKIPPED,
    AI_FILLED,
    SPLIT,
    MERGED,
    ARCHIVED,
    RESTORED,
    PLAN_CHANGED,
    UNDONE,
}

@Serializable
enum class PlanState { DRAFT, ACCEPTED }

@Serializable
enum class PlanItemOrigin { AUTO, MANUAL, LATE_ADD }

@Serializable
enum class PlanItemOutcome { PENDING, DONE, CARRIED_OVER, REMOVED }

/** Candidate groups for the day plan in priority order (PLN-4, tech plan §10.3). */
@Serializable
enum class CandidateGroup(val order: Int) {
    OVERDUE(1),
    DEADLINE_SOON(2),
    IN_PROGRESS(3),
    PLANNED(4),
    WEEK(5),
    ;

    /** Tasks in review are auto-selected only from the deadline groups (interpretation 22). */
    val autoSelectsInReview: Boolean get() = this == OVERDUE || this == DEADLINE_SOON
}

@Serializable
enum class ReviewKind { RELEVANCE, INBOX_TRIAGE }

@Serializable
enum class ReviewDecision {
    RELEVANT,
    SOMEDAY,
    ARCHIVE,
    TODAY,
    WEEK,
    PROJECT,
    COMPLETE_PROJECT,
    ADD_STEP,
}

@Serializable
enum class AiMode { DIRECT, PROXY }

@Serializable
enum class SettingSource { DEFAULT, USER, AUTO }
