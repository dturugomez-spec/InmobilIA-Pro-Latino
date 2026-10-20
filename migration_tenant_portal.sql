-- ============================================================================
-- InmobilIA Pro · Sprint 26: Portal del inquilino
-- ============================================================================

-- Vincular un user con un inquilino (cuando el rol es 'tenant_portal')
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES inquilinos(id) ON DELETE SET NULL;

-- Solicitudes de reparación / mantenimiento que crean los inquilinos
CREATE TABLE IF NOT EXISTS tenant_requests (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    inquilino_id    UUID REFERENCES inquilinos(id) ON DELETE SET NULL,
    org_id          UUID NOT NULL,
    property_name   TEXT,
    category        TEXT,                          -- 'mantenimiento', 'incidencia', 'consulta', 'otro'
    title           TEXT NOT NULL,
    description     TEXT,
    photo_urls      JSONB DEFAULT '[]'::jsonb,
    priority        TEXT DEFAULT 'Media',
    status          TEXT DEFAULT 'Nueva',           -- Nueva, En revisión, En curso, Resuelta, Cerrada
    response        TEXT,
    responded_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tenant_req_org    ON tenant_requests(org_id, status);
CREATE INDEX IF NOT EXISTS idx_tenant_req_tenant ON tenant_requests(tenant_user_id);

-- Invitaciones a inquilinos para acceder al portal
CREATE TABLE IF NOT EXISTS tenant_invites (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    inquilino_id  UUID NOT NULL REFERENCES inquilinos(id) ON DELETE CASCADE,
    org_id        UUID NOT NULL,
    email         TEXT NOT NULL,
    token         TEXT UNIQUE NOT NULL,
    accepted_at   TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '14 days'),
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tenant_invites_token ON tenant_invites(token);

SELECT 'Migration tenant portal completed' AS status;
