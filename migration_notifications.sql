-- ============================================================================
-- InmobilIA Pro · Migración notificaciones in-app
-- ============================================================================

CREATE TABLE IF NOT EXISTS notifications (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,        -- 'info', 'success', 'warning', 'error'
    icon        TEXT,                  -- emoji o icono Lucide
    title       TEXT NOT NULL,
    message     TEXT,
    link        TEXT,                  -- URL a la que llevar al hacer click
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user    ON notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_unread  ON notifications(user_id, read_at) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_notifications_created ON notifications(created_at DESC);

SELECT 'Migration notifications completed' AS status;
