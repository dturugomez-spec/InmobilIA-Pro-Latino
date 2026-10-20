-- Versión Latinoamérica: país de cada inmobiliaria (moneda, impuesto, documentos, prefijo, zona horaria).
-- Se guarda en la cuenta administradora (users.id = organization_id). Códigos ISO: MX, CO, AR, CL, PE…
ALTER TABLE users ADD COLUMN IF NOT EXISTS pais TEXT DEFAULT 'MX';
ALTER TABLE solicitudes_cuenta ADD COLUMN IF NOT EXISTS pais TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS moneda TEXT;
ALTER TABLE portal_feeds ADD COLUMN IF NOT EXISTS feed_enabled BOOLEAN DEFAULT true;
