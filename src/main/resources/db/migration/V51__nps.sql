-- Sprint 91: NPS surveys
CREATE TABLE IF NOT EXISTS nps_respuestas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    cliente_nombre TEXT,
    cliente_email TEXT,
    contrato_id UUID, visita_id UUID,
    puntuacion INT CHECK (puntuacion BETWEEN 0 AND 10),
    comentario TEXT,
    categoria TEXT,                       -- detractor (0-6), pasivo (7-8), promotor (9-10)
    token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    solicitada_at TIMESTAMPTZ DEFAULT NOW(),
    respondida_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_nps_user ON nps_respuestas(user_id);
CREATE INDEX IF NOT EXISTS idx_nps_token ON nps_respuestas(token);
