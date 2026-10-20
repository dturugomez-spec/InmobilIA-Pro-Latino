-- Sprint 73: Auditoría avanzada de todas las acciones
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID,                          -- usuario afectado / propietario del dato
    actor_id UUID,                         -- quien hizo la acción
    actor_nombre TEXT,
    actor_rol TEXT,

    accion TEXT NOT NULL,                  -- crear, actualizar, eliminar, leer, login, exportar, etc.
    tabla TEXT,                            -- propiedades, leads, contratos...
    registro_id TEXT,                      -- ID del registro afectado
    descripcion TEXT,

    -- Datos del cambio
    valores_antes JSONB,
    valores_despues JSONB,
    campos_cambiados TEXT[],

    -- Contexto
    ip TEXT,
    user_agent TEXT,
    metodo TEXT,                           -- GET, POST, PUT, DELETE
    url TEXT,

    -- Severidad / categoría
    nivel TEXT DEFAULT 'info',             -- info, warning, critical, security
    categoria TEXT,                        -- datos, seguridad, financiero, gdpr, exportacion

    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tabla ON audit_log(tabla, registro_id);
CREATE INDEX IF NOT EXISTS idx_audit_nivel ON audit_log(nivel, created_at DESC) WHERE nivel != 'info';
