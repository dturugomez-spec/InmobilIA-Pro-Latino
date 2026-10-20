-- Sprint 69: Reviews y testimonios públicos
CREATE TABLE IF NOT EXISTS reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    autor_nombre TEXT NOT NULL,
    autor_email TEXT,
    autor_foto_url TEXT,
    autor_rol TEXT,                       -- inquilino, propietario, comprador, vendedor

    puntuacion INT NOT NULL CHECK (puntuacion BETWEEN 1 AND 5),
    titulo TEXT,
    comentario TEXT NOT NULL,

    propiedad_id UUID,
    agente_id UUID REFERENCES users(id) ON DELETE SET NULL,

    aprobada BOOLEAN DEFAULT FALSE,        -- moderación
    destacada BOOLEAN DEFAULT FALSE,        -- aparece en landing
    publica BOOLEAN DEFAULT TRUE,
    respuesta TEXT,                        -- respuesta del agente
    respuesta_at TIMESTAMPTZ,

    fuente TEXT DEFAULT 'directa',         -- directa, google, idealista, fotocasa
    fuente_url TEXT,

    -- Pedido vía email tras visita/contrato
    solicitud_token TEXT UNIQUE,
    solicitada_at TIMESTAMPTZ,
    respondida_at TIMESTAMPTZ DEFAULT NOW(),

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reviews_user ON reviews(user_id) WHERE aprobada = true;
CREATE INDEX IF NOT EXISTS idx_reviews_agente ON reviews(agente_id);
CREATE INDEX IF NOT EXISTS idx_reviews_token ON reviews(solicitud_token);
