-- Sprint 63: Co-broking entre agencias (MLS)

CREATE TABLE IF NOT EXISTS cobroking_red (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    agencia_socia_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre_red TEXT,
    comision_default_pct NUMERIC(5,2) DEFAULT 50,
    estado TEXT DEFAULT 'pendiente',  -- pendiente, activa, suspendida, terminada
    invitacion_token TEXT UNIQUE,
    invitacion_email TEXT,
    aceptada_at TIMESTAMPTZ,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cobr_user ON cobroking_red(user_id);
CREATE INDEX IF NOT EXISTS idx_cobr_socia ON cobroking_red(agencia_socia_id);

-- Propiedades compartidas
CREATE TABLE IF NOT EXISTS cobroking_propiedades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,
    user_propietario_id UUID NOT NULL,    -- agencia dueña del listing
    user_compartido_id UUID,               -- agencia con quien se comparte (NULL = abierto a toda la red)
    comision_oferente_pct NUMERIC(5,2) DEFAULT 50,
    comision_captador_pct NUMERIC(5,2) DEFAULT 50,
    condiciones TEXT,
    visible_solo_red BOOLEAN DEFAULT TRUE,
    activa BOOLEAN DEFAULT TRUE,
    fecha_inicio DATE DEFAULT CURRENT_DATE,
    fecha_fin DATE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cobr_prop ON cobroking_propiedades(propiedad_id);
CREATE INDEX IF NOT EXISTS idx_cobr_prop_user ON cobroking_propiedades(user_propietario_id) WHERE activa = true;

-- Operaciones de co-broking (registro de quién aportó qué)
CREATE TABLE IF NOT EXISTS cobroking_operaciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    propiedad_id UUID NOT NULL,
    user_captador_id UUID NOT NULL,        -- quien tiene el listing
    user_oferente_id UUID NOT NULL,        -- quien aportó el comprador
    lead_id UUID,
    visita_id UUID,
    oferta_id UUID,
    importe_operacion NUMERIC(12,2),
    comision_total NUMERIC(10,2),
    comision_captador NUMERIC(10,2),
    comision_oferente NUMERIC(10,2),
    estado TEXT DEFAULT 'en_curso',        -- en_curso, cerrada, cancelada
    fecha_cierre DATE,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cobr_op_captador ON cobroking_operaciones(user_captador_id);
CREATE INDEX IF NOT EXISTS idx_cobr_op_oferente ON cobroking_operaciones(user_oferente_id);
