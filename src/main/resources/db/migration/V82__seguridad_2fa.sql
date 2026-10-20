-- Seguridad fase 1: verificación en dos pasos (2FA) + registro de actividad
-- Ejecutar a mano en Supabase (SQL Editor). Se puede ejecutar varias veces sin romper nada.

-- 1. Verificación en dos pasos por usuario
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_recovery_codes TEXT;   -- hashes SHA-256, separados por comas
-- En la fila del admin de cada inmobiliaria: obliga a todo su equipo a usar 2FA
ALTER TABLE users ADD COLUMN IF NOT EXISTS require_2fa BOOLEAN DEFAULT FALSE;

-- 2. Registro de actividad (por si la migración V43 no se llegó a ejecutar)
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID,
    actor_id UUID,
    actor_nombre TEXT,
    actor_rol TEXT,
    accion TEXT NOT NULL,
    tabla TEXT,
    registro_id TEXT,
    descripcion TEXT,
    valores_antes JSONB,
    valores_despues JSONB,
    campos_cambiados TEXT[],
    ip TEXT,
    user_agent TEXT,
    metodo TEXT,
    url TEXT,
    nivel TEXT DEFAULT 'info',
    categoria TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log(actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tabla ON audit_log(tabla, registro_id);
CREATE INDEX IF NOT EXISTS idx_audit_nivel ON audit_log(nivel, created_at DESC) WHERE nivel != 'info';
-- Nadie accede desde fuera: solo el servidor (service_role, que salta el RLS)
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;

-- 3. Borrar las contraseñas provisionales que se guardaban EN CLARO al aprobar cuentas
UPDATE solicitudes_cuenta SET password_temporal = NULL WHERE password_temporal IS NOT NULL;
