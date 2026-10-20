-- Asignación de leads a agentes (habilita reparto y panel de rendimiento del equipo)
ALTER TABLE leads ADD COLUMN IF NOT EXISTS asignado_a UUID;
CREATE INDEX IF NOT EXISTS idx_leads_asignado ON leads(asignado_a);
