-- Sprint 57: Motor de automatizaciones tipo Zapier
CREATE TABLE IF NOT EXISTS workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    descripcion TEXT,
    activo BOOLEAN DEFAULT TRUE,

    -- Trigger
    trigger_evento TEXT NOT NULL,        -- lead_creado, visita_programada, factura_impagada, contrato_firmado, propiedad_creada, etc.
    trigger_condiciones JSONB,           -- {"campo": "presupuesto_max", "operador": ">", "valor": 200000}

    -- Acciones (array ordenado)
    acciones JSONB NOT NULL,             -- [{"tipo": "send_email", "config": {...}}, {"tipo": "create_task", ...}]

    -- Ejecución
    ejecutar_inmediato BOOLEAN DEFAULT TRUE,
    delay_minutos INT DEFAULT 0,          -- esperar X min después del trigger
    solo_horario_laboral BOOLEAN DEFAULT FALSE,

    -- Estadísticas
    veces_ejecutado INT DEFAULT 0,
    veces_exito INT DEFAULT 0,
    veces_error INT DEFAULT 0,
    ultima_ejecucion TIMESTAMPTZ,
    ultimo_error TEXT,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflows_user ON workflows(user_id);
CREATE INDEX IF NOT EXISTS idx_workflows_trigger ON workflows(trigger_evento) WHERE activo = true;

-- Log de ejecuciones
CREATE TABLE IF NOT EXISTS workflow_ejecuciones (
    id BIGSERIAL PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    user_id UUID,
    trigger_evento TEXT,
    payload JSONB,                       -- datos del evento que disparó

    estado TEXT DEFAULT 'pendiente',     -- pendiente, ejecutando, exitoso, error
    iniciado_at TIMESTAMPTZ DEFAULT NOW(),
    completado_at TIMESTAMPTZ,
    duracion_ms INT,

    acciones_ejecutadas INT DEFAULT 0,
    acciones_total INT DEFAULT 0,
    resultado JSONB,                     -- log de cada acción
    error_mensaje TEXT,

    -- Para ejecución diferida
    ejecutar_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_workflow_exec_workflow ON workflow_ejecuciones(workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_exec_pending ON workflow_ejecuciones(ejecutar_at) WHERE estado = 'pendiente';
CREATE INDEX IF NOT EXISTS idx_workflow_exec_estado ON workflow_ejecuciones(estado);

-- Tareas creadas por workflows (también gestionables manualmente)
CREATE TABLE IF NOT EXISTS tareas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    asignado_a UUID REFERENCES users(id) ON DELETE SET NULL,
    workflow_origen_id UUID REFERENCES workflows(id) ON DELETE SET NULL,

    titulo TEXT NOT NULL,
    descripcion TEXT,
    prioridad TEXT DEFAULT 'media',      -- baja, media, alta, urgente
    estado TEXT DEFAULT 'pendiente',     -- pendiente, en_curso, completada, cancelada
    fecha_limite TIMESTAMPTZ,
    completada_at TIMESTAMPTZ,

    -- Referencias opcionales
    lead_id UUID,
    propiedad_id UUID,
    contrato_id UUID,
    visita_id UUID,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tareas_user ON tareas(user_id);
CREATE INDEX IF NOT EXISTS idx_tareas_asignado ON tareas(asignado_a);
CREATE INDEX IF NOT EXISTS idx_tareas_estado ON tareas(estado);
CREATE INDEX IF NOT EXISTS idx_tareas_fecha_limite ON tareas(fecha_limite) WHERE estado = 'pendiente';
