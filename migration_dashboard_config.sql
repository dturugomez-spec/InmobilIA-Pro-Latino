-- ============================================================================
-- InmobilIA Pro · Sprint 45: Dashboard configurable
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS dashboard_config JSONB;

SELECT 'Migration dashboard_config completed' AS status;
