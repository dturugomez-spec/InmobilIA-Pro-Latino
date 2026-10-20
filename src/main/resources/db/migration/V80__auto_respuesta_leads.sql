-- Auto-respuesta a leads (speed-to-lead): interruptor por agencia, OFF por defecto.
-- Cuando está activo, un lead nuevo del formulario público recibe al instante un
-- email personalizado con la propiedad y alternativas. Nunca se envía a direcciones
-- de prueba (@example.com, etc.) para proteger la reputación del dominio.
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_respuesta_leads BOOLEAN DEFAULT FALSE;
