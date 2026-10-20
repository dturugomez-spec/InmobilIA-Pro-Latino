-- Sprint 82: Whitelabel
CREATE TABLE IF NOT EXISTS whitelabel_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,

    nombre_marca TEXT,
    subdominio TEXT UNIQUE,                -- agencia.inmobilia-pro.com

    logo_url TEXT,
    logo_dark_url TEXT,
    favicon_url TEXT,
    color_primario TEXT DEFAULT '#3b82f6',
    color_secundario TEXT DEFAULT '#1e40af',
    color_acento TEXT DEFAULT '#10b981',

    email_remitente TEXT,
    email_pie TEXT,
    soporte_telefono TEXT,
    soporte_email TEXT,

    sitio_web TEXT,
    redes_sociales JSONB,

    css_personalizado TEXT,
    js_personalizado TEXT,

    activo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wl_subdom ON whitelabel_config(subdominio);
