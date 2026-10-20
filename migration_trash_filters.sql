-- ============================================================================
-- InmobilIA Pro · Sprint 23: Papelera + filtros guardados
-- ============================================================================

-- Papelera: cuando se borra un registro, va aquí en lugar de desaparecer
CREATE TABLE IF NOT EXISTS trash (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID NOT NULL,
    user_id     UUID,
    table_name  TEXT NOT NULL,
    original_id TEXT NOT NULL,
    snapshot    JSONB NOT NULL,
    actor_name  TEXT,
    deleted_at  TIMESTAMPTZ DEFAULT NOW(),
    expires_at  TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '30 days')
);
CREATE INDEX IF NOT EXISTS idx_trash_org     ON trash(org_id);
CREATE INDEX IF NOT EXISTS idx_trash_table   ON trash(table_name);
CREATE INDEX IF NOT EXISTS idx_trash_expires ON trash(expires_at);

-- Filtros guardados por usuario
CREATE TABLE IF NOT EXISTS saved_filters (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    table_name  TEXT NOT NULL,
    name        TEXT NOT NULL,
    filters     JSONB NOT NULL,
    is_default  BOOLEAN DEFAULT false,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_filters_user_table ON saved_filters(user_id, table_name);

-- Tags / etiquetas libres
CREATE TABLE IF NOT EXISTS tags (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id      UUID NOT NULL,
    name        TEXT NOT NULL,
    color       TEXT DEFAULT '#3b82f6',
    icon        TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tags_org_name ON tags(org_id, name);

CREATE TABLE IF NOT EXISTS entity_tags (
    tag_id      UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL,
    entity_id   TEXT NOT NULL,
    PRIMARY KEY (tag_id, entity_type, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_entity_tags_lookup ON entity_tags(entity_type, entity_id);

SELECT 'Migration trash + filters + tags completed' AS status;
