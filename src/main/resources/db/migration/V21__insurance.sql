-- Sprint 50: Gestión avanzada de seguros
CREATE TABLE IF NOT EXISTS seguros (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    contrato_id UUID REFERENCES contratos(id) ON DELETE SET NULL,
    inquilino_id UUID REFERENCES inquilinos(id) ON DELETE SET NULL,

    -- Datos póliza
    tipo TEXT NOT NULL,         -- hogar, vida, impago_alquiler, comunidad, responsabilidad_civil
    aseguradora TEXT NOT NULL,
    numero_poliza TEXT,
    tomador TEXT,                -- nombre del tomador
    asegurado TEXT,              -- a quien protege
    beneficiario TEXT,

    -- Coberturas
    capital_asegurado NUMERIC(12,2),
    franquicia NUMERIC(10,2) DEFAULT 0,
    coberturas TEXT,             -- texto libre con coberturas
    exclusiones TEXT,

    -- Económico
    prima_anual NUMERIC(10,2),
    forma_pago TEXT DEFAULT 'anual',  -- anual, semestral, trimestral, mensual
    comision_agente NUMERIC(10,2),    -- comisión que recibe nuestra inmobiliaria

    -- Fechas
    fecha_emision DATE,
    fecha_inicio DATE NOT NULL,
    fecha_vencimiento DATE NOT NULL,
    renovacion_automatica BOOLEAN DEFAULT TRUE,
    fecha_cancelacion DATE,
    motivo_cancelacion TEXT,

    -- Documentación
    documento_url TEXT,           -- PDF póliza
    condiciones_particulares_url TEXT,

    -- Siniestros
    siniestros_count INT DEFAULT 0,

    estado TEXT DEFAULT 'activa',  -- activa, suspendida, cancelada, expirada
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seguros_user ON seguros(user_id);
CREATE INDEX IF NOT EXISTS idx_seguros_vencimiento ON seguros(fecha_vencimiento) WHERE estado = 'activa';
CREATE INDEX IF NOT EXISTS idx_seguros_propiedad ON seguros(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_seguros_contrato ON seguros(contrato_id);

-- Tabla de siniestros
CREATE TABLE IF NOT EXISTS siniestros (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seguro_id UUID NOT NULL REFERENCES seguros(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    fecha_siniestro DATE NOT NULL,
    fecha_declaracion DATE,
    numero_expediente TEXT,
    tipo_dano TEXT,                  -- agua, incendio, robo, daños propios, etc.
    descripcion TEXT,
    importe_reclamado NUMERIC(10,2),
    importe_indemnizado NUMERIC(10,2),
    estado TEXT DEFAULT 'abierto',   -- abierto, en_tramite, pagado, rechazado, cerrado
    perito_asignado TEXT,
    fecha_resolucion DATE,
    documentos JSONB DEFAULT '[]'::jsonb,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_siniestros_seguro ON siniestros(seguro_id);
CREATE INDEX IF NOT EXISTS idx_siniestros_estado ON siniestros(estado);
