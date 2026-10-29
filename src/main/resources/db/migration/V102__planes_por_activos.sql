-- Planes por activos (oct-2026): Free, Pro, Business y Enterprise. El precio va por el número
-- de activos, no por usuario. Ejecutar a mano en Supabase (SQL Editor): no hay Flyway.

-- Mensual o anual (pagando el año, un 20 % menos)
ALTER TABLE users ADD COLUMN IF NOT EXISTS periodo_facturacion TEXT DEFAULT 'mensual';
-- Activos por encima de los del plan (4 €/mes cada uno)
ALTER TABLE users ADD COLUMN IF NOT EXISTS activos_extra INTEGER DEFAULT 0;
-- Módulos comprados en Pro: futureproof, holding (en Business vienen incluidos)
ALTER TABLE users ADD COLUMN IF NOT EXISTS modulos TEXT[] DEFAULT '{}';

-- El plan antiguo por usuario "agency" pasa a Business; los antiguos de prueba, a Free
UPDATE users SET plan = 'business' WHERE plan = 'agency';
UPDATE users SET plan = 'free' WHERE plan IN ('starter', 'trial');

-- Cuentas que el cron antiguo desactivó al acabar la prueba: ahora siguen en Free con sus datos
UPDATE users SET active = true, plan = 'free', plan_status = 'active'
 WHERE plan_status = 'expired' AND organization_id IS NULL;
