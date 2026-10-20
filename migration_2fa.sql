-- ============================================================================
-- InmobilIA Pro · Sprint 19: 2FA y seguridad enterprise
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret      TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled     BOOLEAN DEFAULT false;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_backup_codes TEXT[];
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_logins      INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until       TIMESTAMPTZ;

-- Whitelist de IPs por organización (opcional)
CREATE TABLE IF NOT EXISTS ip_whitelist (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID NOT NULL,
    ip_cidr     TEXT NOT NULL,         -- '192.168.1.0/24' o '10.0.0.5'
    description TEXT,
    enabled     BOOLEAN DEFAULT true,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ip_whitelist_org ON ip_whitelist(org_id) WHERE enabled = true;

SELECT 'Migration 2FA completed' AS status;
