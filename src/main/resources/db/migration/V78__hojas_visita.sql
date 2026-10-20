-- Sprint diferenciación: Hoja de visita firmable (protege la comisión)
-- El visitante firma que la agencia le enseñó el inmueble → evita que el
-- comprador vaya directo al vendedor saltándose al agente. Documento imprimible.

CREATE TABLE IF NOT EXISTS hojas_visita (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID,
    visita_id UUID,

    visitante_nombre TEXT,
    visitante_dni TEXT,
    visitante_telefono TEXT,
    visitante_email TEXT,

    inmueble_texto TEXT,
    fecha_visita DATE,
    observaciones TEXT,

    estado TEXT DEFAULT 'pendiente',   -- pendiente, firmada
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_hojas_visita_user ON hojas_visita(user_id, created_at DESC);
