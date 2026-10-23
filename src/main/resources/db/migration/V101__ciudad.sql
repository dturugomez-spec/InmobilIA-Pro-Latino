-- Versión Latinoamérica: la inmobiliaria elige su ciudad; de ella salen el país (moneda, impuesto,
-- documentos, leyes) y la zona horaria. users.pais se sigue guardando (lo deduce la app de la ciudad).
ALTER TABLE users ADD COLUMN IF NOT EXISTS ciudad TEXT;
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS ciudad TEXT;
