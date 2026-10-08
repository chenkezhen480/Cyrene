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
CREATE INDEX IF NOT EXISTS idx_trace_owner_time ON agent_traces (user_id, json_extract(full_json, '$.metadata.tenant_id'), (rtrim(timestamp, 'Z') || CASE WHEN instr(timestamp, '.') = 0 THEN '.000000000' ELSE substr('000000000', length(substr(timestamp, instr(timestamp, '.'))) - 1) END || 'Z') DESC, trace_id DESC);
CREATE INDEX IF NOT EXISTS idx_trace_session_time ON agent_traces (session_id, (rtrim(timestamp, 'Z') || CASE WHEN instr(timestamp, '.') = 0 THEN '.000000000' ELSE substr('000000000', length(substr(timestamp, instr(timestamp, '.'))) - 1) END || 'Z') DESC, trace_id DESC);
CREATE INDEX IF NOT EXISTS idx_trace_time ON agent_traces ((rtrim(timestamp, 'Z') || CASE WHEN instr(timestamp, '.') = 0 THEN '.000000000' ELSE substr('000000000', length(substr(timestamp, instr(timestamp, '.'))) - 1) END || 'Z') DESC, trace_id DESC);
