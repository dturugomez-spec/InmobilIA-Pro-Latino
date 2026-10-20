-- Sprint 77: Tabla unificada de integraciones externas
CREATE TABLE IF NOT EXISTS integraciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    proveedor TEXT NOT NULL,               -- holded, sage, a3, google_calendar, gmail, etc.
    api_key TEXT,
    refresh_token TEXT,
    access_token TEXT,
    token_expira_at TIMESTAMPTZ,
    config_extra JSONB,
    activo BOOLEAN DEFAULT TRUE,
    ultimo_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, proveedor)
);
CREATE INDEX IF NOT EXISTS idx_integ_user ON integraciones(user_id);
