-- Sprint extra: tokens de aprobación/rechazo desde email
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS approval_token TEXT;
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS token_expires_at TIMESTAMPTZ;
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS token_used_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_solicitudes_token ON solicitudes_cuenta(approval_token) WHERE token_used_at IS NULL;
