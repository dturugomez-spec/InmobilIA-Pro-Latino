-- Sprint 101: SMS gateway
CREATE TABLE IF NOT EXISTS sms_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    proveedor TEXT DEFAULT 'twilio',         -- twilio, esendex, lleida
    account_sid TEXT,
    auth_token TEXT,
    numero_emisor TEXT,
    sender_id TEXT,                          -- ID alfanumérico (sólo Esendex/Lleida)
    activo BOOLEAN DEFAULT FALSE,
    credito NUMERIC(8,2),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sms_mensajes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    destinatario TEXT NOT NULL,
    mensaje TEXT NOT NULL,
    estado TEXT DEFAULT 'pendiente',         -- pendiente, enviado, entregado, fallido
    proveedor_id TEXT,
    coste NUMERIC(6,4),
    lead_id UUID,
    error_mensaje TEXT,
    enviado_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sms_user ON sms_mensajes(user_id, enviado_at DESC);
