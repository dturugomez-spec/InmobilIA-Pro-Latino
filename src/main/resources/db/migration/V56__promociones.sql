-- Sprint 98: Promociones de obra nueva
CREATE TABLE IF NOT EXISTS promociones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,

    nombre TEXT NOT NULL,
    slug TEXT,
    promotora TEXT,
    arquitecto TEXT,
    constructora TEXT,

    direccion TEXT,
    municipio TEXT, provincia TEXT, codigo_postal TEXT,
    lat NUMERIC(10,7), lng NUMERIC(10,7),

    tipo TEXT,                              -- residencial, comercial, mixto
    num_unidades INT,
    num_unidades_vendidas INT DEFAULT 0,

    fecha_inicio_obras DATE,
    fecha_entrega DATE,
    fase TEXT DEFAULT 'preventa',           -- preventa, obras, terminado, entregado

    precio_desde NUMERIC(12,2),
    precio_hasta NUMERIC(12,2),

    memoria_calidades TEXT,
    plano_general_url TEXT,
    render_principal_url TEXT,
    galeria_renders TEXT[],
    tour_360_url TEXT,
    video_url TEXT,

    seo_descripcion TEXT,
    landing_slug TEXT UNIQUE,
    publicada BOOLEAN DEFAULT FALSE,

    leads_capturados INT DEFAULT 0,
    visitas_count INT DEFAULT 0,

    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_promociones_user ON promociones(user_id);
CREATE INDEX IF NOT EXISTS idx_promociones_landing ON promociones(landing_slug);

-- Unidades de la promoción
CREATE TABLE IF NOT EXISTS promocion_unidades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promocion_id UUID NOT NULL REFERENCES promociones(id) ON DELETE CASCADE,
    propiedad_id UUID REFERENCES propiedades(id) ON DELETE SET NULL,
    bloque TEXT,
    planta INT,
    letra TEXT,
    portal TEXT,
    tipologia TEXT,                         -- 1d, 2d, 3d, atico, duplex
    num_habitaciones INT,
    num_banos INT,
    superficie_util NUMERIC(7,2),
    superficie_construida NUMERIC(7,2),
    terraza_m2 NUMERIC(6,2),
    orientacion TEXT,
    precio NUMERIC(12,2),
    plaza_garaje BOOLEAN DEFAULT FALSE,
    trastero BOOLEAN DEFAULT FALSE,
    estado TEXT DEFAULT 'disponible',       -- disponible, reservada, vendida
    cliente_id UUID,
    fecha_venta DATE,
    plano_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_unidades_promo ON promocion_unidades(promocion_id);
