-- Créditos de IA mensuales + papelera de 30 días
-- Ejecutar a mano en Supabase (SQL Editor). Se puede ejecutar varias veces sin romper nada.

-- 1. Fecha de la última recarga mensual de créditos de IA (la usa CreditosMensualesService)
ALTER TABLE users ADD COLUMN IF NOT EXISTS ai_credits_renewed_at TIMESTAMPTZ;
-- Parte del saldo que viene de packs comprados: no caduca y se gasta después de la del plan
ALTER TABLE users ADD COLUMN IF NOT EXISTS ai_credits_comprados INTEGER DEFAULT 0;

-- 2. Papelera: copia de cada registro borrado, restaurable durante 30 días
CREATE TABLE IF NOT EXISTS trash (
    id BIGSERIAL PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID,
    table_name TEXT NOT NULL,
    record_id TEXT,
    actor_id TEXT,
    actor_name TEXT,
    snapshot JSONB NOT NULL,
    deleted_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ
);
-- Por si ya existía una tabla trash con menos columnas
ALTER TABLE trash ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE trash ADD COLUMN IF NOT EXISTS record_id TEXT;
ALTER TABLE trash ADD COLUMN IF NOT EXISTS actor_id TEXT;
ALTER TABLE trash ADD COLUMN IF NOT EXISTS actor_name TEXT;
ALTER TABLE trash ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE trash ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_trash_org ON trash(org_id, deleted_at DESC);
CREATE INDEX IF NOT EXISTS idx_trash_expira ON trash(expires_at);
-- Nadie accede desde fuera: solo el servidor (service_role, que salta el RLS)
ALTER TABLE trash ENABLE ROW LEVEL SECURITY;
