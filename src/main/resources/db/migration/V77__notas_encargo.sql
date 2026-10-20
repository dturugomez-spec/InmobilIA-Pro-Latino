-- Sprint diferenciación: Nota de Encargo de venta (cierra la captación)
-- Genera el documento del encargo/exclusiva con los datos reales (agencia,
-- propietario, inmueble, comisión, duración) listo para imprimir y firmar.
-- Es una plantilla estándar editable, NO asesoramiento jurídico.

CREATE TABLE IF NOT EXISTS notas_encargo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID,

    -- Propietario
    propietario_nombre TEXT,
    propietario_dni TEXT,
    propietario_email TEXT,
    propietario_telefono TEXT,
    propietario_direccion TEXT,

    -- Inmueble (snapshot)
    inmueble_texto TEXT,
    precio NUMERIC(12,2),

    -- Condiciones del encargo
    comision_pct NUMERIC(5,2) DEFAULT 3,
    exclusiva BOOLEAN DEFAULT TRUE,
    duracion_meses INT DEFAULT 6,
    observaciones TEXT,

    estado TEXT DEFAULT 'borrador',   -- borrador, firmado
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notas_encargo_user ON notas_encargo(user_id, created_at DESC);
