-- Respuesta inmediata a los contactos (speed-to-lead), activada desde el alta y medida.
-- Ejecutar a mano en Supabase (SQL Editor). Se puede ejecutar varias veces.

-- Cuándo y cómo se contestó por primera vez a cada lead (para medir el tiempo de respuesta)
ALTER TABLE leads ADD COLUMN IF NOT EXISTS primera_respuesta_at TIMESTAMPTZ;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS primera_respuesta_via TEXT;   -- auto_email | agente | etapa | seguimiento

-- La respuesta automática pasa a estar encendida por defecto (se puede apagar en Respuesta a leads)
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_respuesta_leads BOOLEAN DEFAULT TRUE;
ALTER TABLE users ALTER COLUMN auto_respuesta_leads SET DEFAULT TRUE;
-- Cuándo la cambió la propia inmobiliaria (si nunca la tocó, se enciende ahora)
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_respuesta_cambiada_at TIMESTAMPTZ;
UPDATE users SET auto_respuesta_leads = TRUE
 WHERE organization_id IS NULL AND auto_respuesta_cambiada_at IS NULL AND auto_respuesta_leads IS DISTINCT FROM TRUE;

CREATE INDEX IF NOT EXISTS idx_leads_user_created ON leads(user_id, created_at DESC);

NOTIFY pgrst, 'reload schema';
