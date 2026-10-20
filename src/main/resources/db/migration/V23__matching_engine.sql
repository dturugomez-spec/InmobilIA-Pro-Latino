-- Sprint 52: Motor de matching leads ↔ propiedades

-- Preferencias detalladas del lead/cliente
CREATE TABLE IF NOT EXISTS preferencias_busqueda (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lead_id UUID REFERENCES leads(id) ON DELETE CASCADE,
    inquilino_id UUID REFERENCES inquilinos(id) ON DELETE CASCADE,

    -- Tipo operación
    operacion TEXT,                     -- venta, alquiler, alquiler_temporal
    tipo_propiedad TEXT[],              -- piso, casa, atico, duplex, local, oficina, garaje, trastero, terreno

    -- Económico
    presupuesto_min NUMERIC(12,2),
    presupuesto_max NUMERIC(12,2),

    -- Ubicación
    zonas TEXT[],                       -- array de zonas/barrios deseados
    municipios TEXT[],
    radio_km NUMERIC(5,2),              -- radio desde un punto
    lat NUMERIC(10,7),
    lng NUMERIC(10,7),

    -- Características
    habitaciones_min INT,
    habitaciones_max INT,
    banos_min INT,
    superficie_min NUMERIC(8,2),
    superficie_max NUMERIC(8,2),

    -- Booleanos deseados
    requiere_garaje BOOLEAN DEFAULT FALSE,
    requiere_ascensor BOOLEAN DEFAULT FALSE,
    requiere_terraza BOOLEAN DEFAULT FALSE,
    requiere_piscina BOOLEAN DEFAULT FALSE,
    requiere_aire_acondicionado BOOLEAN DEFAULT FALSE,
    requiere_amueblado BOOLEAN DEFAULT FALSE,
    permite_mascotas BOOLEAN DEFAULT FALSE,
    obra_nueva BOOLEAN DEFAULT FALSE,

    -- Otros
    eficiencia_min TEXT,                -- A, B, C, D, E, F, G
    ano_construccion_min INT,
    notas TEXT,

    -- Sistema de matching
    auto_match BOOLEAN DEFAULT TRUE,    -- enviar emails automáticos
    notificar_email BOOLEAN DEFAULT TRUE,
    notificar_whatsapp BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prefs_user ON preferencias_busqueda(user_id);
CREATE INDEX IF NOT EXISTS idx_prefs_lead ON preferencias_busqueda(lead_id);

-- Tabla de matches realizados (para no spamear)
CREATE TABLE IF NOT EXISTS matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    preferencia_id UUID REFERENCES preferencias_busqueda(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    lead_id UUID,
    inquilino_id UUID,

    score NUMERIC(5,2),                 -- 0-100 afinidad
    razones JSONB DEFAULT '[]'::jsonb,  -- ["precio_ok", "habitaciones_match", ...]
    descartes JSONB DEFAULT '[]'::jsonb, -- ["zona_no_coincide"]

    estado TEXT DEFAULT 'nuevo',        -- nuevo, enviado, visto, interesado, descartado, visita
    notificado_at TIMESTAMPTZ,
    visto_at TIMESTAMPTZ,
    feedback TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_matches_pref_prop ON matches(preferencia_id, propiedad_id);
CREATE INDEX IF NOT EXISTS idx_matches_estado ON matches(estado);
CREATE INDEX IF NOT EXISTS idx_matches_score ON matches(score DESC);
