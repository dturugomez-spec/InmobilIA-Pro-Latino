-- Sprint 58: Lead scoring + interacciones tracking
ALTER TABLE leads ADD COLUMN IF NOT EXISTS score INT DEFAULT 0;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS score_actualizado_at TIMESTAMPTZ;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS probabilidad_conversion NUMERIC(5,2);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS temperatura TEXT;       -- frio, tibio, caliente, hot
ALTER TABLE leads ADD COLUMN IF NOT EXISTS siguiente_accion TEXT;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS siguiente_accion_at TIMESTAMPTZ;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS score_desglose JSONB;   -- {"presupuesto": 25, "urgencia": 15, ...}

CREATE INDEX IF NOT EXISTS idx_leads_score ON leads(score DESC) WHERE estado != 'descartado';
CREATE INDEX IF NOT EXISTS idx_leads_temperatura ON leads(temperatura);

-- Tracking de cada interacción para alimentar el scoring
CREATE TABLE IF NOT EXISTS lead_interacciones (
    id BIGSERIAL PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    user_id UUID,

    tipo TEXT NOT NULL,                  -- email_abierto, email_click, web_visita, llamada, whatsapp, visita_realizada, formulario_extra
    canal TEXT,                           -- email, web, telefono, whatsapp, presencial
    detalle TEXT,
    valor_score INT DEFAULT 0,           -- puntos que aporta esta interacción

    propiedad_id UUID,                   -- si la interacción es sobre una propiedad concreta
    metadata JSONB,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lead_int_lead ON lead_interacciones(lead_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_lead_int_tipo ON lead_interacciones(tipo);

-- Historial de scoring (para gráfico evolución)
CREATE TABLE IF NOT EXISTS lead_score_historico (
    id BIGSERIAL PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    score INT,
    temperatura TEXT,
    desglose JSONB,
    motivo_cambio TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_score_hist_lead ON lead_score_historico(lead_id, created_at DESC);
