-- Sprint 95: Staging digital (mobiliario virtual)
CREATE TABLE IF NOT EXISTS staging_proyectos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    nombre TEXT,
    imagen_original_url TEXT,
    imagen_staged_url TEXT,
    estilo TEXT,                            -- moderno, nordico, industrial, clasico, minimalista
    tipo_habitacion TEXT,
    estado TEXT DEFAULT 'pendiente',        -- pendiente, procesando, completado, error
    coste NUMERIC(8,2),
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_staging_user ON staging_proyectos(user_id);
