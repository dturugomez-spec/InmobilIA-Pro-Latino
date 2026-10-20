-- Sprint 53: Feeds XML para portales (Idealista, Fotocasa, Habitaclia, Pisos.com)
CREATE TABLE IF NOT EXISTS portal_feeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    feed_token TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),
    company_name TEXT,
    company_phone TEXT,
    company_email TEXT,
    company_logo_url TEXT,
    company_website TEXT,
    -- Activación por portal
    idealista_enabled BOOLEAN DEFAULT TRUE,
    fotocasa_enabled BOOLEAN DEFAULT TRUE,
    habitaclia_enabled BOOLEAN DEFAULT TRUE,
    pisoscom_enabled BOOLEAN DEFAULT TRUE,
    -- Filtros: solo propiedades publicables
    publicar_todas BOOLEAN DEFAULT FALSE,
    -- Estadísticas
    accesos_total INT DEFAULT 0,
    ultimo_acceso TIMESTAMPTZ,
    ultimo_ip TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_portal_feeds_token ON portal_feeds(feed_token);

-- Flag por propiedad para publicar en portales
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS publicar_portales BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS destacada BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS referencia TEXT;

-- Estadísticas de accesos por portal
CREATE TABLE IF NOT EXISTS portal_feed_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID,
    portal TEXT,
    ip TEXT,
    user_agent TEXT,
    propiedades_count INT,
    bytes_servidos BIGINT,
    duracion_ms INT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_portal_logs_user ON portal_feed_logs(user_id, created_at DESC);
