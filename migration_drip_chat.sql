-- ============================================================================
-- InmobilIA Pro · Sprint 35: Drip campaigns + chat interno
-- ============================================================================

-- Drip campaigns (secuencias de emails automáticas)
CREATE TABLE IF NOT EXISTS drip_campaigns (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    trigger     TEXT NOT NULL,           -- 'lead_created', 'visit_done', 'contract_signed'
    active      BOOLEAN DEFAULT true,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS drip_steps (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    campaign_id   UUID NOT NULL REFERENCES drip_campaigns(id) ON DELETE CASCADE,
    delay_days    INTEGER NOT NULL DEFAULT 0,
    subject       TEXT NOT NULL,
    body          TEXT NOT NULL,
    sort_order    INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_drip_steps_campaign ON drip_steps(campaign_id, sort_order);

-- Tracking: qué leads están en qué punto de la secuencia
CREATE TABLE IF NOT EXISTS drip_enrollments (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    campaign_id   UUID NOT NULL REFERENCES drip_campaigns(id) ON DELETE CASCADE,
    lead_id       UUID NOT NULL,
    org_id        UUID NOT NULL,
    enrolled_at   TIMESTAMPTZ DEFAULT NOW(),
    next_send_at  TIMESTAMPTZ,
    next_step     INTEGER DEFAULT 0,
    completed     BOOLEAN DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_drip_enrollments_pending ON drip_enrollments(next_send_at) WHERE completed = false;

-- ─── Chat interno entre miembros del equipo ───
CREATE TABLE IF NOT EXISTS team_messages (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id       UUID NOT NULL,
    from_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_user_id   UUID REFERENCES users(id) ON DELETE CASCADE,  -- null = canal general
    entity_type  TEXT,                                          -- contexto opcional
    entity_id    TEXT,
    body         TEXT NOT NULL,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_team_msg_org ON team_messages(org_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_team_msg_to  ON team_messages(to_user_id, read_at);

SELECT 'Migration drip + chat completed' AS status;
