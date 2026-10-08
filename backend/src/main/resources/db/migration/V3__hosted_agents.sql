CREATE TABLE hosted_agents (
    agent_id text PRIMARY KEY,
    provider text NOT NULL CHECK (provider IN ('nvidia')),
    model text NOT NULL,
    encrypted_key text NOT NULL,
    status text NOT NULL DEFAULT 'ready',
    last_error text NOT NULL DEFAULT '',
    last_event_id bigint NOT NULL DEFAULT 0,
    last_run_at timestamptz,
    next_run_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    day_index bigint NOT NULL DEFAULT -1,
    calls_today integer NOT NULL DEFAULT 0,
    tokens_today bigint NOT NULL DEFAULT 0,
    total_calls bigint NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0
);
CREATE INDEX hosted_agents_due ON hosted_agents(next_run_at) WHERE status IN ('ready','running','backoff','budget_paused');
