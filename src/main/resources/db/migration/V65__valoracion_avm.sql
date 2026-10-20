-- Sprint diferenciación: Valoración instantánea (AVM) como imán de captación
CREATE TABLE IF NOT EXISTS valoraciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    -- Datos del inmueble valorado
    tipo TEXT,                       -- piso, casa, atico, chalet, local...
    municipio TEXT,
    provincia TEXT,
    zona TEXT,
    metros NUMERIC(8,2),
    habitaciones INT,
    banos INT,
    estado TEXT,                     -- nuevo, reformado, buen_estado, a_reformar
    planta INT,
    ascensor BOOLEAN,
    garaje BOOLEAN,
    terraza BOOLEAN,
    ano_construccion INT,

    -- Resultado de la valoración
    precio_estimado NUMERIC(12,2),
    precio_min NUMERIC(12,2),
    precio_max NUMERIC(12,2),
    eur_m2 NUMERIC(8,2),
    confianza TEXT,                  -- alta, media, baja
    metodo TEXT,                     -- avm_tabla, avm_ia, comparables

    -- Datos del propietario (lead capturado)
    propietario_nombre TEXT,
    propietario_email TEXT,
    propietario_telefono TEXT,
    quiere_vender TEXT,              -- ahora, 3-6m, explorando, no
    lead_id UUID,

    origen TEXT DEFAULT 'valoracion_publica',
    ip TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_valoraciones_user ON valoraciones(user_id, created_at DESC);

-- Token público por agencia para compartir/embeber la valoración
ALTER TABLE users ADD COLUMN IF NOT EXISTS valoracion_token TEXT;
