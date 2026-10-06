-- Tech plan §18.1: install registry, persistent limits, token and cost accounting, weekly metric aggregates.
-- Task texts are never stored. Portable between PostgreSQL and H2 (PostgreSQL mode, used by tests).
-- Days are UTC calendar days, like the provider's billing.

CREATE TABLE IF NOT EXISTS installs (
    install_id VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 'play' (Play Integrity) or 'dev' (DEV_INSTALL_KEY in the dev environment)
    verification VARCHAR(16) NOT NULL,
    -- set manually to stop an abusive install; its tokens are then rejected
    blocked BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS daily_usage (
    install_id VARCHAR(64) NOT NULL REFERENCES installs (install_id) ON DELETE CASCADE,
    usage_day DATE NOT NULL,
    requests INTEGER NOT NULL DEFAULT 0,
    succeeded INTEGER NOT NULL DEFAULT 0,
    refused INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    cache_creation_tokens BIGINT NOT NULL DEFAULT 0,
    cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    latency_ms_total BIGINT NOT NULL DEFAULT 0,
    latency_ms_max BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (install_id, usage_day)
);

CREATE INDEX IF NOT EXISTS daily_usage_day_idx ON daily_usage (usage_day);

CREATE TABLE IF NOT EXISTS global_daily_cost (
    cost_day DATE PRIMARY KEY,
    requests INTEGER NOT NULL DEFAULT 0,
    cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    warned_at_80 BOOLEAN NOT NULL DEFAULT FALSE,
    exhausted_alerted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS metrics_weekly (
    install_id VARCHAR(64) NOT NULL REFERENCES installs (install_id) ON DELETE CASCADE,
    week_start DATE NOT NULL,
    counters_json TEXT NOT NULL,
    values_json TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (install_id, week_start)
);
