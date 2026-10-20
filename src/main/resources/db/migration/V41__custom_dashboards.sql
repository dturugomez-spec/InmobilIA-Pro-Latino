-- Sprint 71: Dashboards personalizados con widgets
CREATE TABLE IF NOT EXISTS dashboards_custom (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    descripcion TEXT,
    es_default BOOLEAN DEFAULT FALSE,
    visible_para TEXT DEFAULT 'yo',       -- yo, mi_equipo, publico
    icono TEXT DEFAULT '📊',
    widgets JSONB NOT NULL DEFAULT '[]',  -- array de widgets configurados
    columnas INT DEFAULT 12,              -- grid 12 columnas
    fondo_color TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dash_custom_user ON dashboards_custom(user_id);
