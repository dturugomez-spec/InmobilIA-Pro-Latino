-- Sprint diferenciación: Portal de Transparencia del Propietario
-- El propietario ve en tiempo real qué está pasando con su inmueble
-- (visitas, interés, feedback anónimo, ofertas) → renueva la exclusiva y no se siente ignorado.

ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_token TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_activo BOOLEAN DEFAULT TRUE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_vistas INT DEFAULT 0;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_ultima_vista TIMESTAMPTZ;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS portal_mensaje_agente TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS propietario_nombre TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS propietario_email TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_prop_portal_token ON propiedades(portal_token) WHERE portal_token IS NOT NULL;

-- Mensajes del propietario al agente desde el portal
CREATE TABLE IF NOT EXISTS portal_mensajes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE CASCADE,
    autor TEXT DEFAULT 'propietario',   -- propietario, agente
    mensaje TEXT NOT NULL,
    leido BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_portal_mensajes_prop ON portal_mensajes(propiedad_id, created_at DESC);
