-- Sprint diferenciación: "Tinder de pisos" (marketplace de compradores)
-- En la misma web pública, un buscador gratuito tipo swipe para compradores finales.
-- Solo aparecen inmuebles de agencias que activan el add-on (paga +25% sobre su plan).
-- Cada "me gusta" con contacto se convierte en lead para la agencia → monetización.

-- Add-on del marketplace por agencia (+25% sobre el plan de pago)
ALTER TABLE users ADD COLUMN IF NOT EXISTS tinder_activo BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS tinder_swipes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_id UUID REFERENCES users(id) ON DELETE CASCADE,   -- dueño del inmueble
    propiedad_id UUID,

    accion TEXT,                    -- like, superlike, pass
    buyer_device TEXT,              -- id de dispositivo (localStorage) para deduplicar
    buyer_nombre TEXT,
    buyer_email TEXT,
    buyer_telefono TEXT,
    buyer_zona TEXT,

    lead_id UUID,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tinder_swipes_agency ON tinder_swipes(agency_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tinder_swipes_prop ON tinder_swipes(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_tinder_swipes_accion ON tinder_swipes(accion);
