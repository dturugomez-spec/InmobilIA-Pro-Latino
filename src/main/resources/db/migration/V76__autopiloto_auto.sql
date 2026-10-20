-- Autopiloto proactivo: generar propuestas automáticamente cada mañana
ALTER TABLE users ADD COLUMN IF NOT EXISTS autopiloto_auto BOOLEAN DEFAULT FALSE;
