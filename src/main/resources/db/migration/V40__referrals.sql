-- Sprint 70: Programa de referidos
CREATE TABLE IF NOT EXISTS referidos_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    activo BOOLEAN DEFAULT TRUE,
    premio_referente TEXT,                -- "200€ Amazon", "1 mes gratis", etc.
    premio_referido TEXT,                  -- premio para quien usa el código
    premio_tipo TEXT DEFAULT 'fijo',       -- fijo, porcentaje, mes_gratis
    premio_valor NUMERIC(10,2),
    requiere_cierre BOOLEAN DEFAULT TRUE,  -- solo se paga si hay venta
    mensaje_landing TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS referidos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    referente_nombre TEXT NOT NULL,
    referente_email TEXT NOT NULL,
    referente_telefono TEXT,
    codigo TEXT UNIQUE NOT NULL,           -- código único compartible

    -- Persona referida
    referido_nombre TEXT,
    referido_email TEXT,
    referido_telefono TEXT,
    lead_id UUID,

    estado TEXT DEFAULT 'pendiente',       -- pendiente, contactado, calificado, cerrado_ok, cerrado_ko
    fecha_referencia TIMESTAMPTZ DEFAULT NOW(),
    fecha_cierre TIMESTAMPTZ,
    valor_operacion NUMERIC(12,2),

    -- Premio
    premio_concedido BOOLEAN DEFAULT FALSE,
    premio_pagado_at TIMESTAMPTZ,
    premio_detalle TEXT,

    notas TEXT
);

CREATE INDEX IF NOT EXISTS idx_referidos_user ON referidos(user_id);
CREATE INDEX IF NOT EXISTS idx_referidos_codigo ON referidos(codigo);
CREATE INDEX IF NOT EXISTS idx_referidos_estado ON referidos(estado);
