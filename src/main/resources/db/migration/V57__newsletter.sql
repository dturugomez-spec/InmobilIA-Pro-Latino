-- Sprint 100: Newsletter + suscriptores
CREATE TABLE IF NOT EXISTS newsletter_suscriptores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    nombre TEXT,
    intereses TEXT[],                    -- ["alquiler", "venta", "obra_nueva"]
    zona TEXT,
    presupuesto_min NUMERIC(12,2),
    presupuesto_max NUMERIC(12,2),
    fuente TEXT,                          -- web, microsite, importado
    confirmado BOOLEAN DEFAULT FALSE,
    confirmacion_token TEXT UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    confirmado_at TIMESTAMPTZ,
    baja_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, email)
);
CREATE INDEX IF NOT EXISTS idx_news_user ON newsletter_suscriptores(user_id) WHERE baja_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_news_token ON newsletter_suscriptores(confirmacion_token);

CREATE TABLE IF NOT EXISTS newsletter_envios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    asunto TEXT,
    contenido_html TEXT,
    enviados INT DEFAULT 0,
    abiertos INT DEFAULT 0,
    fecha_envio TIMESTAMPTZ DEFAULT NOW()
);
