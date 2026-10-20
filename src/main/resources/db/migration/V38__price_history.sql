-- Sprint 67: Histórico de precios de propiedad
CREATE TABLE IF NOT EXISTS precio_historico (
    id BIGSERIAL PRIMARY KEY,
    propiedad_id UUID NOT NULL REFERENCES propiedades(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    precio_anterior NUMERIC(12,2),
    precio_nuevo NUMERIC(12,2) NOT NULL,
    diferencia NUMERIC(12,2),
    diferencia_pct NUMERIC(6,2),
    motivo TEXT,                          -- bajada_negociacion, reforma, mercado, otros
    cambiado_por UUID,
    notas TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_precio_hist_prop ON precio_historico(propiedad_id, created_at DESC);

-- Función para guardar histórico automáticamente
CREATE OR REPLACE FUNCTION track_price_change()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.precio IS DISTINCT FROM NEW.precio AND NEW.precio IS NOT NULL THEN
        INSERT INTO precio_historico (propiedad_id, user_id, precio_anterior, precio_nuevo, diferencia, diferencia_pct)
        VALUES (
            NEW.id,
            NEW.user_id,
            OLD.precio,
            NEW.precio,
            NEW.precio - COALESCE(OLD.precio, 0),
            CASE WHEN OLD.precio > 0 THEN ((NEW.precio - OLD.precio) / OLD.precio * 100) ELSE NULL END
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_track_price ON propiedades;
CREATE TRIGGER tg_track_price
    AFTER UPDATE ON propiedades
    FOR EACH ROW EXECUTE FUNCTION track_price_change();
