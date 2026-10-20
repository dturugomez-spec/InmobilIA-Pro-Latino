-- Sprint 112: Solicitudes de cuenta (modelo aprobación manual)
CREATE TABLE IF NOT EXISTS solicitudes_cuenta (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre TEXT NOT NULL,
    empresa TEXT NOT NULL,
    email TEXT NOT NULL,
    telefono TEXT NOT NULL,
    plan TEXT DEFAULT 'pro',                -- free, pro, agency, enterprise
    num_propiedades TEXT,
    mensaje TEXT,
    origen TEXT DEFAULT 'landing',          -- landing, microsite, referido
    ip TEXT,
    user_agent TEXT,

    estado TEXT DEFAULT 'pendiente',        -- pendiente, aprobada, rechazada, contactada
    aprobada_por UUID REFERENCES users(id) ON DELETE SET NULL,
    aprobada_at TIMESTAMPTZ,
    user_creado_id UUID REFERENCES users(id) ON DELETE SET NULL,
    motivo_rechazo TEXT,
    notas_admin TEXT,

    -- Para email automático
    password_temporal TEXT,
    email_enviado_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_solicitudes_estado ON solicitudes_cuenta(estado, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_solicitudes_email ON solicitudes_cuenta(email);
