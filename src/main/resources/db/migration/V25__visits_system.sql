-- Sprint 54: Sistema completo de visitas a propiedades
CREATE TABLE IF NOT EXISTS visitas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,
    lead_id UUID REFERENCES leads(id) ON DELETE SET NULL,
    inquilino_id UUID REFERENCES inquilinos(id) ON DELETE SET NULL,
    agente_id UUID REFERENCES users(id) ON DELETE SET NULL,
    match_id UUID REFERENCES matches(id) ON DELETE SET NULL,

    -- Datos visitante
    visitante_nombre TEXT NOT NULL,
    visitante_email TEXT,
    visitante_telefono TEXT,
    visitante_dni TEXT,

    -- Fecha y duración
    fecha_inicio TIMESTAMPTZ NOT NULL,
    fecha_fin TIMESTAMPTZ NOT NULL,
    duracion_min INT DEFAULT 30,

    -- Estado
    estado TEXT DEFAULT 'programada',  -- programada, confirmada, realizada, cancelada, no_presentado
    tipo TEXT DEFAULT 'presencial',    -- presencial, virtual, open_house

    -- URLs
    confirmacion_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    feedback_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    reunion_url TEXT,                   -- Para visitas virtuales (Zoom/Meet)

    -- Recordatorios
    recordatorio_24h_enviado BOOLEAN DEFAULT FALSE,
    recordatorio_1h_enviado BOOLEAN DEFAULT FALSE,

    -- Confirmación
    confirmada_at TIMESTAMPTZ,
    cancelada_at TIMESTAMPTZ,
    motivo_cancelacion TEXT,

    -- Feedback post-visita (rellenado por el visitante o agente)
    interes_nivel INT,                  -- 1-5
    feedback_visitante TEXT,
    feedback_agente TEXT,
    objeciones TEXT,
    siguiente_paso TEXT,                -- segunda_visita, oferta, descartado, sin_decision
    fecha_siguiente_paso DATE,

    -- Conversión
    convirtio_en_oferta BOOLEAN DEFAULT FALSE,
    convirtio_en_reserva BOOLEAN DEFAULT FALSE,

    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_visitas_user ON visitas(user_id);
CREATE INDEX IF NOT EXISTS idx_visitas_propiedad ON visitas(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_visitas_agente ON visitas(agente_id);
CREATE INDEX IF NOT EXISTS idx_visitas_fecha ON visitas(fecha_inicio);
CREATE INDEX IF NOT EXISTS idx_visitas_estado ON visitas(estado);
CREATE INDEX IF NOT EXISTS idx_visitas_confirmacion_token ON visitas(confirmacion_token);
CREATE INDEX IF NOT EXISTS idx_visitas_feedback_token ON visitas(feedback_token);

-- Slots de disponibilidad del agente (semanal recurrente)
CREATE TABLE IF NOT EXISTS slots_disponibilidad (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    agente_id UUID REFERENCES users(id) ON DELETE CASCADE,
    dia_semana INT NOT NULL,            -- 0=domingo ... 6=sábado
    hora_inicio TIME NOT NULL,
    hora_fin TIME NOT NULL,
    activo BOOLEAN DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_slots_agente ON slots_disponibilidad(agente_id, dia_semana);
