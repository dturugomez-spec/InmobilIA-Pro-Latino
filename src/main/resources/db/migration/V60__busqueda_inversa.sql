-- Sprint 108: Búsqueda inversa (clientes que reciben alertas)
CREATE TABLE IF NOT EXISTS busquedas_guardadas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lead_id UUID,
    cliente_nombre TEXT,
    cliente_email TEXT,
    cliente_telefono TEXT,
    nombre_busqueda TEXT,

    -- Criterios
    operacion TEXT,                          -- venta, alquiler
    tipo TEXT,                                -- piso, casa, etc.
    municipio TEXT,
    zonas TEXT[],
    precio_min NUMERIC(12,2),
    precio_max NUMERIC(12,2),
    metros_min NUMERIC(7,2),
    metros_max NUMERIC(7,2),
    habitaciones_min INT,
    banos_min INT,
    requiere_garaje BOOLEAN,
    requiere_ascensor BOOLEAN,
    requiere_terraza BOOLEAN,

    -- Notificaciones
    notificar_email BOOLEAN DEFAULT TRUE,
    notificar_whatsapp BOOLEAN DEFAULT FALSE,
    frecuencia TEXT DEFAULT 'inmediata',     -- inmediata, diaria, semanal

    activa BOOLEAN DEFAULT TRUE,
    matches_count INT DEFAULT 0,
    ultima_notificacion TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_busq_user ON busquedas_guardadas(user_id) WHERE activa = true;
