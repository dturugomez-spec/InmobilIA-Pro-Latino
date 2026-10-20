-- ============================================================================
-- InmobilIA Pro · Migración seguridad (Sprint 9)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

-- ─── Verificación de email ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified       BOOLEAN DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verify_token   TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verify_expires TIMESTAMPTZ;

-- ─── Recuperación de contraseña ───
CREATE TABLE IF NOT EXISTS password_resets (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email       TEXT NOT NULL,
    token       TEXT UNIQUE NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    ip_address  TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_pwd_resets_token ON password_resets(token);
CREATE INDEX IF NOT EXISTS idx_pwd_resets_user  ON password_resets(user_id);

-- ─── Auditoría: log de eventos importantes ───
CREATE TABLE IF NOT EXISTS audit_log (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    org_id       UUID,
    action       TEXT NOT NULL,        -- 'login', 'logout', 'create', 'update', 'delete', 'invite', 'role_change', etc.
    target_type  TEXT,                  -- 'user', 'invoice', 'property', etc.
    target_id    TEXT,
    metadata     JSONB,                 -- detalles adicionales
    ip_address   TEXT,
    user_agent   TEXT,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_org  ON audit_log(org_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action);

-- ─── Cookies / consent (RGPD) ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_accepted_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS privacy_accepted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS marketing_consent  BOOLEAN DEFAULT false;

-- ─── Soft delete (no borrar usuario, solo marcarlo) ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at  TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_deleted ON users(deleted_at) WHERE deleted_at IS NULL;

SELECT 'Migration security completed' AS status;
