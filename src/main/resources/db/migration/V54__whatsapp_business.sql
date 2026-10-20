-- Sprint 96: WhatsApp Business Cloud API (Meta)
CREATE TABLE IF NOT EXISTS whatsapp_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    phone_number_id TEXT,
    business_account_id TEXT,
    access_token TEXT,
    webhook_verify_token TEXT,
    numero_emisor TEXT,                    -- +34 600 000 000
    nombre_emisor TEXT,
    activo BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS whatsapp_plantillas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,                  -- bienvenida_lead
    nombre_meta TEXT,                       -- nombre aprobado por Meta
    categoria TEXT,                         -- MARKETING, UTILITY, AUTHENTICATION
    idioma TEXT DEFAULT 'es',
    cuerpo TEXT,                            -- "Hola {{1}}, gracias por contactar..."
    variables JSONB,
    estado_aprobacion TEXT DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS whatsapp_mensajes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    chat_id TEXT,                           -- número receptor formateado
    direccion TEXT,                         -- entrante, saliente
    tipo TEXT,                              -- texto, imagen, plantilla, audio, video, documento, ubicacion
    mensaje TEXT,
    media_url TEXT,
    template_name TEXT,
    template_params JSONB,
    estado TEXT DEFAULT 'enviado',          -- enviado, entregado, leido, fallido
    wa_message_id TEXT,
    lead_id UUID,
    inquilino_id UUID,
    propietario_id UUID,
    leido BOOLEAN DEFAULT FALSE,
    fecha TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_wa_msg_user ON whatsapp_mensajes(user_id, fecha DESC);
CREATE INDEX IF NOT EXISTS idx_wa_msg_chat ON whatsapp_mensajes(chat_id);
