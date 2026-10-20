-- Sprint diferenciación: Captación proactiva / Farming por zona con IA
-- El agente sale a buscar exclusivas: elige zona + ángulo, la IA redacta los
-- mensajes (carta/WhatsApp/email) con el enlace de valoración, y gestiona los
-- destinatarios como un mini-pipeline de captación. Cierra el embudo:
-- Farming (outbound) → Valoración (captura) → Portal (retención).

CREATE TABLE IF NOT EXISTS captacion_campanas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    zona TEXT,
    tipo_objetivo TEXT,              -- propietarios_piso, chalets, locales, herederos...
    angulo TEXT,                     -- tengo_comprador, valoracion_gratis, vendi_cerca, mercado_alto...

    mensaje_carta TEXT,
    mensaje_whatsapp TEXT,
    mensaje_email TEXT,

    estado TEXT DEFAULT 'activa',    -- activa, pausada, cerrada
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_captacion_campanas_user ON captacion_campanas(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS captacion_destinatarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    campana_id UUID REFERENCES captacion_campanas(id) ON DELETE CASCADE,

    nombre TEXT,
    direccion TEXT,
    telefono TEXT,
    email TEXT,

    estado TEXT DEFAULT 'pendiente', -- pendiente, contactado, respondido, captado, descartado
    notas TEXT,
    lead_id UUID,
    contactado_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_captacion_dest_campana ON captacion_destinatarios(campana_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_captacion_dest_user ON captacion_destinatarios(user_id);
