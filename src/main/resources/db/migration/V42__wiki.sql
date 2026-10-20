-- Sprint 72: Wiki / Knowledge base interna
CREATE TABLE IF NOT EXISTS wiki_categorias (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    nombre TEXT NOT NULL,
    descripcion TEXT,
    icono TEXT DEFAULT '📁',
    color TEXT DEFAULT '#3b82f6',
    orden INT DEFAULT 0,
    parent_id UUID REFERENCES wiki_categorias(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wiki_cat_user ON wiki_categorias(user_id);

CREATE TABLE IF NOT EXISTS wiki_articulos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    categoria_id UUID REFERENCES wiki_categorias(id) ON DELETE SET NULL,
    autor_id UUID REFERENCES users(id) ON DELETE SET NULL,

    slug TEXT NOT NULL,
    titulo TEXT NOT NULL,
    resumen TEXT,
    contenido_markdown TEXT,
    contenido_html TEXT,

    publicado BOOLEAN DEFAULT TRUE,
    visible_publico BOOLEAN DEFAULT FALSE,  -- accesible sin login

    tags TEXT[],
    busqueda_tsv TSVECTOR,                 -- full text search

    veces_leido INT DEFAULT 0,
    util_count INT DEFAULT 0,
    no_util_count INT DEFAULT 0,

    fijado BOOLEAN DEFAULT FALSE,
    destacado BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, slug)
);

CREATE INDEX IF NOT EXISTS idx_wiki_art_user ON wiki_articulos(user_id);
CREATE INDEX IF NOT EXISTS idx_wiki_art_cat ON wiki_articulos(categoria_id);
CREATE INDEX IF NOT EXISTS idx_wiki_art_search ON wiki_articulos USING GIN(busqueda_tsv);

CREATE OR REPLACE FUNCTION wiki_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.busqueda_tsv := to_tsvector('spanish',
        coalesce(NEW.titulo,'') || ' ' || coalesce(NEW.resumen,'') || ' ' || coalesce(NEW.contenido_markdown,''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_wiki_tsv ON wiki_articulos;
CREATE TRIGGER tg_wiki_tsv BEFORE INSERT OR UPDATE ON wiki_articulos
    FOR EACH ROW EXECUTE FUNCTION wiki_tsv_update();
