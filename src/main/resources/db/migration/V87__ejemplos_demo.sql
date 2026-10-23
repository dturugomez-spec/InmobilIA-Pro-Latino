-- Datos de ejemplo para demos: registro de cada fila sembrada, para poder quitarlas
-- todas (y solo esas) sin tocar los datos reales de la cuenta.
CREATE TABLE IF NOT EXISTS ejemplos_demo (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    tabla TEXT NOT NULL,
    registro_id TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ejemplos_demo_user ON ejemplos_demo(user_id, id);
