-- Sprint 56: Campañas de marketing masivas
CREATE TABLE IF NOT EXISTS campanas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    descripcion TEXT,
    canal TEXT NOT NULL DEFAULT 'email', -- email, whatsapp, sms, mixto

    -- Contenido
    asunto TEXT,                          -- email subject
    contenido TEXT NOT NULL,              -- HTML email o texto WhatsApp
    from_name TEXT,
    from_email TEXT,
    preheader TEXT,                       -- texto preview email

    -- A/B testing
    ab_test BOOLEAN DEFAULT FALSE,
    asunto_b TEXT,
    contenido_b TEXT,
    ab_split_pct INT DEFAULT 50,         -- % que recibe versión A

    -- Segmentación
    segmento_tipo TEXT NOT NULL,         -- todos_leads, todos_inquilinos, todos_propietarios, lista_custom, segmentado
    segmento_filtros JSONB,              -- {"tipo": "comprador", "presupuesto_min": 200000, ...}
    lista_destinatarios JSONB,           -- ["email1", "email2"] si manual

    -- Programación
    estado TEXT DEFAULT 'borrador',      -- borrador, programada, enviando, enviada, pausada, cancelada
    enviar_at TIMESTAMPTZ,               -- cuando empezar a enviar
    enviado_at TIMESTAMPTZ,
    completado_at TIMESTAMPTZ,

    -- Métricas (agregadas)
    destinatarios_total INT DEFAULT 0,
    enviados INT DEFAULT 0,
    fallidos INT DEFAULT 0,
    abiertos INT DEFAULT 0,
    clicks INT DEFAULT 0,
    rebotes INT DEFAULT 0,
    bajas INT DEFAULT 0,
    conversiones INT DEFAULT 0,

    -- Configuración
    incluir_unsubscribe BOOLEAN DEFAULT TRUE,
    tracking_aperturas BOOLEAN DEFAULT TRUE,
    tracking_clicks BOOLEAN DEFAULT TRUE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,  -- si la campaña promociona una propiedad

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_campanas_user ON campanas(user_id);
CREATE INDEX IF NOT EXISTS idx_campanas_estado ON campanas(estado);
CREATE INDEX IF NOT EXISTS idx_campanas_enviar ON campanas(enviar_at) WHERE estado = 'programada';

-- Envíos individuales
CREATE TABLE IF NOT EXISTS campana_envios (
    id BIGSERIAL PRIMARY KEY,
    campana_id UUID NOT NULL REFERENCES campanas(id) ON DELETE CASCADE,
    user_id UUID,

    destinatario_email TEXT,
    destinatario_telefono TEXT,
    destinatario_nombre TEXT,
    lead_id UUID,
    inquilino_id UUID,
    variante TEXT DEFAULT 'A',           -- A o B (para A/B test)

    tracking_token TEXT UNIQUE NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),

    estado TEXT DEFAULT 'pendiente',    -- pendiente, enviado, abierto, click, rebote, baja, fallido
    enviado_at TIMESTAMPTZ,
    primer_abierto_at TIMESTAMPTZ,
    primer_click_at TIMESTAMPTZ,
    aperturas_count INT DEFAULT 0,
    clicks_count INT DEFAULT 0,
    error_mensaje TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_envios_campana ON campana_envios(campana_id);
CREATE INDEX IF NOT EXISTS idx_envios_token ON campana_envios(tracking_token);
CREATE INDEX IF NOT EXISTS idx_envios_estado ON campana_envios(estado);

-- Plantillas reutilizables
CREATE TABLE IF NOT EXISTS plantillas_email (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    categoria TEXT,                      -- bienvenida, propiedad_nueva, recordatorio, felicitacion, encuesta
    asunto TEXT,
    contenido TEXT NOT NULL,
    variables_usadas TEXT[],             -- ["nombre", "propiedad_direccion", ...]
    es_publica BOOLEAN DEFAULT FALSE,    -- true = compartida con otros usuarios del workspace
    veces_usada INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_plantillas_user ON plantillas_email(user_id);

-- Bajas (unsubscribes)
CREATE TABLE IF NOT EXISTS bajas_marketing (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    motivo TEXT,
    campana_origen_id UUID,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (user_id, email)
);
