package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inserta datos de ejemplo cuando un usuario se registra,
 * para que vea la app con contenido y entienda cómo funciona.
 * Asíncrono: no bloquea el registro.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemoDataService {

    private final SupabaseService supabase;
    private final PaisService paises;
    private final CurrencyService divisas;

    @Async
    public void seedDemoData(String userId) {
        try {
            log.info(">>> Seeding demo data para usuario {}", userId);

            // 3 propiedades demo
            Map<String, Object> p1 = newProp(userId, "Depto. Reforma 222, 3B", "Paseo de la Reforma 222", "Ciudad de México",
                    "06600", "Residencial", "Ocupado", 85.0, 3, 2, 1998, 235000.0, 270000.0, 1100.0,
                    "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=800&auto=format&fit=crop&q=80");
            insertar("propiedades", p1);

            Map<String, Object> p2 = newProp(userId, "Depto. Roma Norte 12, 2A", "Calle Orizaba 80", "Ciudad de México",
                    "06700", "Residencial", "Libre", 65.0, 2, 1, 2010, 195000.0, 220000.0, 950.0,
                    "https://images.unsplash.com/photo-1493809842364-78817add7ffb?w=800&auto=format&fit=crop&q=80");
            insertar("propiedades", p2);

            Map<String, Object> p3 = newProp(userId, "Local Insurgentes 88", "Av. Insurgentes Sur 88", "Ciudad de México",
                    "03100", "Comercial", "Ocupado", 120.0, 0, 1, 1985, 320000.0, 380000.0, 1850.0,
                    "https://images.unsplash.com/photo-1497366216548-37526070297c?w=800&auto=format&fit=crop&q=80");
            insertar("propiedades", p3);

            // 2 inquilinos demo
            Map<String, Object> i1 = new LinkedHashMap<>();
            i1.put("user_id", userId);
            i1.put("name", "Juan Pérez García");
            i1.put("email", "juan.perez@example.com");
            i1.put("phone", "+52 55 1234 5678");
            i1.put("id_number", "PEGJ800115HDFRRN05");
            i1.put("nationality", "Mexicana");
            i1.put("property_name", "Depto. Reforma 222, 3B");
            i1.put("rent_amount", 1100.0);
            i1.put("deposit", 2200.0);
            i1.put("lease_start", "2024-01-15");
            i1.put("lease_end", "2029-01-14");
            i1.put("status", "Activo");
            i1.put("score", 85);
            i1.put("notes", "Inquilino ejemplar. Pagos puntuales.");
            i1.put("created_at", OffsetDateTime.now().toString());
            insertar("inquilinos", i1);

            Map<String, Object> i2 = new LinkedHashMap<>();
            i2.put("user_id", userId);
            i2.put("name", "Cosméticos PIO S.A. de C.V.");
            i2.put("email", "info@cosmeticospio.example.com");
            i2.put("phone", "+52 55 1234 5678");
            i2.put("id_number", "CPI150601AB1");
            i2.put("property_name", "Local Insurgentes 88");
            i2.put("rent_amount", 1850.0);
            i2.put("deposit", 5550.0);
            i2.put("lease_start", "2023-06-01");
            i2.put("lease_end", "2028-05-31");
            i2.put("status", "Activo");
            i2.put("score", 78);
            i2.put("created_at", OffsetDateTime.now().toString());
            insertar("inquilinos", i2);

            // Lead demo
            Map<String, Object> l1 = new LinkedHashMap<>();
            l1.put("user_id", userId);
            l1.put("nombre", "Ana García López");
            l1.put("email", "ana.garcia@example.com");
            l1.put("telefono", "+52 55 7890 1234");
            l1.put("origen", "Inmuebles24");
            l1.put("interes", "Busca depto. 2 hab en Roma Norte/Juárez, presupuesto 250k");
            l1.put("presupuesto", 250000.0);
            l1.put("zona", "Roma Norte");
            l1.put("tipo_activo", "Residencial");
            l1.put("etapa", "Visita");
            l1.put("score", 75);
            l1.put("next_followup", LocalDate.now().plusDays(3).toString());
            l1.put("notas", "Le gustó el Roma Norte 12. Pidió rebajar 5%.");
            l1.put("created_at", OffsetDateTime.now().toString());
            insertar("leads", l1);

            // Transacción de cobro demo (este mes)
            Map<String, Object> t1 = new LinkedHashMap<>();
            t1.put("user_id", userId);
            t1.put("type", "Ingreso");
            t1.put("category", "Alquiler");
            t1.put("amount", 1100.0);
            t1.put("date", LocalDate.now().withDayOfMonth(5).toString());
            t1.put("property_name", "Depto. Reforma 222, 3B");
            t1.put("description", "Cobro alquiler mensual · Juan Pérez");
            t1.put("created_at", OffsetDateTime.now().toString());
            insertar("transacciones", t1);

            // Visita / reserva demo
            Map<String, Object> v1 = new LinkedHashMap<>();
            v1.put("user_id", userId);
            v1.put("client_name", "Ana García López");
            v1.put("client_phone", "+52 55 7890 1234");
            v1.put("client_email", "ana.garcia@example.com");
            v1.put("property_name", "Depto. Roma Norte 12, 2A");
            v1.put("date", LocalDate.now().plusDays(2).toString());
            v1.put("time", "17:30");
            v1.put("type", "Visita");
            v1.put("status", "Confirmada");
            v1.put("agent_name", "Comercial");
            v1.put("created_at", OffsetDateTime.now().toString());
            insertar("reservas", v1);

            // Tarea demo
            Map<String, Object> task1 = new LinkedHashMap<>();
            task1.put("user_id", userId);
            task1.put("asignado_a", userId);
            task1.put("titulo", "Revisar calentador Depto. Reforma");
            task1.put("descripcion", "Inquilino reporta agua tibia en ducha. Llamar al fontanero.");
            task1.put("prioridad", "media");
            task1.put("estado", "pendiente");
            task1.put("fecha_limite", LocalDate.now().plusDays(5).toString());
            task1.put("created_at", OffsetDateTime.now().toString());
            insertar("tareas", task1);

            log.info(">>> Demo data seeded para usuario {}", userId);
        } catch (Exception e) {
            log.warn("Error sembrando datos demo: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Sembrado ENRIQUECIDO para la cuenta de demostración comercial.
    // Síncrono y con informe por tabla (para verificar e iterar).
    // Llamado desde el endpoint protegido /debug/seed-demo.
    // ------------------------------------------------------------------

    /** Campos con importes (los valores de ejemplo están en euros y se pasan a la moneda de la inmobiliaria). */
    private static final Set<String> IMPORTES = Set.of(
            "rent_amount", "rent", "deposit", "amount", "importe", "importe_inicial", "presupuesto", "presupuesto_max",
            "presupuesto_min", "precio", "precio_min", "precio_max", "precio_estimado", "precio_objetivo", "precio_nuevo",
            "precio_anterior", "precio_recomendado", "precio_desde", "precio_hasta", "precio_compra", "rental_income",
            "monthly_expenses", "purchase_price", "current_value", "eur_m2", "eur_m2_zona", "deposito_arras", "coste",
            "total", "valor", "valor_operacion", "renta_mensual", "fianza", "saldo_inicial", "saldo_final", "saldo_actual",
            "prima_anual", "capital_asegurado", "capital_prestamo", "capacidad_max", "cuota_estimada", "cuota_mensual",
            "commission_amount", "base_imponible", "iva_amount", "iva_importe", "irpf_amount", "ingresos_mensuales",
            "importe_reclamado", "importe_liquidar", "importe_indemnizado", "seguro_hogar", "seguro_impago", "mantenimiento",
            "gestion", "otros_gastos", "ibi", "comunidad", "gastos_compra", "reformas", "mobiliario", "ahorro", "diferencia",
            "total_pagado", "capital_hipoteca");

    /** País de la inmobiliaria (la siembra es @Async: no hay país de la petición). */
    public com.inmobilia.util.Pais pais(String userId) {
        return paises.deOrganizacion(userId);
    }

    /** Euros de los datos de ejemplo → moneda de la inmobiliaria. */
    public double factorEur(String userId) {
        String mon = pais(userId).moneda();
        return "EUR".equals(mon) ? 1.0 : divisas.convertir(1.0, "EUR", mon);
    }

    private static boolean esImporte(String clave) {
        String k = clave.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(); // rentaMensual → renta_mensual
        return !k.endsWith("_pct") && (IMPORTES.contains(k) || k.startsWith("total_") || k.startsWith("importe_"));
    }

    private static double redondear(double x) {
        return Math.abs(x) >= 10_000 ? Math.round(x / 100.0) * 100.0 : Math.round(x * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private static Object convertir(String clave, Object v, double f) {
        if (v instanceof Number n && clave != null && esImporte(clave)) return redondear(n.doubleValue() * f);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> copia = new LinkedHashMap<>();
            m.forEach((k, x) -> copia.put(String.valueOf(k), convertir(String.valueOf(k), x, f)));
            return copia;
        }
        if (v instanceof List<?> l) {
            List<Object> copia = new ArrayList<>();
            for (Object x : l) copia.add(convertir(clave, x, f));
            return copia;
        }
        return v;
    }

    /** La fila con sus importes (también dentro de listas y mapas, p. ej. líneas de factura) en la moneda de la inmobiliaria. */
    public Map<String, Object> enMoneda(Map<String, Object> row, String userId) {
        if (userId == null) return row;
        double f = factorEur(userId);
        if (f == 1.0) return row;
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, convertir(k, v, f)));
        return out;
    }

    public Map<String, Object> enMoneda(Map<String, Object> row) {
        Object uid = row.get("user_id") != null ? row.get("user_id") : row.get("org_id");
        return uid == null ? row : enMoneda(row, String.valueOf(uid));
    }

    private Map<String, Object> insertar(String table, Map<String, Object> row) {
        return supabase.insert(table, enMoneda(row));
    }

    /** Avisa de cada fila de ejemplo guardada (para anotarla y poder quitarla después). */
    @FunctionalInterface
    public interface Registro {
        void guardada(String tabla, Map<String, Object> fila);
    }

    /** Inserta una fila y contabiliza en el informe; devuelve la fila guardada (con id) o null. */
    private Map<String, Object> ins(Map<String, Object> report, Registro registro, String table, Map<String, Object> row) {
        try {
            Map<String, Object> saved = supabase.insert(table, enMoneda(row));
            if (saved != null && !saved.isEmpty()) {
                report.merge(table, 1, (a, b) -> (Integer) a + (Integer) b);
                if (registro != null) registro.guardada(table, saved);
                return saved;
            }
            report.putIfAbsent(table + "_error", supabase.lastError() != null ? supabase.lastError() : "insert vacío");
            return null;
        } catch (Exception e) {
            report.put(table + "_error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Siembra un set de datos rico y variado para demostraciones a clientes. */
    public Map<String, Object> seedRichDemo(String userId) {
        return seedRichDemo(userId, null);
    }

    /**
     * Igual, avisando de cada fila guardada. (Antes había un "wipeDemo" que borraba TODAS las filas
     * de la cuenta en estas tablas, también las reales: ahora se quitan solo las anotadas, ver
     * EjemplosService.)
     */
    public Map<String, Object> seedRichDemo(String userId, Registro registro) {
        Map<String, Object> report = new LinkedHashMap<>();
        String now = OffsetDateTime.now().toString();

        // ---------- PROPIEDADES (7, variadas, con portal del propietario) ----------
        // {name, address, city, postal, type, status, area, beds, baths, year, purchase, current, rent, photo, propNombre, propEmail}
        Object[][] props = {
            {"Depto. Reforma 222, 3B", "Paseo de la Reforma 222", "Ciudad de México", "06600", "Residencial", "Ocupado", 85.0, 3, 2, 1998, 235000.0, 270000.0, 1100.0, "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=800&auto=format&fit=crop&q=80", "Carmen Ruiz Molina", "carmen.ruiz@example.com"},
            {"Depto. Roma Norte 12, 2A", "Calle Orizaba 80", "Ciudad de México", "06700", "Residencial", "Libre", 65.0, 2, 1, 2010, 195000.0, 220000.0, 950.0, "https://images.unsplash.com/photo-1493809842364-78817add7ffb?w=800&auto=format&fit=crop&q=80", "Luis Sanz Pérez", "luis.sanz@example.com"},
            {"Local Insurgentes 88", "Av. Insurgentes Sur 88", "Ciudad de México", "03100", "Comercial", "Ocupado", 120.0, 0, 1, 1985, 320000.0, 380000.0, 1850.0, "https://images.unsplash.com/photo-1497366216548-37526070297c?w=800&auto=format&fit=crop&q=80", "Inversiones Insurgentes S.A. de C.V.", "info@invatocha.example.com"},
            {"Penthouse Masaryk 100", "Av. Presidente Masaryk 100", "Ciudad de México", "11560", "Residencial", "Libre", 110.0, 3, 2, 2005, 640000.0, 720000.0, 2400.0, "https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?w=800&auto=format&fit=crop&q=80", "Familia Herrero", "herrero.familia@example.com"},
            {"Casa Santa Fe", "Av. Vasco de Quiroga 15", "Ciudad de México", "01219", "Residencial", "Libre", 220.0, 5, 3, 2015, 590000.0, 650000.0, 2600.0, "https://images.unsplash.com/photo-1512917774080-9991f1c4c750?w=800&auto=format&fit=crop&q=80", "Marta Gil Vidal", "marta.gil@example.com"},
            {"Estudio Coyoacán", "Calle Francisco Sosa 22", "Ciudad de México", "03100", "Residencial", "Libre", 38.0, 1, 1, 1972, 145000.0, 165000.0, 800.0, "https://images.unsplash.com/photo-1522708323590-d24dbb6b0267?w=800&auto=format&fit=crop&q=80", "Óscar Prieto", "oscar.prieto@example.com"},
            {"Oficina Del Valle", "Calle Pilares 45", "Ciudad de México", "03100", "Comercial", "Libre", 95.0, 0, 2, 2000, 410000.0, 450000.0, 1950.0, "https://images.unsplash.com/photo-1497366811353-6870744d04b2?w=800&auto=format&fit=crop&q=80", "Grupo Pilares S.A. de C.V.", "contacto@zurbano.example.com"},
            {"Depto. Condesa, Ámsterdam 5", "Calle Ámsterdam 5", "Ciudad de México", "06140", "Residencial", "Libre", 95.0, 3, 2, 2008, 480000.0, 520000.0, 1800.0, "https://images.unsplash.com/photo-1560185007-cde436f6a4d0?w=800&auto=format&fit=crop&q=80", "Julia Sáez Bravo", "julia.saez@example.com"},
            {"Dúplex Narvarte", "Calle Pitágoras 30", "Ciudad de México", "03020", "Residencial", "Libre", 130.0, 4, 2, 2012, 560000.0, 610000.0, 2100.0, "https://images.unsplash.com/photo-1502005229762-cf1b2da7c5d6?w=800&auto=format&fit=crop&q=80", "Ignacio Roldán", "ignacio.roldan@example.com"},
            {"Casa Coyoacán", "Calle Higuera 12", "Ciudad de México", "04000", "Residencial", "Ocupado", 160.0, 4, 2, 2003, 310000.0, 340000.0, 1250.0, "https://images.unsplash.com/photo-1568605114967-8130f3a36994?w=800&auto=format&fit=crop&q=80", "Rosa Delgado", "rosa.delgado@example.com"},
            {"Loft Escandón", "Calle Patriotismo 60", "Ciudad de México", "11800", "Residencial", "Libre", 70.0, 1, 1, 2018, 285000.0, 310000.0, 1300.0, "https://images.unsplash.com/photo-1522708323590-d24dbb6b0267?w=800&auto=format&fit=crop&q=80", "Hugo Marín", "hugo.marin@example.com"},
            {"Bodega Industrial Tlalnepantla", "Parque Industrial Tlalnepantla, Calle 3", "Tlalnepantla", "54030", "Comercial", "Libre", 400.0, 0, 2, 1998, 520000.0, 560000.0, 2800.0, "https://images.unsplash.com/photo-1553413077-190dd305871c?w=800&auto=format&fit=crop&q=80", "Logística TLA S.A. de C.V.", "info@logisticagtf.example.com"},
        };
        List<String> propIds = new ArrayList<>();
        List<String> propNames = new ArrayList<>();
        for (Object[] a : props) {
            String status = (String) a[5];
            Map<String, Object> p = newProp(userId, (String) a[0], (String) a[1], (String) a[2], (String) a[3],
                    (String) a[4], status, (Double) a[6], (Integer) a[7], (Integer) a[8], (Integer) a[9],
                    (Double) a[10], (Double) a[11], (Double) a[12], (String) a[13]);
            // Portal del propietario (transparencia)
            p.put("propietario_nombre", a[14]);
            p.put("propietario_email", a[15]);
            p.put("portal_token", UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            p.put("portal_activo", true);
            p.put("portal_vistas", 5 + (int) (Math.random() * 40));
            p.put("portal_mensaje_agente", "Seguimos trabajando en la comercialización. Esta semana " + (2 + (int) (Math.random() * 5)) + " visitas programadas.");
            Map<String, Object> saved = ins(report, registro, "propiedades", p);
            if (saved != null) {
                propIds.add(String.valueOf(saved.get("id")));
                propNames.add((String) a[0]);
            }
        }

        // ---------- INQUILINOS (3) ----------
        ins(report, registro, "inquilinos", inquilino(userId, "Juan Pérez García", "juan.perez@example.com", "+52 55 1234 5678",
                "PEGJ800115HDFRRN05", "Depto. Reforma 222, 3B", 1100.0, 2200.0, "2024-01-15", "2029-01-14", 85, "Inquilino ejemplar. Pagos puntuales."));
        ins(report, registro, "inquilinos", inquilino(userId, "Cosméticos PIO S.A. de C.V.", "info@cosmeticospio.example.com", "+52 55 1234 5678",
                "CPI150601AB1", "Local Insurgentes 88", 1850.0, 5550.0, "2023-06-01", "2028-05-31", 78, "Contrato comercial. Renta ajustada a INPC."));
        ins(report, registro, "inquilinos", inquilino(userId, "Sara López Vega", "sara.lopez@example.com", "+52 55 5511 2233",
                "LOVS020301MDFPGR08", "Estudio Coyoacán", 800.0, 1600.0, "2025-03-01", "2026-02-28", 72, "Estudiante. Aval de los padres."));

        // ---------- LEADS (8, todas las etapas del embudo) ----------
        // {nombre, email, tel, origen, interes, presupuesto, zona, tipo, etapa, score, followDays, notas}
        Object[][] leads = {
            {"Ana García López", "ana.garcia@example.com", "+52 55 7890 1234", "Inmuebles24", "Busca depto. 2 hab en Roma Norte/Juárez", 250000.0, "Roma Norte", "Residencial", "Visita", 82, 3, "Le gustó el Roma Norte 12. Pidió rebajar 5%."},
            {"Roberto Díaz Nieto", "roberto.diaz@example.com", "+52 55 1122 2333", "Vivanuncios", "Reubicación por trabajo, zona Polanco", 700000.0, "Polanco", "Residencial", "Nuevo", 68, 1, "Contactar mañana. Muy interesado en el ático."},
            {"Marta Fernández Ruiz", "marta.fernandez@example.com", "+52 55 2233 3444", "Web propia", "Primera vivienda, presupuesto ajustado", 175000.0, "Coyoacán", "Residencial", "Contactado", 55, 2, "Enviada selección de estudios."},
            {"Nostrum Retail S.A. de C.V.", "expansion@nostrum.example.com", "+52 55 1555 6677", "Referido", "Local comercial 100-150 m² zona centro", 400000.0, "Centro", "Comercial", "Oferta", 88, 1, "Oferta presentada por el Local Insurgentes."},
            {"Pablo Moreno Blanco", "pablo.moreno@example.com", "+52 55 3344 4555", "Recomendación", "Casa familiar en Santa Fe", 650000.0, "Santa Fe", "Residencial", "Firmado", 95, 0, "¡Operación cerrada! Contrato de promesa firmado."},
            {"Lucía Ramos Ortega", "lucia.ramos@example.com", "+52 55 4455 5666", "Inmuebles24", "Inversión para alquilar", 220000.0, "Del Valle", "Residencial", "Contactado", 61, 4, "Busca rentabilidad >5%."},
            {"David Torres Gómez", "david.torres@example.com", "+52 55 5566 6777", "Instagram", "Ático con terraza, exclusivo", 750000.0, "Polanco", "Residencial", "Visita", 79, 2, "Visita al penthouse Masaryk el jueves."},
            {"Sofía Núñez Cano", "sofia.nunez@example.com", "+52 55 6677 7888", "Vivanuncios", "Estudio para hija universitaria", 160000.0, "Coyoacán", "Residencial", "Perdido", 30, 0, "Compró con otra agencia. Cerrar."},
        };
        for (Object[] a : leads) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("user_id", userId);
            l.put("nombre", a[0]); l.put("email", a[1]); l.put("telefono", a[2]);
            l.put("origen", a[3]); l.put("interes", a[4]); l.put("presupuesto", a[5]);
            l.put("zona", a[6]); l.put("tipo_activo", a[7]); l.put("etapa", a[8]);
            l.put("score", a[9]);
            l.put("next_followup", LocalDate.now().plusDays((Integer) a[10]).toString());
            l.put("notas", a[11]);
            l.put("created_at", now);
            ins(report, registro, "leads", l);
        }

        // ---------- TRANSACCIONES (ingresos + gastos, últimos 6 meses → gráficos) ----------
        String[][] rentas = {{"Depto. Reforma 222, 3B", "1100"}, {"Local Insurgentes 88", "1850"}, {"Estudio Coyoacán", "800"}};
        for (int m = 5; m >= 0; m--) {
            LocalDate mes = LocalDate.now().minusMonths(m).withDayOfMonth(5);
            for (String[] r : rentas) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("user_id", userId); t.put("type", "Ingreso"); t.put("category", "Alquiler");
                t.put("amount", Double.parseDouble(r[1])); t.put("date", mes.toString());
                t.put("property_name", r[0]); t.put("description", "Cobro alquiler mensual · " + r[0]);
                t.put("created_at", now);
                ins(report, registro, "transacciones", t);
            }
        }
        // Gastos variados
        Object[][] gastos = {
            {"Gasto", "Mantenimiento", 95.0, 10, "Depto. Reforma 222, 3B", "Cuota de mantenimiento"},
            {"Gasto", "Predial", 340.0, 20, "Local Insurgentes 88", "Predial bimestral"},
            {"Gasto", "Reparación", 210.0, 12, "Depto. Reforma 222, 3B", "Reparación caldera"},
            {"Gasto", "Suministros", 60.0, 8, "Estudio Coyoacán", "Agua y mantenimiento"},
            {"Ingreso", "Honorarios", 9750.0, 15, "Casa Santa Fe", "Honorarios venta (comisión 1,5%)"},
        };
        for (Object[] g : gastos) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("user_id", userId); t.put("type", g[0]); t.put("category", g[1]);
            t.put("amount", g[2]); t.put("date", LocalDate.now().withDayOfMonth((Integer) g[3]).toString());
            t.put("property_name", g[4]); t.put("description", g[5]); t.put("created_at", now);
            ins(report, registro, "transacciones", t);
        }

        // ---------- RESERVAS / VISITAS (agenda) ----------
        Object[][] visitas = {
            {"Ana García López", "+52 55 7890 1234", "ana.garcia@example.com", "Depto. Roma Norte 12, 2A", 2, "17:30", "Visita", "Confirmada"},
            {"David Torres Gómez", "+52 55 5566 6777", "david.torres@example.com", "Penthouse Masaryk 100", 4, "11:00", "Visita", "Confirmada"},
            {"Roberto Díaz Nieto", "+52 55 1122 2333", "roberto.diaz@example.com", "Penthouse Masaryk 100", 6, "18:00", "Visita", "Pendiente"},
            {"Lucía Ramos Ortega", "+52 55 4455 5666", "lucia.ramos@example.com", "Oficina Del Valle", -3, "10:30", "Visita", "Realizada"},
            {"Nostrum Retail S.A. de C.V.", "+52 55 1555 6677", "expansion@nostrum.example.com", "Local Insurgentes 88", 1, "13:00", "Reunión", "Confirmada"},
        };
        for (Object[] v : visitas) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("user_id", userId); r.put("client_name", v[0]); r.put("client_phone", v[1]);
            r.put("client_email", v[2]); r.put("property_name", v[3]);
            r.put("date", LocalDate.now().plusDays((Integer) v[4]).toString());
            r.put("time", v[5]); r.put("type", v[6]); r.put("status", v[7]);
            r.put("agent_name", "Comercial"); r.put("created_at", now);
            ins(report, registro, "reservas", r);
        }

        // ---------- TAREAS (esquema real V28: titulo/descripcion/prioridad/estado/fecha_limite/propiedad_id) ----------
        // {titulo, descripcion, prioridad(baja|media|alta|urgente), estado(pendiente|en_curso|completada), diasLimite, propIndex(-1=ninguna)}
        Object[][] tareas = {
            {"Revisar calentador Depto. Reforma", "Inquilino reporta agua tibia. Llamar al fontanero.", "media", "pendiente", 5, 0},
            {"Preparar dossier penthouse Masaryk", "Fotos pro + plano + nota de encargo para David Torres.", "alta", "en_curso", 1, 3},
            {"Llamar a Roberto Díaz", "Primer contacto lead nuevo de Inmuebles24.", "alta", "pendiente", 0, -1},
            {"Firmar promesa casa Santa Fe", "Cita en notaría con Pablo Moreno.", "urgente", "pendiente", 3, 4},
            {"Renovar póliza de seguro", "Estudio Coyoacán caduca el mes que viene.", "baja", "pendiente", 12, 5},
        };
        for (Object[] t : tareas) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("user_id", userId);
            task.put("asignado_a", userId);
            task.put("titulo", t[0]);
            task.put("descripcion", t[1]);
            task.put("prioridad", t[2]);
            task.put("estado", t[3]);
            task.put("fecha_limite", LocalDate.now().plusDays((Integer) t[4]).toString());
            int pi = (Integer) t[5];
            if (pi >= 0 && pi < propIds.size()) task.put("propiedad_id", propIds.get(pi));
            task.put("created_at", now);
            ins(report, registro, "tareas", task);
        }

        // ---------- VALORACIONES (imán de captación) ----------
        Object[][] vals = {
            {"piso", "Ciudad de México", "Ciudad de México", "Del Valle", 90.0, 3, 2, "buen_estado", 268000.0, 250000.0, 286000.0, 2978.0, "alta", "Elena Vargas", "elena.vargas@example.com", "+52 55 7700 1122", "3-6m"},
            {"chalet", "Huixquilucan", "Ciudad de México", "La Finca", 240.0, 5, 4, "nuevo", 1150000.0, 1080000.0, 1220000.0, 4792.0, "media", "Grupo Familiar Ríos", "rios.familia@example.com", "+52 55 7700 3344", "explorando"},
            {"atico", "Ciudad de México", "Ciudad de México", "Polanco", 105.0, 3, 2, "reformado", 735000.0, 690000.0, 780000.0, 7000.0, "alta", "Andrés Cano", "andres.cano@example.com", "+52 55 7700 5566", "ahora"},
        };
        for (Object[] v : vals) {
            Map<String, Object> val = new LinkedHashMap<>();
            val.put("user_id", userId);
            val.put("tipo", v[0]); val.put("municipio", v[1]); val.put("provincia", v[2]); val.put("zona", v[3]);
            val.put("metros", v[4]); val.put("habitaciones", v[5]); val.put("banos", v[6]); val.put("estado", v[7]);
            val.put("precio_estimado", v[8]); val.put("precio_min", v[9]); val.put("precio_max", v[10]);
            val.put("eur_m2", v[11]); val.put("confianza", v[12]); val.put("metodo", "avm_ia");
            val.put("propietario_nombre", v[13]); val.put("propietario_email", v[14]); val.put("propietario_telefono", v[15]);
            val.put("quiere_vender", v[16]); val.put("origen", "valoracion_publica"); val.put("created_at", now);
            ins(report, registro, "valoraciones", val);
        }

        // ---------- PORTAL: mensajes propietario ↔ agente ----------
        if (propIds.size() >= 2) {
            ins(report, registro, "portal_mensajes", portalMsg(userId, propIds.get(0), "propietario", "¿Cómo van las visitas este mes? Me gustaría un resumen.", true));
            ins(report, registro, "portal_mensajes", portalMsg(userId, propIds.get(0), "agente", "¡Hola Carmen! Esta semana 3 visitas y una oferta en estudio. Te llamo el viernes.", false));
            ins(report, registro, "portal_mensajes", portalMsg(userId, propIds.get(1), "propietario", "¿Podríamos bajar el precio un poco para acelerar?", false));
        }

        // ---------- CAMPAÑAS de marketing ----------
        Map<String, Object> camp1 = new LinkedHashMap<>();
        camp1.put("user_id", userId); camp1.put("nombre", "Newsletter septiembre · Nuevas exclusivas");
        camp1.put("descripcion", "Envío mensual a base de compradores"); camp1.put("canal", "email");
        camp1.put("asunto", "🏠 3 nuevas viviendas que te van a encantar");
        camp1.put("contenido", "<h1>Nuevas exclusivas</h1><p>Descubre el penthouse de Masaryk y la casa de Santa Fe...</p>");
        camp1.put("from_name", "InmobilIA Pro"); camp1.put("segmento_tipo", "todos_leads");
        camp1.put("estado", "enviada"); camp1.put("enviado_at", now); camp1.put("completado_at", now);
        camp1.put("destinatarios_total", 340); camp1.put("enviados", 338); camp1.put("fallidos", 2);
        camp1.put("abiertos", 214); camp1.put("clicks", 63); camp1.put("bajas", 3); camp1.put("conversiones", 7);
        camp1.put("created_at", now);
        ins(report, registro, "campanas", camp1);

        Map<String, Object> camp2 = new LinkedHashMap<>();
        camp2.put("user_id", userId); camp2.put("nombre", "WhatsApp · Recordatorio open house");
        camp2.put("canal", "whatsapp"); camp2.put("contenido", "Hola {nombre}, te esperamos el sábado en el open house del penthouse Masaryk 100. ¿Confirmas?");
        camp2.put("segmento_tipo", "segmentado"); camp2.put("estado", "borrador");
        camp2.put("destinatarios_total", 0); camp2.put("created_at", now);
        ins(report, registro, "campanas", camp2);

        // ---------- OFERTAS formales ----------
        if (propIds.size() >= 4) {
            ins(report, registro, "ofertas", oferta(userId, propIds.get(3), "OF-2026-014", 690000.0, 720000.0, "hipoteca+ahorros",
                    "David Torres Gómez", "david.torres@example.com", "+52 55 5566 6777", "en_negociacion", 15000.0));
            ins(report, registro, "ofertas", oferta(userId, propIds.get(2), "OF-2026-015", 365000.0, 380000.0, "contado",
                    "Nostrum Retail S.A. de C.V.", "expansion@nostrum.example.com", "+52 55 1555 6677", "nueva", 20000.0));
        }

        // ---------- CERTIFICADOS ENERGÉTICOS (uno por las primeras propiedades) ----------
        String[] letras = {"C", "D", "B", "E", "D", "C"};
        for (int i = 0; i < Math.min(6, propIds.size()); i++) {
            Map<String, Object> cee = new LinkedHashMap<>();
            cee.put("user_id", userId);
            cee.put("propiedad_id", propIds.get(i));
            cee.put("numero_registro", "CEE-M-2025-" + (1000 + i));
            cee.put("fecha_emision", LocalDate.now().minusMonths(6 + i).toString());
            cee.put("fecha_caducidad", LocalDate.now().plusYears(9).minusMonths(i).toString());
            cee.put("calificacion_consumo", letras[i]);
            cee.put("consumo_kwh_m2_anio", 90.0 + i * 22);
            cee.put("calificacion_emisiones", letras[i]);
            cee.put("emisiones_kg_co2_m2_anio", 18.0 + i * 6);
            cee.put("tecnico_nombre", "Arq. Téc. Marina Ortega");
            cee.put("tecnico_titulacion", "Arquitecta técnica");
            cee.put("coste", 120.0);
            cee.put("estado", "vigente");
            cee.put("medidas_mejora", "Ventanas con rotura de puente térmico y caldera de condensación.");
            cee.put("ahorro_estimado_pct", 22.0);
            cee.put("created_at", now);
            ins(report, registro, "certificados_energeticos", cee);
        }

        // ---------- RESEÑAS / TESTIMONIOS ----------
        Object[][] revs = {
            {"María Jiménez", "comprador", 5, "Encontramos casa en 3 semanas", "Trato excelente y muy profesionales. Nos guiaron en todo el proceso de compra.", true, true, "google"},
            {"Andrés Vidal", "vendedor", 5, "Vendieron mi piso por encima de precio", "En 40 días y sin complicaciones. Repetiré sin duda.", true, true, "idealista"},
            {"Patricia Losa", "inquilino", 4, "Muy atentos", "Resolvieron mis dudas rápido. El portal para ver el estado del inmueble es genial.", true, false, "directa"},
            {"Cosméticos PIO S.A. de C.V.", "inquilino", 5, "Local perfecto para nuestra tienda", "Nos asesoraron con la licencia y el traspaso. 10/10.", true, true, "directa"},
        };
        for (Object[] r : revs) {
            Map<String, Object> rev = new LinkedHashMap<>();
            rev.put("user_id", userId);
            rev.put("autor_nombre", r[0]); rev.put("autor_rol", r[1]); rev.put("puntuacion", r[2]);
            rev.put("titulo", r[3]); rev.put("comentario", r[4]);
            rev.put("aprobada", r[5]); rev.put("destacada", r[6]); rev.put("publica", true);
            rev.put("fuente", r[7]); rev.put("created_at", now);
            ins(report, registro, "reviews", rev);
        }

        // ---------- NOTAS DE ENCARGO (captación / exclusiva) ----------
        if (propIds.size() >= 5) {
            Map<String, Object> ne = new LinkedHashMap<>();
            ne.put("user_id", userId); ne.put("propiedad_id", propIds.get(4));
            ne.put("propietario_nombre", "Marta Gil Vidal"); ne.put("propietario_dni", "50123456K");
            ne.put("propietario_email", "marta.gil@example.com"); ne.put("propietario_telefono", "+52 55 5510 0200");
            ne.put("inmueble_texto", "Casa 220 m² en Santa Fe · 5 rec · 3 baños · terreno 500 m².");
            ne.put("precio", 650000.0); ne.put("comision_pct", 3.0); ne.put("exclusiva", true);
            ne.put("duracion_meses", 6); ne.put("estado", "firmado");
            ne.put("observaciones", "Exclusiva 6 meses. Incluye home staging y reportaje fotográfico profesional.");
            ne.put("created_at", now);
            ins(report, registro, "notas_encargo", ne);
        }
        if (propIds.size() >= 4) {
            Map<String, Object> ne2 = new LinkedHashMap<>();
            ne2.put("user_id", userId); ne2.put("propiedad_id", propIds.get(3));
            ne2.put("propietario_nombre", "Familia Herrero"); ne2.put("propietario_email", "herrero.familia@example.com");
            ne2.put("inmueble_texto", "Penthouse 110 m² Masaryk 100 · 3 rec · 2 baños · terraza 25 m².");
            ne2.put("precio", 720000.0); ne2.put("comision_pct", 3.5); ne2.put("exclusiva", true);
            ne2.put("duracion_meses", 4); ne2.put("estado", "borrador"); ne2.put("created_at", now);
            ins(report, registro, "notas_encargo", ne2);
        }

        // ---------- GASTOS DE AGENTE ----------
        Object[][] gas = {
            {"kilometraje", "Visitas zona poniente (oficina → Santa Fe)", 0.0, 42.0, "Oficina", "Santa Fe", "aprobado"},
            {"parking", "Parking centro para reunión con inversor", 8.5, 0.0, null, null, "aprobado"},
            {"dieta_comida", "Comida de trabajo con Nostrum Retail", 34.0, 0.0, null, null, "pendiente"},
            {"combustible", "Repostaje mensual vehículo comercial", 65.0, 0.0, null, null, "pendiente"},
        };
        for (Object[] g : gas) {
            Map<String, Object> ga = new LinkedHashMap<>();
            ga.put("user_id", userId); ga.put("agente_id", userId);
            ga.put("fecha", LocalDate.now().minusDays(2 + (int) (Math.random() * 18)).toString());
            ga.put("tipo", g[0]); ga.put("concepto", g[1]);
            double importe = (Double) g[2];
            double km = (Double) g[3];
            if (km > 0) { ga.put("km", km); ga.put("importe_por_km", 0.26); importe = Math.round(km * 0.26 * 100) / 100.0; }
            ga.put("importe", importe);
            if (g[4] != null) ga.put("origen", g[4]);
            if (g[5] != null) ga.put("destino", g[5]);
            ga.put("estado", g[6]); ga.put("created_at", now);
            ins(report, registro, "gastos_agente", ga);
        }

        // ---------- BÚSQUEDAS GUARDADAS (búsqueda inversa / alertas) ----------
        Map<String, Object> bg = new LinkedHashMap<>();
        bg.put("user_id", userId);
        bg.put("cliente_nombre", "Roberto Díaz Nieto"); bg.put("cliente_email", "roberto.diaz@example.com");
        bg.put("cliente_telefono", "+52 55 1122 2333"); bg.put("nombre_busqueda", "Penthouse Polanco remodelado");
        bg.put("operacion", "venta"); bg.put("tipo", "atico"); bg.put("municipio", "Ciudad de México");
        bg.put("zonas", List.of("Polanco", "Del Valle")); bg.put("precio_min", 500000.0); bg.put("precio_max", 800000.0);
        bg.put("metros_min", 90.0); bg.put("habitaciones_min", 3); bg.put("requiere_terraza", true);
        bg.put("notificar_email", true); bg.put("frecuencia", "inmediata"); bg.put("activa", true);
        bg.put("matches_count", 2); bg.put("created_at", now);
        ins(report, registro, "busquedas_guardadas", bg);

        Map<String, Object> bg2 = new LinkedHashMap<>();
        bg2.put("user_id", userId);
        bg2.put("cliente_nombre", "Lucía Ramos Ortega"); bg2.put("cliente_email", "lucia.ramos@example.com");
        bg2.put("nombre_busqueda", "Inversión para rentar en Del Valle"); bg2.put("operacion", "venta");
        bg2.put("tipo", "piso"); bg2.put("municipio", "Ciudad de México"); bg2.put("zonas", List.of("Del Valle", "Tetuán"));
        bg2.put("precio_max", 250000.0); bg2.put("habitaciones_min", 2); bg2.put("requiere_ascensor", true);
        bg2.put("frecuencia", "diaria"); bg2.put("activa", true); bg2.put("matches_count", 1); bg2.put("created_at", now);
        ins(report, registro, "busquedas_guardadas", bg2);

        // ---------- NEWSLETTER: suscriptores ----------
        Object[][] subs = {
            {"elena.compradora@example.com", "Elena Vargas", "Del Valle"},
            {"mario.martin@example.com", "Mario Martín", "Polanco"},
            {"clara.inversora@example.com", "Clara Ruiz", "Centro"},
            {"tomas.jerez@example.com", "Tomás Jerez", "Condesa"},
            {"nuria.compra@example.com", "Nuria Peña", "Santa Fe"},
        };
        for (Object[] s : subs) {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("user_id", userId); sub.put("email", s[0]); sub.put("nombre", s[1]);
            sub.put("intereses", List.of("venta", "obra_nueva")); sub.put("zona", s[2]);
            sub.put("fuente", "microsite"); sub.put("confirmado", true); sub.put("confirmado_at", now);
            sub.put("created_at", now);
            ins(report, registro, "newsletter_suscriptores", sub);
        }

        return report;
    }

    private Map<String, Object> inquilino(String userId, String name, String email, String phone, String idNumber,
                                          String propertyName, double rent, double deposit, String start, String end,
                                          int score, String notes) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("user_id", userId); i.put("name", name); i.put("email", email); i.put("phone", phone);
        i.put("id_number", idNumber); i.put("property_name", propertyName); i.put("rent_amount", rent);
        i.put("deposit", deposit); i.put("lease_start", start); i.put("lease_end", end);
        i.put("status", "Activo"); i.put("score", score); i.put("notes", notes);
        i.put("created_at", OffsetDateTime.now().toString());
        return i;
    }

    private Map<String, Object> portalMsg(String userId, String propId, String autor, String mensaje, boolean leido) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", userId); m.put("propiedad_id", propId); m.put("autor", autor);
        m.put("mensaje", mensaje); m.put("leido", leido); m.put("created_at", OffsetDateTime.now().toString());
        return m;
    }

    private Map<String, Object> oferta(String userId, String propId, String numero, double importe, double inicial,
                                       String formaPago, String nombre, String email, String tel, String estado, double arras) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("user_id", userId); o.put("propiedad_id", propId); o.put("numero_oferta", numero);
        o.put("importe", importe); o.put("importe_inicial", inicial); o.put("forma_pago", formaPago);
        o.put("nombre_ofertante", nombre); o.put("email_ofertante", email); o.put("telefono_ofertante", tel);
        o.put("estado", estado); o.put("deposito_arras", arras); o.put("fecha_oferta", LocalDate.now().toString());
        o.put("created_at", OffsetDateTime.now().toString());
        return o;
    }

    private static Map<String, Object> newProp(String userId, String name, String address, String city,
                                                String postal, String type, String status, double area,
                                                int beds, int baths, int year, double purchase, double current,
                                                double rent, String photo) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("user_id", userId);
        p.put("name", name);
        p.put("address", address);
        p.put("city", city);
        p.put("postal_code", postal);
        p.put("type", type);
        p.put("status", status);
        p.put("area_m2", area);
        p.put("bedrooms", beds);
        p.put("bathrooms", baths);
        p.put("year_built", year);
        p.put("purchase_price", purchase);
        p.put("current_value", current);
        p.put("rental_income", rent);
        p.put("featured_photo", photo);
        p.put("publicada", "Libre".equals(status));
        p.put("created_at", OffsetDateTime.now().toString());
        return p;
    }
}
