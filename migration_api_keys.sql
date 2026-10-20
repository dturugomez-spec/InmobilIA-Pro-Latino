-- ============================================================================
-- InmobilIA Pro · Sprint 37: API pública con tokens
-- ============================================================================

CREATE TABLE IF NOT EXISTS api_keys (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id      UUID NOT NULL,
    name        TEXT NOT NULL,
    token_hash  TEXT UNIQUE NOT NULL,           -- bcrypt del token, nunca se guarda en plano
    prefix      TEXT NOT NULL,                   -- primeros 8 chars del token (visible en lista)
    scopes      TEXT[] DEFAULT ARRAY['read'],    -- 'read', 'write'
    last_used   TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_api_keys_user ON api_keys(user_id) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys(token_hash) WHERE revoked_at IS NULL;

SELECT 'Migration api keys completed' AS status;
