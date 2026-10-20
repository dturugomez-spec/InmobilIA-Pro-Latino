-- Sprint diferenciación: Seguimiento Automático Inteligente (Auto-nurture)
-- Cada lead entra en una cadencia que el Agente ejecuta en piloto automático:
-- bienvenida (día 0) → recordatorio (día 2) → aporta valor (día 5) → llamar (día 9).
-- Cada mensaje lo redacta la IA. La cadencia se para sola cuando el lead avanza.

-- Config por agencia
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_seguimiento BOOLEAN DEFAULT FALSE;
-- Modo: false = proponer en el Autopiloto (revisar); true = enviar automáticamente
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_seguimiento_envia BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS cadencia_inscripciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    lead_id UUID,
    lead_nombre TEXT,

    tipo TEXT DEFAULT 'comprador',   -- comprador | vendedor
    paso INT DEFAULT 0,              -- índice del próximo paso a ejecutar
    estado TEXT DEFAULT 'activa',    -- activa | completada | pausada | parada
    proximo_at TIMESTAMPTZ,          -- cuándo toca el próximo paso
    ultimo_at TIMESTAMPTZ,
    pasos_dados INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cadencia_user ON cadencia_inscripciones(user_id, estado);
CREATE INDEX IF NOT EXISTS idx_cadencia_lead ON cadencia_inscripciones(lead_id);
CREATE INDEX IF NOT EXISTS idx_cadencia_proximo ON cadencia_inscripciones(estado, proximo_at);
