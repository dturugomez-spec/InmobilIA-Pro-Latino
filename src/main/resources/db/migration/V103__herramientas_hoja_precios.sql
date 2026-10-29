-- Las cuatro herramientas de la hoja de precios que faltaban: Communication AutoPilot,
-- comandos de voz (sin tablas propias), gestión multi-empresa y Negotiation Co-Pilot.
-- Ejecutar a mano en Supabase (SQL Editor). Se puede ejecutar varias veces.

-- ── Communication AutoPilot: avisos a inquilinos ─────────────────────
-- Qué se envía solo cada día (con plantilla, sin IA). Lo demás se revisa y se envía a mano.
CREATE TABLE IF NOT EXISTS autopilot_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    auto_renovacion BOOLEAN DEFAULT FALSE,       -- contrato que vence en 90 días o menos
    auto_actualizacion BOOLEAN DEFAULT FALSE,    -- actualización de renta en 30 días o menos
    auto_pago BOOLEAN DEFAULT FALSE,             -- recibo sin pagar desde hace 5 días o más
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Lo que se ha enviado (para no repetir el mismo aviso y para el historial)
CREATE TABLE IF NOT EXISTS comunicaciones_autopilot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    tipo TEXT NOT NULL,                          -- renovacion | actualizacion | pago | incidencia
    ref_id TEXT NOT NULL,                        -- contrato, recibo o incidencia
    destinatario TEXT,
    email TEXT,
    asunto TEXT,
    cuerpo TEXT,
    modo TEXT DEFAULT 'manual',                  -- manual | automatico
    enviado BOOLEAN DEFAULT TRUE,
    error TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_comunicaciones_autopilot_user ON comunicaciones_autopilot(user_id, created_at DESC);

-- ── Gestión multi-empresa ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sociedades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    nombre TEXT NOT NULL,
    cif TEXT,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sociedades_user ON sociedades(user_id);
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS sociedad_id UUID;
-- Sociedades por encima de las 3 de Business (15 €/mes cada una)
ALTER TABLE users ADD COLUMN IF NOT EXISTS sociedades_extra INTEGER DEFAULT 0;

-- ── Negotiation Co-Pilot ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS negociaciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    propiedad_id UUID,
    titulo TEXT,
    operacion TEXT,                              -- compra | venta | alquiler
    rol TEXT,                                    -- compro | vendo | alquilo (arrendador) | arriendo (arrendatario)
    precio_pedido NUMERIC,
    apertura NUMERIC,
    objetivo NUMERIC,
    limite NUMERIC,
    plan JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_negociaciones_user ON negociaciones(user_id, created_at DESC);

ALTER TABLE autopilot_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE comunicaciones_autopilot ENABLE ROW LEVEL SECURITY;
ALTER TABLE sociedades ENABLE ROW LEVEL SECURITY;
ALTER TABLE negociaciones ENABLE ROW LEVEL SECURITY;

NOTIFY pgrst, 'reload schema';
