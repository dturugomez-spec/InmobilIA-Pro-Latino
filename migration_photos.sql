-- ============================================================================
-- InmobilIA Pro · Migración fotos de propiedades (Sprint 5)
-- ============================================================================
-- Uso: SQL Editor de Supabase → New query → pega → Run
-- ============================================================================

-- Foto destacada y galería
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS featured_photo TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS photos         JSONB DEFAULT '[]'::jsonb;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS reference      TEXT;        -- código tipo "REF-2024-001"
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS publicada      BOOLEAN DEFAULT false;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS visitas_count  INTEGER DEFAULT 0;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS consultas_count INTEGER DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_propiedades_publicada ON propiedades(publicada);
CREATE INDEX IF NOT EXISTS idx_propiedades_reference ON propiedades(reference);

-- Auto-generar reference si está vacío al insertar
CREATE OR REPLACE FUNCTION propiedades_default_ref()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.reference IS NULL OR NEW.reference = '' THEN
        NEW.reference := 'REF-' || to_char(now(), 'YYYY') || '-' || lpad(floor(random()*9999)::text, 4, '0');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_propiedades_default_ref ON propiedades;
CREATE TRIGGER trg_propiedades_default_ref
    BEFORE INSERT ON propiedades
    FOR EACH ROW EXECUTE FUNCTION propiedades_default_ref();

-- Verificación
SELECT 'propiedades con campos nuevos:' AS info,
       count(*) FILTER (WHERE featured_photo IS NOT NULL) AS con_foto,
       count(*) FILTER (WHERE reference IS NOT NULL) AS con_ref,
       count(*) AS total
  FROM propiedades;
