-- ============================================================================
-- InmobilIA Pro · Sprint 12: Comentarios, timeline, adjuntos
-- ============================================================================

-- Comentarios en cualquier entidad
CREATE TABLE IF NOT EXISTS comments (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id      UUID NOT NULL,
    entity_type TEXT NOT NULL,        -- 'inquilino', 'propiedad', 'lead', 'contrato', etc.
    entity_id   TEXT NOT NULL,
    body        TEXT NOT NULL,
    author_name TEXT,
    edited_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_comments_entity ON comments(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_comments_org    ON comments(org_id, created_at DESC);

-- Timeline de actividad
CREATE TABLE IF NOT EXISTS activity_log (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID,
    org_id      UUID NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   TEXT NOT NULL,
    action      TEXT NOT NULL,        -- 'created', 'updated', 'deleted', 'commented', 'attached'
    field       TEXT,                  -- nombre del campo modificado
    old_value   TEXT,
    new_value   TEXT,
    actor_name  TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_activity_entity ON activity_log(entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_org    ON activity_log(org_id, created_at DESC);

-- Adjuntos (URLs externos o uploads futuros)
CREATE TABLE IF NOT EXISTS attachments (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id      UUID NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   TEXT NOT NULL,
    name        TEXT NOT NULL,
    url         TEXT NOT NULL,
    file_type   TEXT,                  -- pdf, image, doc, sheet, other
    size_kb     INTEGER,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_attachments_entity ON attachments(entity_type, entity_id);

-- Plantillas reutilizables
CREATE TABLE IF NOT EXISTS templates (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,         -- 'email', 'contract_clause', 'description'
    name        TEXT NOT NULL,
    subject     TEXT,
    body        TEXT NOT NULL,
    variables   TEXT[],                -- ['inquilino', 'renta', 'mes']
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_templates_user ON templates(user_id, type);

SELECT 'Migration productivity completed' AS status;
