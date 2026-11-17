-- Red de colaboradores de cada inmobiliaria (certificado energético, fotos, reformas, limpieza…)
-- y los encargos que se les piden desde la ficha del inmueble.
-- Ejecutar a mano en Supabase (SQL Editor). Se puede ejecutar varias veces.

CREATE TABLE IF NOT EXISTS colaboradores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    nombre TEXT NOT NULL,
    servicio TEXT NOT NULL,                      -- cee | fotos | plano | limpieza | reformas | cerrajero | mudanzas | tasacion | abogado | otro
    email TEXT,
    telefono TEXT,
    zona TEXT,
    precio_orientativo TEXT,
    notas TEXT,
    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_colaboradores_user ON colaboradores(user_id);

CREATE TABLE IF NOT EXISTS encargos_colaborador (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    colaborador_id UUID NOT NULL,
    propiedad_id UUID,
    servicio TEXT,
    descripcion TEXT,
    direccion TEXT,                              -- dirección del inmueble que ve el colaborador
    contacto TEXT,                               -- con quién queda para entrar
    fecha_deseada DATE,
    estado TEXT DEFAULT 'enviado',               -- enviado | aceptado | rechazado | hecho | cancelado
    token TEXT,                                  -- enlace del colaborador
    token_caduca TIMESTAMPTZ,
    precio NUMERIC(12,2),                        -- precio que propone el colaborador
    fecha_prevista DATE,
    respuesta TEXT,
    entrega_ruta TEXT,                           -- archivo entregado (certificado, fotos en ZIP, presupuesto…)
    entrega_nombre TEXT,
    entrega_datos JSONB,                         -- datos leídos con IA (certificado energético)
    valoracion INTEGER,                          -- 1 a 5
    valoracion_nota TEXT,
    created_by UUID,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    respondido_at TIMESTAMPTZ,
    hecho_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_encargos_colaborador_user ON encargos_colaborador(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_encargos_colaborador_token ON encargos_colaborador(token) WHERE token IS NOT NULL;

ALTER TABLE colaboradores ENABLE ROW LEVEL SECURITY;
ALTER TABLE encargos_colaborador ENABLE ROW LEVEL SECURITY;

NOTIFY pgrst, 'reload schema';
