-- SQLite trace schema. Apply to the configured audit database before starting Cyrene.
CREATE TABLE IF NOT EXISTS agent_traces (
    trace_id          TEXT PRIMARY KEY,
    timestamp         TEXT NOT NULL,
    user_id           TEXT,
    session_id        TEXT,
    input_text        TEXT,
    intent            TEXT,
    llm_model         TEXT,
    steps_json        TEXT,
    final_output      TEXT,
    risk_level        TEXT,
    total_duration_ms INTEGER,
    total_tokens      INTEGER,
    full_json         TEXT NOT NULL
);
