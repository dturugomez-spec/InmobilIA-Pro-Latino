-- Socio fundador: las 10 primeras inmobiliarias que marque el dueño de la plataforma tienen un 20 % de
-- descuento en sus 4 primeros cobros (cupón de Stripe que crea la app al suscribirse). Desde España (V110).
ALTER TABLE users ADD COLUMN IF NOT EXISTS socio_fundador BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS socio_fundador_at TIMESTAMPTZ;
