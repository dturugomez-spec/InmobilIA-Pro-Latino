-- Sprint 97: Microsite público
CREATE TABLE IF NOT EXISTS microsites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    slug TEXT UNIQUE NOT NULL,                  -- nombre.inmobilia-pro.com o /m/{slug}
    nombre_publico TEXT,
    tagline TEXT,
    descripcion TEXT,

    hero_imagen TEXT,
    hero_titulo TEXT,
    hero_subtitulo TEXT,
    hero_cta_texto TEXT DEFAULT 'Ver propiedades',
    hero_cta_url TEXT,

    sobre_nosotros_titulo TEXT,
    sobre_nosotros_texto TEXT,
    sobre_nosotros_foto TEXT,

    servicios JSONB DEFAULT '[]',                -- [{titulo, descripcion, icono}]
    equipo JSONB DEFAULT '[]',                   -- [{nombre, cargo, foto, email}]
    testimonios_destacados UUID[],                -- referencias a reviews

    direccion TEXT,
    telefono TEXT,
    email TEXT,
    horario TEXT,
    google_maps_url TEXT,

    redes_facebook TEXT,
    redes_instagram TEXT,
    redes_linkedin TEXT,
    redes_twitter TEXT,
    redes_youtube TEXT,

    seo_title TEXT,
    seo_description TEXT,
    seo_keywords TEXT,
    seo_og_image TEXT,
    google_analytics_id TEXT,

    color_primario TEXT DEFAULT '#3b82f6',
    fuente TEXT DEFAULT 'Inter',
    publicado BOOLEAN DEFAULT FALSE,

    visitas_count INT DEFAULT 0,
    leads_capturados INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_microsite_slug ON microsites(slug);
