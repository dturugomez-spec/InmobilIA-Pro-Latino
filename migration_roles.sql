-- ============================================================================
-- InmobilIA Pro · Migración multi-usuario y roles (Sprint 4)
-- ============================================================================
-- Uso:
--   1. SQL Editor de Supabase → New query → pega TODO esto → Run
--   2. Si tu cuenta admin ya existe, se autoasigna como organización propia
-- ============================================================================

-- ─── Columnas nuevas en users ───
ALTER TABLE users ADD COLUMN IF NOT EXISTS role            TEXT DEFAULT 'admin';
ALTER TABLE users ADD COLUMN IF NOT EXISTS name            TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS active          BOOLEAN DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone           TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS position        TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS invited_by      UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login      TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_org    ON users(organization_id);
CREATE INDEX IF NOT EXISTS idx_users_role   ON users(role);
CREATE INDEX IF NOT EXISTS idx_users_active ON users(active);

-- ─── Auto-asignar organization_id al propio id si está NULL ───
-- (Cada usuario sin organización pasa a ser su propia organización = admin)
UPDATE users
   SET organization_id = id, role = COALESCE(role, 'admin')
 WHERE organization_id IS NULL;

-- ─── Trigger para que nuevos usuarios sin organization se autoasignen ───
CREATE OR REPLACE FUNCTION users_default_org()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.organization_id IS NULL THEN
        NEW.organization_id := NEW.id;
    END IF;
    IF NEW.role IS NULL THEN
        NEW.role := 'admin';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_default_org ON users;
CREATE TRIGGER trg_users_default_org
    BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION users_default_org();

-- ─── Auditoría: created_by, updated_by en tablas principales ───
DO $$
DECLARE
    t TEXT;
    tables TEXT[] := ARRAY[
        'propiedades','inquilinos','contratos','transacciones','tareas',
        'mantenimiento','personal','eventos','documentos','reservas',
        'seguros','oportunidades','leads','email_templates','analisis_financieros'
    ];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS created_by UUID', t);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_by UUID', t);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()', t);
    END LOOP;
END $$;

-- ─── Verificación ───
SELECT 'users con role/org configurados:' AS info, count(*) FROM users WHERE role IS NOT NULL AND organization_id IS NOT NULL;
