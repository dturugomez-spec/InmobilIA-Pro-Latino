-- Marketplace: modo "match" (la agencia también acepta al comprador)
-- Cuando el agente hace match, el comprador recibe un email tipo "¡es un match!".
ALTER TABLE tinder_swipes ADD COLUMN IF NOT EXISTS matched BOOLEAN DEFAULT FALSE;
ALTER TABLE tinder_swipes ADD COLUMN IF NOT EXISTS matched_at TIMESTAMPTZ;
