-- Exportación de la cartera a portales (feed XML; kyero_enabled solo por compatibilidad con la versión española)
ALTER TABLE portal_feeds ADD COLUMN IF NOT EXISTS kyero_enabled BOOLEAN DEFAULT TRUE;

-- Qué inmuebles se publican y si son venta o alquiler (pueden existir ya: no pasa nada)
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS publicar_portales BOOLEAN DEFAULT FALSE;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS operacion TEXT;
ALTER TABLE propiedades ADD COLUMN IF NOT EXISTS provincia TEXT;
