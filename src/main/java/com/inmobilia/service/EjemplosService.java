package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Datos de ejemplo para enseñar la app a clientes: llena todas las herramientas (cartera, leads,
 * visitas, embudos, marketing, finanzas, documentos, equipo...) con una inmobiliaria ficticia
 * de Ciudad de México coherente entre sí.
 *
 * Cada fila sembrada se anota en ejemplos_demo (V87): "Quitar ejemplos" borra exactamente esas
 * filas y lo que cuelgue de ellas, nunca los datos reales de la cuenta. Emails @example.com
 * (EmailService no envía a esos dominios) y teléfonos inventados.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EjemplosService {

    private final SupabaseService supabase;
    private final DemoDataService demo;

    public record Estado(boolean enCurso, int filas, String mensaje, Map<String, Object> informe) {}

    private final Map<String, Estado> estados = new ConcurrentHashMap<>();

    public Estado estado(String orgId) {
        return estados.getOrDefault(orgId, new Estado(false, 0, null, Map.of()));
    }

    /** Filas de ejemplo que tiene ahora la cuenta. */
    public int filasCargadas(String orgId) {
        return supabase.selectFilter("ejemplos_demo", "select=id&user_id=eq." + orgId + "&tabla=not.in.(_inicio,_error)&limit=5000").size();
    }

    /** Filas de ejemplo por tabla (para el diagnóstico de la pantalla de ejemplos). */
    public Map<String, Integer> porTabla(String orgId) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map<String, Object> r : supabase.selectFilter("ejemplos_demo", "select=tabla&user_id=eq." + orgId + "&limit=5000")) {
            String t = String.valueOf(r.get("tabla"));
            if (!t.startsWith("_")) out.merge(t, 1, Integer::sum);
        }
        return out;
    }

    /** Lo que no se pudo cargar la última vez (se guarda en la base: sobrevive a un reinicio). */
    public List<String> errores(String orgId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : supabase.selectFilter("ejemplos_demo", "select=registro_id&user_id=eq." + orgId + "&tabla=eq._error&limit=200"))
            out.add(String.valueOf(r.get("registro_id")));
        return out;
    }

    @Async
    public void cargar(String orgId) {
        cargarAhora(orgId);
    }

    public Map<String, Object> cargarAhora(String orgId) {
        if (estado(orgId).enCurso()) return Map.of("error", "Ya se están cargando");
        estados.put(orgId, new Estado(true, 0, "Cargando ejemplos…", Map.of()));
        try {
            if (filasCargadas(orgId) > 0) {
                return fin(orgId, "Ya hay ejemplos cargados. Quítalos primero si quieres volver a cargarlos.", Map.of());
            }
            // Marca de inicio: si no se puede guardar, falta la tabla de registro (migración V87)
            Map<String, Object> inicio = supabase.insert("ejemplos_demo",
                    Map.of("user_id", orgId, "tabla", "_inicio", "registro_id", "-"));
            if (inicio == null) {
                // Antes cualquier fallo se atribuía a la V87; casi siempre era la conexión o la clave
                String error = supabase.lastError() == null ? "" : supabase.lastError();
                boolean faltaTabla = error.contains("ejemplos_demo") && (error.contains("does not exist")
                        || error.contains("Could not find") || error.contains("relation"));
                return fin(orgId, faltaTabla
                        ? "Falta ejecutar la migración V87 en Supabase (tabla ejemplos_demo)."
                        : "No se pudo guardar en Supabase: " + (error.isBlank() ? "sin respuesta" : error.length() > 200 ? error.substring(0, 200) + "…" : error)
                          + ". Si acabas de cambiar la clave de Supabase, la versión nueva tiene que estar desplegada.", Map.of());
            }
            Siembra s = new Siembra(orgId);
            try {
                s.bloque("inmuebles y leads", () -> s.informe.putAll(demo.seedRichDemo(orgId, s::anotar)));
                s.todo();
            } finally {
                s.volcar();
                supabase.delete("ejemplos_demo", String.valueOf(inicio.get("id")));
            }
            return fin(orgId, "Ejemplos cargados: " + s.total + " registros en todas las herramientas.", s.informe);
        } catch (Exception e) {
            log.error("Error cargando ejemplos de {}: {}", orgId, e.getMessage(), e);
            return fin(orgId, "Error cargando ejemplos: " + e.getMessage(), Map.of());
        }
    }

    private Map<String, Object> fin(String orgId, String mensaje, Map<String, Object> informe) {
        int filas = informe.isEmpty() ? 0 : filasCargadas(orgId);
        estados.put(orgId, new Estado(false, filas, mensaje, informe));
        Map<String, Object> r = new LinkedHashMap<>(informe);
        r.put("mensaje", mensaje);
        return r;
    }

    /** Tablas donde la app (o el usuario durante la demo) crea filas colgando de leads o inmuebles de ejemplo. */
    private static final String[][] HIJOS = {
            {"tareas", "lead_id"}, {"tareas", "propiedad_id"}, {"agente_acciones", "lead_id"}, {"agente_acciones", "propiedad_id"},
            {"lead_interacciones", "lead_id"}, {"matches", "lead_id"}, {"matches", "propiedad_id"},
            {"visitas", "lead_id"}, {"visitas", "propiedad_id"}, {"notas_voz", "lead_id"}};

    @Async
    public void quitarEnSegundoPlano(String orgId) {
        if (estado(orgId).enCurso()) return;
        estados.put(orgId, new Estado(true, 0, "Quitando ejemplos…", Map.of()));
        try {
            quitar(orgId);
        } catch (Exception e) {
            log.error("Error quitando ejemplos de {}: {}", orgId, e.getMessage(), e);
            estados.put(orgId, new Estado(false, 0, "Error quitando ejemplos: " + e.getMessage(), Map.of()));
        }
    }

    /** Ids de los inmuebles de ejemplo (para no publicarlos nunca en portales). */
    public Set<String> inmueblesDeEjemplo(String orgId) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> r : supabase.selectFilter("ejemplos_demo", "user_id=eq." + orgId + "&tabla=eq.propiedades&limit=1000"))
            ids.add(String.valueOf(r.get("registro_id")));
        return ids;
    }

    /** Quita todas las filas de ejemplo (y lo que se haya creado colgando de ellas). Nada más. */
    public Map<String, Object> quitar(String orgId) {
        List<Map<String, Object>> reg = supabase.selectFilter("ejemplos_demo",
                "user_id=eq." + orgId + "&order=id.desc&limit=5000");
        Set<String> leads = new LinkedHashSet<>(), props = new LinkedHashSet<>();
        for (Map<String, Object> r : reg) {
            if ("leads".equals(r.get("tabla"))) leads.add(String.valueOf(r.get("registro_id")));
            if ("propiedades".equals(r.get("tabla"))) props.add(String.valueOf(r.get("registro_id")));
        }
        Set<String> anotados = new HashSet<>();
        reg.forEach(r -> anotados.add(r.get("tabla") + ":" + r.get("registro_id")));

        int hijos = 0;
        for (String[] h : HIJOS) {
            Set<String> ids = "lead_id".equals(h[1]) ? leads : props;
            for (List<String> lote : lotes(ids)) {
                for (Map<String, Object> f : supabase.selectFilter(h[0], "user_id=eq." + orgId + "&" + h[1] + "=in.(" + String.join(",", lote) + ")&limit=1000")) {
                    String id = String.valueOf(f.get("id"));
                    if (!anotados.contains(h[0] + ":" + id) && supabase.delete(h[0], id, orgId)) hijos++;
                }
            }
        }

        int borradas = 0, fallidas = 0;
        for (Map<String, Object> r : reg) { // de la última a la primera: hijos antes que padres
            String tabla = String.valueOf(r.get("tabla"));
            if (tabla.startsWith("_")) { /* marcas y errores: solo se quita la anotación */ }
            else if (supabase.delete(tabla, String.valueOf(r.get("registro_id")))) borradas++;
            else fallidas++;
            supabase.delete("ejemplos_demo", String.valueOf(r.get("id")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quitadas", borradas);
        out.put("relacionadas", hijos);
        out.put("no_quitadas", fallidas);
        estados.put(orgId, new Estado(false, 0, fallidas == 0
                ? "Ejemplos quitados (" + (borradas + hijos) + " registros)."
                : "Quitados " + borradas + "; " + fallidas + " no se pudieron quitar.", out));
        return out;
    }

    private static List<List<String>> lotes(Set<String> ids) {
        List<String> l = ids.stream().filter(s -> s.matches("[0-9a-fA-F-]{1,64}")).toList();
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i += 100) out.add(l.subList(i, Math.min(i + 100, l.size())));
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Siembra
    // ═════════════════════════════════════════════════════════════════════════

    /** Tablas sin columna created_at (usan su propia fecha: iniciada_at, enviado_at...). */
    private static final Set<String> SIN_CREATED_AT = Set.of("chatbot_conversaciones", "newsletter_envios",
            "nps_respuestas", "referidos", "sms_mensajes");

    private static final String FOTO = "https://images.unsplash.com/photo-";
    private static final String F = "?w=1200&auto=format&fit=crop&q=80";

    final class Siembra {
        final String org;
        final Map<String, Object> informe = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> guardadas = new HashMap<>();
        final List<Map<String, Object>> pendientes = new ArrayList<>();
        final Set<String> tablasConError = new HashSet<>();
        int total = 0;

        Siembra(String org) { this.org = org; }

        /**
         * Lo que no se pudo cargar queda anotado en ejemplos_demo (tabla "_error") para verlo en
         * la pantalla de ejemplos aunque el servidor se reinicie. Una vez por tabla.
         */
        void error(String donde, String motivo) {
            if (!tablasConError.add(donde)) return;
            String texto = donde + ": " + (motivo == null ? "no guardado" : motivo);
            pendientes.add(new LinkedHashMap<>(Map.of("user_id", org, "tabla", "_error",
                    "registro_id", texto.length() > 400 ? texto.substring(0, 400) : texto)));
        }

        /** Cada bloque por separado: si uno falla, los demás se cargan igual. */
        void bloque(String nombre, Runnable r) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Ejemplos: el bloque {} falló: {}", nombre, e.toString());
                informe.put("bloque_" + nombre + "_error", e.toString());
                error("bloque " + nombre, e.toString());
            }
        }

        void anotar(String tabla, Map<String, Object> fila) {
            Object id = fila.get("id");
            if (id == null) return;
            guardadas.computeIfAbsent(tabla, k -> new ArrayList<>()).add(fila);
            pendientes.add(new LinkedHashMap<>(Map.of("user_id", org, "tabla", tabla, "registro_id", String.valueOf(id))));
            total++;
            if (pendientes.size() >= 20) volcar();
            estados.put(org, new Estado(true, total, "Cargando ejemplos… " + total + " registros", Map.of()));
        }

        void volcar() {
            if (pendientes.isEmpty()) return;
            if (supabase.insertarVarios("ejemplos_demo", pendientes)) pendientes.clear();
            else log.warn("No se pudo anotar el registro de ejemplos: {}", supabase.lastError());
        }

        Map<String, Object> ins(String tabla, Map<String, Object> fila) {
            if (SIN_CREATED_AT.contains(tabla)) fila.remove("created_at");
            fila = latam(fila);
            Map<String, Object> g = supabase.insert(tabla, fila);
            if (g != null && !g.isEmpty()) {
                informe.merge(tabla, 1, (a, b) -> (Integer) a + (Integer) b);
                anotar(tabla, g);
                return g;
            }
            informe.putIfAbsent(tabla + "_error", supabase.lastError() != null ? supabase.lastError() : "no guardado");
            error(tabla, supabase.lastError());
            return null;
        }

        String id(String tabla, int i) {
            List<Map<String, Object>> l = guardadas.getOrDefault(tabla, List.of());
            return i >= 0 && i < l.size() ? String.valueOf(l.get(i).get("id")) : null;
        }

        String nombreProp(int i) {
            List<Map<String, Object>> l = guardadas.getOrDefault("propiedades", List.of());
            return i < l.size() ? String.valueOf(l.get(i).get("name")) : "Inmueble";
        }

        /** Importes de ejemplo (en euros) a la moneda de la inmobiliaria; IVA/IGV/ITBIS… del país. */
        Map<String, Object> latam(Map<String, Object> fila) {
            Map<String, Object> m = demo.enMoneda(fila, org);
            com.inmobilia.util.Pais pais = demo.pais(org);
            if (m.containsKey("moneda")) m.put("moneda", pais.moneda());
            double tasa = pais.tipoImpuesto();
            if (m.get("iva_pct") instanceof Number pct && pct.doubleValue() > 0) {
                m.put("iva_pct", tasa);
                if (m.get("base_imponible") instanceof Number base) {
                    double iva = Math.round(base.doubleValue() * tasa) / 100.0;
                    double irpf = m.get("irpf_amount") instanceof Number r ? r.doubleValue() : 0;
                    m.put("iva_amount", iva);
                    if (m.containsKey("total")) m.put("total", Math.round((base.doubleValue() + iva - irpf) * 100) / 100.0);
                }
                if (m.get("importe") instanceof Number imp && m.containsKey("iva_importe")) {
                    m.put("iva_importe", Math.round(imp.doubleValue() * tasa / (100 + tasa) * 100) / 100.0);
                }
            }
            return m;
        }

        /** Moneda de la inmobiliaria ("MXN", "COP"…). */
        String mon() { return demo.pais(org).moneda(); }

        /** Importe de ejemplo en euros, convertido y escrito en la moneda de la inmobiliaria: "13.000.000 MXN". */
        String m(double eur) {
            com.inmobilia.util.Pais pais = demo.pais(org);
            return String.format(pais.locale(), "%,.0f %s", eur * demo.factorEur(org), pais.moneda());
        }

        String ahora() { return OffsetDateTime.now().toString(); }
        String dia(int d) { return LocalDate.now().plusDays(d).toString(); }
        String momento(int dias, int hora, int min) {
            return ZonedDateTime.now(com.inmobilia.util.PaisActual.get().zonaId()).plusDays(dias).withHour(hora).withMinute(min)
                    .withSecond(0).withNano(0).toOffsetDateTime().toString();
        }
        String token() { return UUID.randomUUID().toString().replace("-", "").substring(0, 20); }

        Map<String, Object> f(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
            return m;
        }

        /** f(...) con user_id y created_at. */
        Map<String, Object> u(Object... kv) {
            Map<String, Object> m = f(kv);
            m.put("user_id", org);
            m.putIfAbsent("created_at", ahora());
            return m;
        }

        void todo() {
            bloque("inmuebles", this::completarInmuebles);
            bloque("cartera", this::cartera);
            bloque("comercial", this::comercial);
            bloque("visitas y captación", this::visitasYCaptacion);
            bloque("marketing", this::marketing);
            bloque("finanzas", this::finanzas);
            bloque("documentos", this::documentos);
            bloque("equipo", this::equipo);
            bloque("inquilinos", this::portalInquilinos);
            bloque("inteligencia", this::inteligencia);
            bloque("rentabilidad", this::rentabilidad);
        }

        // ── Cartera: mapa, portales, galería, obra nueva, tours, staging ───────
        void completarInmuebles() {
            double[][] coords = {{19.4361, -99.1352}, {19.4416, -99.1305}, {19.4273, -99.1284}, {19.4493, -99.1124},
                    {19.5087, -99.3031}, {19.4243, -99.1304}, {19.4523, -99.1219}, {19.4346, -99.1029},
                    {19.4728, -99.1094}, {19.4976, -98.7937}, {19.4148, -99.1229}, {19.3215, -99.1623}};
            String[] desc = {
                    "Depto. exterior en plena Reforma, 3 dormitorios y 2 baños, reformado en 2021. Ascensor y portero.",
                    "Depto. luminoso en Roma Norte, 2 dormitorios, ideal primera vivienda o inversión. Calle peatonal.",
                    "Local comercial a pie de calle en Insurgentes, 120 m² con escaparate de 8 metros. Alquilado a franquicia.",
                    "Penthouse exclusivo en Masaryk con terraza de 25 m², 3 dormitorios, plaza de garaje y bodega.",
                    "Casa independiente en Santa Fe, 5 dormitorios, parcela de 500 m² con piscina y jardín.",
                    "Estudio reformado en Coyoacán, 38 m², muy rentable para alquiler. Ascensor.",
                    "Oficina diáfana en Del Valle, 95 m², 2 aseos, aire acondicionado y fibra.",
                    "Depto. junto al Parque México, 3 dormitorios, terraza, ascensor y garaje opcional.",
                    "Dúplex en Narvarte con terraza, 4 dormitorios y garaje. Urbanización con piscina.",
                    "Casa en el centro histórico de Coyoacán, 4 dormitorios, patio. Alquilada.",
                    "Loft de diseño en Escandón, 70 m², techos altos, amueblado.",
                    "Nave logística en Tlalnepantla, 400 m², muelle de carga, oficina y parking."};
            boolean[] garaje = {false, false, false, true, true, false, false, true, true, false, false, true};
            boolean[] ascensor = {true, false, false, true, false, true, true, true, true, false, true, false};
            boolean[] terraza = {false, false, false, true, true, false, false, true, true, true, false, false};
            boolean[] piscina = {false, false, false, false, true, false, false, false, true, false, false, false};
            List<Map<String, Object>> props = guardadas.getOrDefault("propiedades", List.of());
            for (int i = 0; i < props.size() && i < coords.length; i++) {
                Map<String, Object> upd = f("lat", coords[i][0], "lng", coords[i][1],
                        "reference", String.format("INM-%04d", i + 1), "descripcion", desc[i],
                        "operacion", "venta", "provincia", "Ciudad de México", "garaje", garaje[i], "ascensor", ascensor[i],
                        "terraza", terraza[i], "piscina", piscina[i], "publicar_portales", false,
                        "publicada", true);   // ficha pública y web de la inmobiliaria (no portales)
                supabase.update("propiedades", String.valueOf(props.get(i).get("id")), upd, org);
            }
            // Galería de fotos (ficha pública, portales, comparador)
            String[][] galeria = {
                    {"1", "1556228453-efd6c1ff04f6", "1484154218962-a197022b5858", "1505691938895-1758d7feb511"},
                    {"3", "1600607687939-ce8a6c25118c", "1586023492125-27b2c045efd7", "1600585154340-be6161a56a0c"},
                    {"4", "1600596542815-ffad4c1539a9", "1600585154340-be6161a56a0c", "1556228453-efd6c1ff04f6"}};
            for (String[] g : galeria) {
                String pid = id("propiedades", Integer.parseInt(g[0]));
                if (pid == null) continue;
                for (int k = 1; k < g.length; k++) {
                    Map<String, Object> a = f("user_id", org, "org_id", org, "entity_type", "propiedad", "entity_id", pid,
                            "name", "Foto " + k + ".jpg", "url", FOTO + g[k] + F, "file_type", "image", "size_kb", 420, "created_at", ahora());
                    ins("attachments", a);
                }
            }
            // Histórico de precios
            ins("precio_historico", f("user_id", org, "propiedad_id", id("propiedades", 3), "precio_anterior", 760000, "precio_nuevo", 720000,
                    "diferencia", -40000, "diferencia_pct", -5.26, "motivo", "Ajuste a mercado tras 60 días", "created_at", momento(-20, 10, 0)));
            ins("precio_historico", f("user_id", org, "propiedad_id", id("propiedades", 1), "precio_anterior", 210000, "precio_nuevo", 220000,
                    "diferencia", 10000, "diferencia_pct", 4.76, "motivo", "Reforma de cocina terminada", "created_at", momento(-8, 12, 0)));

            // Obra nueva
            Map<String, Object> promo = ins("promociones", u("nombre", "Residencial Jardines de Valdebebas", "slug", "jardines-valdebebas-" + token().substring(0, 6),
                    "promotora", "Valdebebas Homes SL", "arquitecto", "Estudio Lamela", "constructora", "Construcciones Ortiz",
                    "direccion", "Av. Insurgentes Sur 1200", "municipio", "Ciudad de México", "provincia", "Ciudad de México", "codigo_postal", "03100",
                    "lat", 19.4976, "lng", -99.0497, "tipo", "plurifamiliar", "num_unidades", 48, "num_unidades_vendidas", 31,
                    "fecha_inicio_obras", dia(-240), "fecha_entrega", dia(420), "fase", "en_construccion",
                    "precio_desde", 345000, "precio_hasta", 690000,
                    "memoria_calidades", "Suelo radiante, aerotermia, carpintería con rotura de puente térmico, domótica y piscina comunitaria.",
                    "render_principal_url", FOTO + "1600596542815-ffad4c1539a9" + F, "seo_descripcion", "Viviendas de 2 a 4 dormitorios en Valdebebas con piscina y zonas verdes.",
                    "publicada", true, "leads_capturados", 54, "visitas_count", 1320));
            if (promo != null) {
                Object[][] uds = {{"A", 1, "A", "2D", 2, 2, 78.5, 95.0, 12.0, "Sur", 345000, "vendida"},
                        {"A", 2, "B", "3D", 3, 2, 98.0, 118.0, 15.0, "Sureste", 425000, "reservada"},
                        {"A", 4, "A", "3D", 3, 2, 101.0, 121.0, 18.0, "Sur", 455000, "disponible"},
                        {"B", 3, "C", "4D", 4, 3, 128.0, 152.0, 22.0, "Oeste", 560000, "disponible"},
                        {"B", 6, "A", "Ático", 3, 2, 110.0, 132.0, 60.0, "Sur", 690000, "disponible"},
                        {"B", 1, "D", "2D", 2, 1, 70.0, 86.0, 8.0, "Norte", 352000, "vendida"}};
                for (Object[] x : uds) {
                    ins("promocion_unidades", f("promocion_id", promo.get("id"), "bloque", x[0], "planta", x[1], "letra", x[2],
                            "tipologia", x[3], "num_habitaciones", x[4], "num_banos", x[5], "superficie_util", x[6],
                            "superficie_construida", x[7], "terraza_m2", x[8], "orientacion", x[9], "precio", x[10],
                            "plaza_garaje", true, "trastero", true, "estado", x[11], "created_at", ahora()));
                }
            }

            // Tour virtual (visor de ejemplo de Matterport) y home staging
            ins("tours_virtuales", u("propiedad_id", id("propiedades", 3), "titulo", "Tour 3D · Penthouse Masaryk 100",
                    "descripcion", "Recorrido completo: salón, cocina, 3 dormitorios y terraza.", "public_token", token(),
                    "embed_url", "https://my.matterport.com/show/?m=SxQL3iGyoDo", "proveedor", "matterport",
                    "activo", true, "visitas_count", 214));
            ins("staging_proyectos", u("propiedad_id", id("propiedades", 1), "nombre", "Salón Roma Norte · estilo nórdico",
                    "imagen_original_url", FOTO + "1493809842364-78817add7ffb" + F, "imagen_staged_url", FOTO + "1586023492125-27b2c045efd7" + F,
                    "estilo", "nordico", "tipo_habitacion", "salon", "estado", "completado", "coste", 0, "completed_at", ahora()));
            ins("staging_proyectos", u("propiedad_id", id("propiedades", 10), "nombre", "Loft Escandón · industrial",
                    "imagen_original_url", FOTO + "1522708323590-d24dbb6b0267" + F, "imagen_staged_url", FOTO + "1600607687939-ce8a6c25118c" + F,
                    "estilo", "industrial", "tipo_habitacion", "salon", "estado", "completado", "coste", 0, "completed_at", ahora()));
        }

        // ── Contratos, propietarios, mantenimiento, agenda, extractos ──────────
        void cartera() {
            ins("contratos", u("property_name", nombreProp(0), "tenant_name", "Juan Pérez García", "start_date", "2024-01-15", "end_date", "2029-01-14",
                    "rent", 1100, "deposit", 2200, "ipc_revision", true, "status", "Activo", "signed_date", "2024-01-10",
                    "terms", "Contrato de vivienda habitual (LAU). Actualización anual según índice de referencia.",
                    "estado", "activo", "numero", "CT-2024-001", "fecha_inicio", "2024-01-15", "fecha_fin", "2029-01-14",
                    "renta_mensual", 1100, "fianza", 2200, "propiedad_id", id("propiedades", 0), "inquilino_id", id("inquilinos", 0)));
            ins("contratos", u("property_name", nombreProp(2), "tenant_name", "Cosméticos PIO S.A. de C.V.", "start_date", "2023-06-01", "end_date", "2028-05-31",
                    "rent", 1850, "deposit", 5550, "ipc_revision", true, "status", "Activo", "signed_date", "2023-05-20",
                    "terms", "Arrendamiento de uso distinto de vivienda. 3 meses de depósito. Obras de adecuación a cargo del arrendatario.",
                    "estado", "activo", "numero", "CT-2023-014", "fecha_inicio", "2023-06-01", "fecha_fin", "2028-05-31",
                    "renta_mensual", 1850, "fianza", 5550, "propiedad_id", id("propiedades", 2), "inquilino_id", id("inquilinos", 1)));
            ins("contratos", u("property_name", nombreProp(5), "tenant_name", "Sara López Vega", "start_date", "2025-03-01", "end_date", dia(40),
                    "rent", 800, "deposit", 1600, "ipc_revision", false, "status", "Activo", "signed_date", "2025-02-25",
                    "terms", "Contrato de temporada (curso académico). Aval de los padres.",
                    "estado", "activo", "numero", "CT-2025-003", "fecha_inicio", "2025-03-01", "fecha_fin", dia(40),
                    "renta_mensual", 800, "fianza", 1600, "propiedad_id", id("propiedades", 5), "inquilino_id", id("inquilinos", 2)));

            ins("propietarios", u("nombre", "Carmen Ruiz Molina", "dni", "GOMA810202HDFRRN01", "email", "carmen.ruiz@example.com",
                    "telefono", "+52 55 1110 0200", "direccion", "Calle Durango 15, Ciudad de México", "iban", "002010077777777771"));
            ins("propietarios", u("nombre", "Inversiones Insurgentes S.A. de C.V.", "dni", "EJE876543AB1", "email", "info@invatocha.example.com",
                    "telefono", "+52 55 1300 4050", "direccion", "Av. Insurgentes Sur 88, Ciudad de México"));
            ins("propietarios", u("nombre", "Rosa Delgado", "dni", "GOMA820303HDFRRN02", "email", "rosa.delgado@example.com",
                    "telefono", "+52 55 2230 0400", "direccion", "Calle Higuera 12, Coyoacán"));

            Object[][] mant = {{"Caldera sin agua caliente", "El inquilino reporta agua tibia desde el lunes.", 0, 3, "Pendiente", "Fontanería Ruiz", 180, 2},
                    {"Pintura fachada escaparate", "Repintar el frontal antes de la renovación del contrato.", 2, 2, "En curso", "Pinturas CDMX", 650, 6},
                    {"Revisión aire acondicionado", "Mantenimiento anual antes del verano.", 6, 1, "Completado", "Clima Express", 95, -10}};
            for (Object[] m : mant) {
                ins("mantenimiento", u("title", m[0], "description", m[1], "property_name", nombreProp((Integer) m[2]),
                        "priority", m[3], "status", m[4], "assigned_to", m[5], "cost", m[6],
                        "scheduled_date", dia((Integer) m[7]), "completed_date", "Completado".equals(m[4]) ? dia((Integer) m[7]) : null));
            }

            Object[][] ev = {{"Firma de promesa · Casa Santa Fe", 3, "12:00", "Firma", 4, "Notaría 45, Av. Presidente Masaryk 20"},
                    {"Reunión con Nostrum Retail", 1, "13:00", "Reunión", 2, "Oficina"},
                    {"Sesión de fotos profesional", 5, "10:00", "Marketing", 7, "Dúplex Narvarte"},
                    {"Renovación seguro hogar", 12, "09:00", "Recordatorio", 0, "—"}};
            for (Object[] e : ev) {
                ins("eventos", u("title", e[0], "date", dia((Integer) e[1]), "time", e[2], "type", e[3],
                        "property_name", nombreProp((Integer) e[4]), "location", e[5], "attendees", "Equipo comercial", "reminder_days", 1));
            }

            String prop0 = id("propietarios", 0);
            if (prop0 != null) {
                ins("extractos_propietario", u("propietario_id", prop0, "periodo_inicio", LocalDate.now().minusMonths(1).withDayOfMonth(1).toString(),
                        "periodo_fin", LocalDate.now().withDayOfMonth(1).minusDays(1).toString(),
                        "total_rentas", 1100, "total_otros_ingresos", 0, "total_ingresos", 1100, "total_comision_inmobiliaria", 88,
                        "total_irpf_retenido", 0, "total_iva", 18.48, "total_gastos_repercutibles", 0, "total_seguros", 0,
                        "total_comunidad", 95, "total_mantenimiento", 0, "total_otros_gastos", 0, "total_gastos", 201.48,
                        "importe_liquidar", 898.52, "estado", "pendiente", "notas", "Liquidación mensual de rentas · Depto. Reforma."));
            }

            ins("seguros", u("propiedad_id", id("propiedades", 0), "tipo", "hogar", "aseguradora", "Mapfre", "numero_poliza", "HG-2291-4471",
                    "tomador", "Carmen Ruiz Molina", "capital_asegurado", 180000, "prima_anual", 285, "forma_pago", "anual",
                    "fecha_inicio", dia(-200), "fecha_vencimiento", dia(165), "renovacion_automatica", true, "estado", "activa",
                    "coberturas", "Continente, contenido, responsabilidad civil y daños por agua."));
            Map<String, Object> impago = ins("seguros", u("propiedad_id", id("propiedades", 2), "tipo", "impago_alquiler", "aseguradora", "Arag",
                    "numero_poliza", "IA-55120", "tomador", "Inversiones Insurgentes S.A. de C.V.", "capital_asegurado", 22200, "prima_anual", 540,
                    "forma_pago", "anual", "fecha_inicio", dia(-90), "fecha_vencimiento", dia(275), "renovacion_automatica", true, "estado", "activa",
                    "coberturas", "12 meses de renta, defensa jurídica y daños por vandalismo."));
            if (impago != null) {
                ins("siniestros", u("seguro_id", impago.get("id"), "fecha_siniestro", dia(-30), "fecha_declaracion", dia(-29),
                        "numero_expediente", "SIN-2026-0871", "tipo_dano", "agua", "descripcion", "Filtración desde el departamento superior en el almacén.",
                        "importe_reclamado", 1450, "importe_indemnizado", 1200, "estado", "resuelto", "perito_asignado", "Perito R. Gil",
                        "fecha_resolucion", dia(-5)));
            }
        }

        // ── Comercial: oportunidades, embudos, matches, ofertas, selección ────
        void comercial() {
            Object[][] op = {{"Edificio Narvarte (6 departamentos)", "Av. Universidad 210", "Ciudad de México", 1850000, 5.8, 6.4, 520, "Residencial", "Análisis", "Off-market", "Gestoría Vidal", 9.5, 1, "Rentabilidad bruta 6,4 %. Reforma de fachada pendiente."},
                    {"Local esquina Doctores", "Calle Dr. Vértiz 45", "Ciudad de México", 420000, 6.9, 7.2, 140, "Comercial", "Negociación", "Banco (cartera adjudicada)", "Banco adjudicado", 11.0, 2, "Alquilado a farmacia hasta 2030."},
                    {"Terreno Iztapalapa", "Calle Ermita 3", "Ciudad de México", 760000, 0, 0, 900, "Suelo", "Descartado", "Portal", "Propietario particular", 14.0, 3, "Edificabilidad insuficiente."},
                    {"Depto. para remodelar Coyoacán", "Calle Allende 18", "Ciudad de México", 245000, 5.1, 6.0, 68, "Residencial", "Nuevo", "Captación propia", "Herencia Sanz", 10.5, 2, "Tres herederos; piden valoración antes de decidir."},
                    {"Oficinas Santa Fe", "Av. Vasco de Quiroga 3000", "Ciudad de México", 1320000, 6.2, 6.8, 610, "Oficinas", "Cerrado", "Colaborador", "Family office López", 8.7, 1, "Compra cerrada en escritura el mes pasado."}};
            for (Object[] o : op) {
                ins("oportunidades", u("property_name", o[0], "address", o[1], "city", o[2], "price", o[3], "cap_rate", o[4],
                        "gross_yield", o[5], "area_m2", o[6], "type", o[7], "status", o[8], "source", o[9], "contact", o[10],
                        "expected_irr", o[11], "priority", o[12], "notes", o[13]));
            }

            // Embudo de ofertas: en el que ya tenga la cuenta, o uno de ejemplo
            List<Map<String, Object>> pipes = supabase.selectFilter("pipelines", "user_id=eq." + org + "&tipo=eq.ofertas&limit=1");
            Map<String, Object> pipe = pipes.isEmpty() ? null : pipes.get(0);
            if (pipe == null) {
                pipe = ins("pipelines", u("nombre", "Ofertas en curso", "tipo", "ofertas", "descripcion", "Gestión de ofertas y contraofertas",
                        "columnas", List.of(col("recibida", "📥 Recibida", "#3b82f6", 1), col("presentada", "📤 Presentada", "#8b5cf6", 2),
                                col("negociando", "💬 Negociando", "#f59e0b", 3), col("aceptada", "✅ Aceptada", "#10b981", 4),
                                col("arras", "📝 Con promesa", "#06b6d4", 5), col("escriturada", "🏆 Escriturada", "#0d9488", 6),
                                col("caida", "💔 Caída", "#ef4444", 7))));
            }
            if (pipe != null) {
                Object[][] cards = {{"negociando", "Penthouse Masaryk · David Torres", "Oferta " + m(690000) + " vs " + m(720000), 690000, 60, 6, 3},
                        {"recibida", "Local Insurgentes · Nostrum Retail", "Oferta al contado " + m(365000), 365000, 40, 3, 2},
                        {"arras", "Casa Santa Fe · Pablo Moreno", "Promesa firmada · anticipo " + m(30000), 650000, 90, 4, 4},
                        {"presentada", "Depto. Roma Norte · Ana García", "Pide rebaja del 5 %", 209000, 50, 0, 1},
                        {"escriturada", "Depto. Condesa · Familia Soler", "Escritura firmada", 505000, 100, 7, 7}};
                int orden = 0;
                for (Object[] c : cards) {
                    ins("pipeline_cards", u("pipeline_id", pipe.get("id"), "columna_key", c[0], "orden", orden++, "titulo", c[1],
                            "subtitulo", c[2], "valor", c[3], "probabilidad", c[4], "lead_id", id("leads", (Integer) c[5]),
                            "propiedad_id", id("propiedades", (Integer) c[6]), "fecha_entrada_columna", momento(-orden * 2, 10, 0),
                            "archivada", false, "movimientos", List.of()));
                }
            }

            // Preferencias de búsqueda y matches
            Map<String, Object> pref = ins("preferencias_busqueda", u("lead_id", id("leads", 6), "operacion", "venta",
                    "tipo_propiedad", List.of("atico", "piso"), "presupuesto_min", 550000, "presupuesto_max", 780000,
                    "zonas", List.of("Polanco", "Del Valle", "Condesa"), "municipios", List.of("Ciudad de México"), "habitaciones_min", 3, "banos_min", 2,
                    "superficie_min", 95, "requiere_terraza", true, "requiere_ascensor", true, "auto_match", true, "notificar_email", true));
            ins("preferencias_busqueda", u("lead_id", id("leads", 5), "operacion", "venta", "tipo_propiedad", List.of("piso", "estudio"),
                    "presupuesto_max", 230000, "zonas", List.of("Coyoacán", "Del Valle", "Escandón"), "municipios", List.of("Ciudad de México"),
                    "habitaciones_min", 1, "requiere_ascensor", true, "auto_match", true, "notas", "Inversión: busca rentabilidad bruta superior al 5 %."));
            if (pref != null) {
                ins("matches", u("preferencia_id", pref.get("id"), "propiedad_id", id("propiedades", 3), "lead_id", id("leads", 6), "score", 94,
                        "razones", List.of("Penthouse con terraza", "Zona Polanco", "Dentro de presupuesto", "3 dormitorios"),
                        "descartes", List.of(), "estado", "interesado"));
                ins("matches", u("preferencia_id", pref.get("id"), "propiedad_id", id("propiedades", 7), "lead_id", id("leads", 6), "score", 81,
                        "razones", List.of("Terraza", "Condesa", "3 dormitorios"), "descartes", List.of("No es penthouse"), "estado", "nuevo"));
                ins("matches", u("preferencia_id", pref.get("id"), "propiedad_id", id("propiedades", 8), "lead_id", id("leads", 6), "score", 72,
                        "razones", List.of("Terraza", "4 dormitorios"), "descartes", List.of("Narvarte fuera de sus zonas"), "estado", "nuevo"));
            }

            // Portal del comprador: selección enviada a David Torres
            Map<String, Object> sel = ins("selecciones_comprador", u("lead_id", id("leads", 6), "token", "c" + token().substring(0, 15),
                    "titulo", "Selección para David · penthouses y departamentos con terraza", "comprador_nombre", "David Torres Gómez",
                    "mensaje_agente", "Hola David, te dejo los tres que mejor encajan. Marca los que quieras visitar y te organizo la ruta.",
                    "activo", true, "vistas", 9));
            if (sel != null) {
                Object[][] items = {{3, "visita", "Me encanta la terraza"}, {7, "favorito", null}, {8, "descartado", "Me queda lejos del trabajo"}};
                int orden = 0;
                for (Object[] it : items) {
                    ins("seleccion_items", u("seleccion_id", sel.get("id"), "propiedad_id", id("propiedades", (Integer) it[0]), "orden", orden++,
                            "reaccion", it[1], "comentario", it[2], "reaccion_at", momento(-1, 20, 15)));
                }
            }

            // Marketplace (swipes de compradores)
            Object[][] sw = {{3, "superlike", "Elena Vargas", "elena.vargas@example.com", "Polanco", true},
                    {1, "like", "Mario Martín", "mario.martin@example.com", "Roma Norte", true},
                    {4, "like", null, null, "Santa Fe", false}, {10, "pass", null, null, "Escandón", false}};
            for (Object[] s : sw) {
                ins("tinder_swipes", f("agency_id", org, "propiedad_id", id("propiedades", (Integer) s[0]), "accion", s[1],
                        "buyer_device", "móvil", "buyer_nombre", s[2], "buyer_email", s[3], "buyer_zona", s[4],
                        "matched", s[5], "created_at", momento(-2, 21, 0)));
            }

            // Cobroking: invitación pendiente y un inmueble compartido
            ins("cobroking_red", u("nombre_red", "Red CDMX Centro", "comision_default_pct", 50, "estado", "pendiente",
                    "invitacion_token", token(), "invitacion_email", "socios@inmobiliaria-aliada.example.com",
                    "notas", "Agencia aliada para compartir exclusivas al 50 %."));
            ins("cobroking_propiedades", f("propiedad_id", id("propiedades", 4), "user_propietario_id", org, "comision_oferente_pct", 50,
                    "comision_captador_pct", 50, "condiciones", "Honorarios 3 % repartidos al 50 %. Visitas con cita previa.",
                    "visible_solo_red", true, "activa", true, "fecha_inicio", dia(-15), "created_at", ahora()));
        }

        Map<String, Object> col(String key, String label, String color, int orden) {
            return Map.of("key", key, "label", label, "color", color, "orden", orden);
        }

        // ── Visitas, puertas abiertas, hojas de visita, captación ──────────────
        void visitasYCaptacion() {
            Object[][] vis = {{1, 0, "Ana García López", "ana.garcia@example.com", "+52 55 7890 1234", 2, 17, 30, "confirmada", null, null, null},
                    {3, 6, "David Torres Gómez", "david.torres@example.com", "+52 55 5566 6777", 4, 11, 0, "programada", null, null, null},
                    {6, 5, "Lucía Ramos Ortega", "lucia.ramos@example.com", "+52 55 4455 5666", -3, 10, 30, "realizada", 4,
                            "Le encaja la ubicación; pide ver cuotas de mantenimiento.", "Enviar desglose de gastos y proponer segunda visita"},
                    {7, 1, "Roberto Díaz Nieto", "roberto.diaz@example.com", "+52 55 1122 2333", -6, 18, 0, "realizada", 3,
                            "Le parece pequeño el segundo dormitorio.", "Mostrarle el dúplex de Narvarte"}};
            for (Object[] v : vis) {
                int dias = (Integer) v[5];
                ins("visitas", u("propiedad_id", id("propiedades", (Integer) v[0]), "lead_id", id("leads", (Integer) v[1]),
                        "agente_id", org, "visitante_nombre", v[2], "visitante_email", v[3], "visitante_telefono", v[4],
                        "fecha_inicio", momento(dias, (Integer) v[6], (Integer) v[7]), "fecha_fin", momento(dias, (Integer) v[6] + 1, (Integer) v[7]),
                        "duracion_min", 45, "estado", v[8], "tipo", "presencial", "confirmacion_token", token(), "feedback_token", token(),
                        "recordatorio_24h_enviado", true, "recordatorio_1h_enviado", true,
                        "interes_nivel", v[9], "feedback_agente", v[10], "siguiente_paso", v[11],
                        "fecha_siguiente_paso", v[11] == null ? null : dia(2)));
            }

            Map<String, Object> oh = ins("open_houses", u("propiedad_id", id("propiedades", 3), "titulo", "Jornada de puertas abiertas · Penthouse Masaryk",
                    "descripcion", "Visita libre de 11 a 14 h. Aperitivo y valoración gratuita de tu vivienda actual.",
                    "fecha", dia(9), "hora_inicio", "11:00", "hora_fin", "14:00", "publico", true, "pagina_publica_token", token(),
                    "max_asistentes", 40, "requiere_registro", true, "incluye_aperitivo", true, "incluye_valoracion", true,
                    "incluye_financiacion", true, "estado", "programado", "asistentes_count", 3,
                    "foto_portada_url", FOTO + "1560448204-e02f11c3d0e2" + F));
            if (oh != null) {
                Object[][] as = {{"Elena Vargas", "elena.vargas@example.com", "+52 55 7700 1122", 1, "alto"},
                        {"Tomás Jerez", "tomas.jerez@example.com", "+52 55 9930 0211", 0, "medio"},
                        {"Clara Ruiz", "clara.inversora@example.com", "+52 55 9930 0212", 2, "alto"}};
                for (Object[] a : as) {
                    ins("open_house_asistentes", f("open_house_id", oh.get("id"), "nombre", a[0], "email", a[1], "telefono", a[2],
                            "num_acompanantes", a[3], "interes", a[4], "created_at", ahora()));
                }
            }

            ins("hojas_visita", u("propiedad_id", id("propiedades", 6), "visitante_nombre", "Lucía Ramos Ortega", "visitante_dni", "GOMA830404HDFRRN03",
                    "visitante_telefono", "+52 55 4455 5666", "visitante_email", "lucia.ramos@example.com",
                    "inmueble_texto", "Oficina Del Valle · Calle Pilares 45 · 95 m²", "fecha_visita", dia(-3),
                    "observaciones", "Visita acompañada por el agente. La visitante declara conocer el inmueble a través de esta agencia.",
                    "estado", "firmada"));
            ins("hojas_visita", u("propiedad_id", id("propiedades", 3), "visitante_nombre", "David Torres Gómez", "visitante_telefono", "+52 55 5566 6777",
                    "visitante_email", "david.torres@example.com", "inmueble_texto", "Penthouse Masaryk 100 · 110 m² · terraza 25 m²",
                    "fecha_visita", dia(4), "estado", "pendiente"));

            ins("reservas_online", u("propiedad_id", id("propiedades", 7), "nombre", "Familia Soler", "email", "soler.familia@example.com",
                    "telefono", "+52 55 9930 0213", "dni", "GOMA840505HDFRRN04", "importe", 6000, "moneda", "EUR", "estado", "pagada",
                    "fecha_caducidad", momento(10, 12, 0), "fecha_pago", momento(-4, 12, 0), "token_acceso", token()));
            ins("reservas_online", u("propiedad_id", id("propiedades", 1), "nombre", "Ana García López", "email", "ana.garcia@example.com",
                    "telefono", "+52 55 7890 1234", "importe", 2200, "moneda", "EUR", "estado", "pendiente",
                    "fecha_caducidad", momento(13, 12, 0), "token_acceso", token()));

            Map<String, Object> camp = ins("captacion_campanas", u("nombre", "Volanteo Del Valle · vende con nosotros", "zona", "Del Valle",
                    "tipo_objetivo", "propietarios", "angulo", "Hemos vendido 3 departamentos en tu calle este año",
                    "mensaje_carta", "Estimado vecino: este año hemos vendido tres viviendas en su calle en menos de 45 días. Si está pensando en vender, le hacemos una valoración gratuita y sin compromiso.",
                    "mensaje_whatsapp", "Hola, soy de la inmobiliaria del barrio. ¿Te gustaría saber cuánto vale tu departamento hoy? Valoración gratis en 24 h.",
                    "mensaje_email", "Valoración gratuita de tu vivienda en Del Valle: precio real de venta en 24 horas.", "estado", "activa"));
            if (camp != null) {
                Object[][] ds = {{"Propietario 3ºB", "Calle Pilares 41", "+52 55 9930 0214", null, "contactado"},
                        {"Familia Rivas", "Calle Zurbano 47", null, "rivas@example.com", "interesado"},
                        {"Propietario 1ºA", "Calle Amores 12", "+52 55 9930 0215", null, "pendiente"},
                        {"Herederos Ortiz", "Calle Amores 18", "+52 55 9930 0216", null, "pendiente"}};
                for (Object[] d : ds) {
                    ins("captacion_destinatarios", u("campana_id", camp.get("id"), "nombre", d[0], "direccion", d[1], "telefono", d[2],
                            "email", d[3], "estado", d[4], "contactado_at", "pendiente".equals(d[4]) ? null : momento(-2, 11, 0)));
                }
            }
        }

        // ── Marketing: WhatsApp, SMS, newsletter, micrositio, chatbot, referidos, NPS ──
        void marketing() {
            String chat = "34678901234";
            Object[][] wa = {{"entrante", "Hola, ¿sigue disponible el departamento de Roma Norte?", -1, 18},
                    {"saliente", "¡Hola Ana! Sí, sigue disponible. ¿Te viene bien verlo pasado mañana a las 17:30?", -1, 18},
                    {"entrante", "Perfecto, allí estaré 👍", -1, 19},
                    {"saliente", "Genial, te mando la ubicación. ¡Hasta el jueves!", -1, 19}};
            int min = 0;
            for (Object[] w : wa) {
                ins("whatsapp_mensajes", u("chat_id", chat, "direccion", w[0], "tipo", "texto", "mensaje", w[1], "estado", "leido",
                        "lead_id", id("leads", 0), "leido", true, "fecha", momento((Integer) w[2], (Integer) w[3], min += 4)));
            }
            ins("whatsapp_plantillas", u("nombre", "Confirmación de visita", "nombre_meta", "confirmacion_visita", "categoria", "UTILITY",
                    "idioma", "es", "cuerpo", "Hola {{1}}, te confirmamos la visita a {{2}} el {{3}}. ¿Nos confirmas tu asistencia?",
                    "variables", List.of("nombre", "inmueble", "fecha"), "estado_aprobacion", "APPROVED"));
            ins("whatsapp_plantillas", u("nombre", "Nueva bajada de precio", "nombre_meta", "bajada_precio", "categoria", "MARKETING",
                    "idioma", "es", "cuerpo", "¡Buenas noticias {{1}}! El inmueble {{2}} que te interesaba baja a {{3}}.",
                    "variables", List.of("nombre", "inmueble", "precio"), "estado_aprobacion", "APPROVED"));

            ins("sms_mensajes", u("destinatario", "+52 55 5566 6777", "mensaje", "Recordatorio: mañana a las 11:00 visita al Penthouse Masaryk 100. Responde OK para confirmar.",
                    "estado", "entregado", "coste", 0.0650, "lead_id", id("leads", 6), "enviado_at", momento(-1, 10, 0)));
            ins("sms_mensajes", u("destinatario", "+52 55 1122 2333", "mensaje", "Hola Roberto, tenemos un dúplex en Narvarte que encaja con lo que buscas. ¿Te llamo?",
                    "estado", "entregado", "coste", 0.0650, "lead_id", id("leads", 1), "enviado_at", momento(-3, 12, 0)));

            ins("newsletter_envios", u("asunto", "Mercado de la CDMX en octubre + 4 viviendas nuevas",
                    "contenido_html", "<h1>Novedades de octubre</h1><p>El precio medio en la CDMX sube un 1,2 % este trimestre. Estas son nuestras nuevas exclusivas…</p>",
                    "enviados", 342, "abiertos", 198, "fecha_envio", momento(-12, 9, 0)));

            ins("microsites", u("slug", "inmobiliaria-ejemplo-" + token().substring(0, 6), "nombre_publico", "Inmobiliaria Centro CDMX",
                    "tagline", "Tu casa en el centro, sin sorpresas", "descripcion", "Agencia boutique especializada en el centro de la CDMX desde 2012.",
                    "hero_imagen", FOTO + "1560448204-e02f11c3d0e2" + F, "hero_titulo", "Vendemos tu vivienda en menos de 60 días",
                    "hero_subtitulo", "Valoración gratuita, reportaje profesional y difusión en todos los portales.",
                    "hero_cta_texto", "Valora tu casa gratis", "sobre_nosotros_titulo", "Quiénes somos",
                    "sobre_nosotros_texto", "Somos un equipo de 6 agentes con más de 400 operaciones cerradas en la CDMX.",
                    "servicios", List.of(Map.of("titulo", "Venta", "texto", "Comercializamos tu vivienda de principio a fin."),
                            Map.of("titulo", "Alquiler", "texto", "Inquilinos solventes y seguro de impago."),
                            Map.of("titulo", "Inversión", "texto", "Oportunidades off-market con rentabilidad estudiada.")),
                    "direccion", "Calle Orizaba 45, Ciudad de México", "telefono", "+52 55 0000 0000", "email", "hola@inmobiliaria-ejemplo.example.com",
                    "horario", "L-V 9:30-14:00 y 16:30-20:00", "color_primario", "#1e40af", "publicado", true,
                    "visitas_count", 2310, "leads_capturados", 47));

            Object[][] conv = {{"Elena Vargas", "elena.vargas@example.com", "comprar", 300000, "Del Valle", "piso", 6, 82,
                    new String[][]{{"user", "Hola, busco departamento de 2 o 3 habitaciones en Del Valle"},
                            {"assistant", "¡Hola! Tengo un par de opciones en Del Valle y alrededores. ¿Cuál es tu presupuesto aproximado?"},
                            {"user", "Hasta " + m(300000)},
                            {"assistant", "Perfecto. Te enseño el Depto. Roma Norte 12 (" + m(220000) + ") y el Estudio Coyoacán. ¿Quieres que te reserve una visita?"}}},
                    {"Visitante web", null, "vender", null, "Condesa", "piso", 3, 45,
                            new String[][]{{"user", "¿Cuánto vale mi departamento en Condesa?"},
                                    {"assistant", "Puedo hacerte una valoración orientativa ahora mismo. ¿Cuántos metros y habitaciones tiene?"}}}};
            for (Object[] c : conv) {
                Map<String, Object> cv = ins("chatbot_conversaciones", u("session_id", token(), "visitor_token", token(), "visitor_nombre", c[0],
                        "visitor_email", c[1], "pagina_origen", "/propiedad", "pais", "ES", "estado", c[1] != null ? "lead_capturado" : "finalizada",
                        "intencion_detectada", c[2], "presupuesto_detectado", c[3], "zona_detectada", c[4], "tipo_propiedad_detectada", c[5],
                        "mensajes_total", c[6], "duracion_minutos", 4, "score_engagement", c[7], "iniciada_at", momento(-1, 22, 10),
                        "ultima_actividad_at", momento(-1, 22, 14), "finalizada_at", momento(-1, 22, 15)));
                if (cv != null) {
                    for (String[] m : (String[][]) c[8]) {
                        ins("chatbot_mensajes", f("conversacion_id", cv.get("id"), "rol", m[0], "contenido", m[1], "created_at", momento(-1, 22, 11)));
                    }
                }
            }

            ins("referidos", u("referente_nombre", "Pablo Moreno Blanco", "referente_email", "pablo.moreno@example.com", "codigo", "PABLO-" + token().substring(0, 4).toUpperCase(),
                    "referido_nombre", "Javier Moreno", "referido_email", "javier.moreno@example.com", "referido_telefono", "+52 55 9930 0217",
                    "estado", "en_proceso", "fecha_referencia", momento(-10, 10, 0), "notas", "Hermano de Pablo; busca departamento de 3 habitaciones."));
            ins("referidos", u("referente_nombre", "Andrés Vidal", "referente_email", "andres.vidal@example.com", "codigo", "ANDRES-" + token().substring(0, 4).toUpperCase(),
                    "referido_nombre", "Laura Peña", "referido_email", "laura.pena@example.com", "estado", "cerrado",
                    "fecha_referencia", momento(-60, 10, 0), "fecha_cierre", momento(-15, 10, 0), "valor_operacion", 310000,
                    "premio_concedido", true, "premio_detalle", "Cheque regalo de " + m(300)));

            Object[][] nps = {{"Pablo Moreno Blanco", "pablo.moreno@example.com", 10, "Rapidez y trato inmejorables.", "promotor"},
                    {"Juan Pérez García", "juan.perez@example.com", 9, "Muy atentos con la avería de la calentador.", "promotor"},
                    {"Sofía Núñez Cano", "sofia.nunez@example.com", 6, "Tardaron en enviarme opciones.", "detractor"},
                    {"Marta Fernández Ruiz", "marta.fernandez@example.com", null, null, null}};
            for (Object[] n : nps) {
                ins("nps_respuestas", u("cliente_nombre", n[0], "cliente_email", n[1], "puntuacion", n[2], "comentario", n[3], "categoria", n[4],
                        "token", token(), "solicitada_at", momento(-7, 10, 0), "respondida_at", n[2] == null ? null : momento(-6, 18, 0)));
            }
        }

        // ── Finanzas: facturas, recibos, comisiones, banco, caja ─────────
        void finanzas() {
            ins("invoices", u("serie", "EJ", "numero", 1, "full_number", "EJ-2026-0001", "fecha", dia(-25), "vencimiento", dia(5),
                    "emisor_nombre", "Inmobiliaria Centro CDMX", "emisor_nif", "EJE000000AB0", "cliente_nombre", "Pablo Moreno Blanco",
                    "cliente_nif", "GOMA850606HDFRRN05", "cliente_email", "pablo.moreno@example.com", "concepto", "Honorarios de intermediación · Casa Santa Fe",
                    "base_imponible", 9750, "iva_pct", 21, "iva_amount", 2047.5, "irpf_pct", 0, "irpf_amount", 0, "total", 11797.5,
                    "lineas", List.of(Map.of("concepto", "Honorarios de intermediación (1,5 %)", "cantidad", 1, "precio", 9750)),
                    "status", "Pagada", "fecha_pago", dia(-20), "metodo_pago", "Transferencia", "property_name", nombreProp(4)));
            ins("invoices", u("serie", "EJ", "numero", 2, "full_number", "EJ-2026-0002", "fecha", dia(-5), "vencimiento", dia(25),
                    "emisor_nombre", "Inmobiliaria Centro CDMX", "emisor_nif", "EJE000000AB0", "cliente_nombre", "Inversiones Insurgentes S.A. de C.V.",
                    "cliente_nif", "EJE876543AB1", "cliente_email", "info@invatocha.example.com", "concepto", "Gestión de alquiler · octubre",
                    "base_imponible", 148, "iva_pct", 21, "iva_amount", 31.08, "irpf_pct", 0, "irpf_amount", 0, "total", 179.08,
                    "lineas", List.of(Map.of("concepto", "Gestión integral de alquiler (8 %)", "cantidad", 1, "precio", 148)),
                    "status", "Emitida", "property_name", nombreProp(2)));
            LocalDate mesPasado = LocalDate.now().minusMonths(1);
            ins("receipts", u("serie", "EJR", "numero", 1, "full_number", "EJR-2026-0001", "period_year", mesPasado.getYear(),
                    "period_month", mesPasado.getMonthValue(), "fecha_emision", dia(-25), "fecha_pago", dia(-24),
                    "emisor_nombre", "Carmen Ruiz Molina", "emisor_nif", "GOMA810202HDFRRN01", "inquilino_nombre", "Juan Pérez García",
                    "inquilino_nif", "GOMA830404HDFRRN03", "property_name", nombreProp(0), "concepto", "Renta mensual de vivienda",
                    "importe", 1100, "irpf_pct", 0, "irpf_amount", 0, "total_pagado", 1100, "metodo_pago", "Transferencia", "status", "Pagado"));
            ins("commissions", f("org_id", org, "agent_user_id", org, "agent_name", "Comercial", "operation_type", "venta",
                    "property_id", id("propiedades", 4), "property_name", nombreProp(4), "client_name", "Pablo Moreno Blanco",
                    "operation_value", 650000, "commission_pct", 1.5, "commission_amount", 9750, "operation_date", dia(-25),
                    "status", "pagada", "paid_date", dia(-20), "created_at", ahora()));
            ins("commissions", f("org_id", org, "agent_user_id", org, "agent_name", "Comercial", "operation_type", "venta",
                    "property_id", id("propiedades", 7), "property_name", nombreProp(7), "client_name", "Familia Soler",
                    "operation_value", 505000, "commission_pct", 2, "commission_amount", 10100, "operation_date", dia(-3),
                    "status", "pendiente", "created_at", ahora()));

            Map<String, Object> imp = ins("bank_imports", u("nombre_archivo", "movimientos_octubre.csv", "formato", "csv", "num_lineas", 4,
                    "num_conciliadas", 2, "num_pendientes", 2, "fecha_desde", dia(-30), "fecha_hasta", dia(-1), "saldo_inicial", 24350.20,
                    "saldo_final", 37421.70, "estado", "procesado"));
            if (imp != null) {
                Object[][] mov = {{-26, 11797.5, "TRANSF. PABLO MORENO BLANCO HONORARIOS", "Pablo Moreno Blanco", "conciliado"},
                        {-16, 1100.0, "TRANSFERENCIA RENTA REFORMA 222", "Juan Pérez García", "conciliado"},
                        {-10, -95.0, "CUOTA MANTENIMIENTO REFORMA 222", "Administración del condominio", "pendiente"},
                        {-4, 1850.0, "TRANSF. COSMETICOS PIO RENTA", "Cosméticos PIO S.A. de C.V.", "pendiente"}};
                for (Object[] m : mov) {
                    ins("bank_transactions", u("import_id", imp.get("id"), "fecha", dia((Integer) m[0]), "valor_fecha", dia((Integer) m[0]),
                            "importe", m[1], "concepto", m[2], "contraparte_nombre", m[3], "estado", m[4]));
                }
            }

            Map<String, Object> caja = ins("cuentas_caja", u("nombre", "Caja oficina", "tipo", "efectivo", "saldo_actual", 312.45,
                    "saldo_inicial", 500, "moneda", "EUR", "activa", true));
            if (caja != null) {
                Object[][] mc = {{"gasto", -14, "Material de oficina", "oficina", 42.30, "Papelería Lope", 21.0},
                        {"gasto", -9, "Llaves copiadas · Penthouse Masaryk", "inmuebles", 18.00, "Cerrajería Centro", 21.0},
                        {"gasto", -6, "Taxi a notaría", "desplazamientos", 23.25, "Taxi", 10.0},
                        {"gasto", -2, "Café reunión con inversores", "representacion", 104.00, "Café Comercial", 10.0}};
                for (Object[] m : mc) {
                    double imp2 = (Double) m[4], iva = (Double) m[6];
                    ins("movimientos_caja", u("cuenta_id", caja.get("id"), "tipo", m[0], "fecha", dia((Integer) m[1]), "concepto", m[2],
                            "categoria", m[3], "importe", imp2, "proveedor", m[5], "iva_pct", iva,
                            "iva_importe", Math.round(imp2 * iva / (100 + iva) * 100) / 100.0, "deducible_irpf", true, "creado_por", org));
                }
            }
        }

        // ── Documentos: firmas, plantillas, emails, wiki, bandeja ──────────────
        void documentos() {
            String contenido = "<h2>Contrato de promesa de compraventa</h2><p>En Ciudad de México, a " + dia(-2) + ".</p>"
                    + "<p><strong>Vendedora:</strong> Marta Gil Vidal. <strong>Comprador:</strong> Pablo Moreno Blanco.</p>"
                    + "<p>Objeto: casa en Av. Vasco de Quiroga 15, Santa Fe, Ciudad de México. Precio: " + m(650000) + ". Anticipo: " + m(30000) + ".</p>"
                    + "<p>Plazo para escriturar: 60 días desde la firma.</p>";
            Map<String, Object> doc = ins("firmas_documento", u("titulo", "Promesa de compraventa · Casa Santa Fe", "tipo", "arras",
                    "contenido_html", contenido, "documento_hash", SignatureService.sha256(contenido), "propiedad_id", id("propiedades", 4),
                    "estado", "firmado", "fecha_envio", momento(-3, 10, 0), "fecha_completado", momento(-2, 19, 0),
                    "fecha_expiracion", momento(27, 10, 0), "requiere_dni", true, "requiere_otp_email", true));
            if (doc != null) {
                Object[][] fs = {{"Marta Gil Vidal", "GOMA860707HDFRRN06", "marta.gil@example.com", "vendedor", 1}, {"Pablo Moreno Blanco", "GOMA850606HDFRRN05", "pablo.moreno@example.com", "comprador", 2}};
                for (Object[] x : fs) {
                    ins("firmas_firmante", f("documento_id", doc.get("id"), "nombre", x[0], "dni", x[1], "email", x[2], "rol", x[3], "orden", x[4],
                            "token_firma", token(), "estado", "firmado", "visto_at", momento(-2, 9 + (Integer) x[4], 0),
                            "firmado_at", momento(-2, 10 + (Integer) x[4] * 4, 0), "otp_verificado", true, "created_at", ahora()));
                }
                // Cadena de hashes como la de SignatureService.audit (hash_actual es obligatorio)
                String h1 = SignatureService.sha256("GENESIS|" + doc.get("id") + "|enviado");
                String h2 = SignatureService.sha256(h1 + "|" + doc.get("id") + "|completado");
                ins("firmas_auditoria", f("documento_id", doc.get("id"), "evento", "enviado", "detalle", "Enviado a 2 firmantes",
                        "hash_anterior", "GENESIS", "hash_actual", h1, "created_at", momento(-3, 10, 0)));
                ins("firmas_auditoria", f("documento_id", doc.get("id"), "evento", "completado", "detalle", "Firmado por todas las partes",
                        "hash_anterior", h1, "hash_actual", h2, "created_at", momento(-2, 19, 0)));
            }

            Map<String, Object> pl = ins("plantillas_documento", u("nombre", "Hoja de encargo de venta (ejemplo)", "descripcion", "Encargo de venta con honorarios y duración.",
                    "categoria", "captacion", "icono", "📝", "contenido_html",
                    "<h2>Hoja de encargo de venta</h2><p>Propietario: {propietario.nombre}</p><p>Inmueble: {propiedad.address}</p><p>Precio: {%importe:250000}</p><p>Fecha: {hoy}</p>",
                    "variables_detectadas", List.of("propietario.nombre", "propiedad.address", "hoy"), "es_publica", false, "veces_usada", 3, "favorita", true));
            if (pl != null) {
                ins("documentos_generados", u("plantilla_id", pl.get("id"), "titulo", "Hoja de encargo · Casa Santa Fe",
                        "contenido_html", "<h2>Hoja de encargo de venta</h2><p>Propietario: Marta Gil Vidal</p><p>Inmueble: Av. Vasco de Quiroga 15</p><p>Precio: " + m(650000) + "</p>",
                        "propiedad_id", id("propiedades", 4)));
            }
            ins("plantillas_email", u("nombre", "Seguimiento tras visita", "categoria", "seguimiento", "asunto", "¿Qué te pareció {propiedad}?",
                    "contenido", "Hola {nombre}, gracias por venir a ver {propiedad}. ¿Te surgió alguna duda? Si quieres, te preparo una segunda visita.",
                    "variables_usadas", List.of("nombre", "propiedad"), "es_publica", false, "veces_usada", 12));
            ins("plantillas_email", u("nombre", "Bajada de precio", "categoria", "marketing", "asunto", "{propiedad} baja de precio",
                    "contenido", "Hola {nombre}, el inmueble que te interesaba ahora está por {precio}. ¿Lo vemos esta semana?",
                    "variables_usadas", List.of("nombre", "propiedad", "precio"), "es_publica", false, "veces_usada", 5));

            Map<String, Object> cat = ins("wiki_categorias", u("nombre", "Procesos de la agencia", "descripcion", "Cómo trabajamos", "icono", "📘", "color", "#1e40af", "orden", 1));
            if (cat != null) {
                ins("wiki_articulos", u("categoria_id", cat.get("id"), "autor_id", org, "slug", "checklist-captacion-" + token().substring(0, 5),
                        "titulo", "Checklist de captación en exclusiva", "resumen", "Pasos para captar un inmueble en exclusiva.",
                        "contenido_markdown", "# Checklist de captación\n\n- Valoración con comparables\n- **Nota de encargo** firmada\n- Reportaje de fotos profesional\n- Publicación en portales en 24 h",
                        "publicado", true, "visible_publico", false, "tags", List.of("captacion", "exclusiva"), "veces_leido", 37, "fijado", true));
                ins("wiki_articulos", u("categoria_id", cat.get("id"), "autor_id", org, "slug", "guion-primera-llamada-" + token().substring(0, 5),
                        "titulo", "Guion para la primera llamada a un lead", "resumen", "Qué preguntar en los primeros 5 minutos.",
                        "contenido_markdown", "# Primera llamada\n\n1. Presupuesto y financiación\n2. Zonas y plazos\n3. Qué no negocia\n4. Proponer visita con fecha concreta",
                        "publicado", true, "visible_publico", false, "tags", List.of("leads", "ventas"), "veces_leido", 21));
            }

            Map<String, Object> cuenta = ins("imap_cuentas", u("email", "bandeja-ejemplo@example.com", "imap_host", "imap.example.com", "imap_port", 993,
                    "activa", false, "ultimo_sync_at", momento(0, 9, 0), "ultimo_error", ""));
            if (cuenta != null) {
                Object[][] em = {{"contactos@idealista.com", "idealista", "Nuevo mensaje sobre tu anuncio INM-0002",
                        "Nombre: Laura Peña\nEmail: laura.pena@example.com\nTeléfono: 699 300 218\n\nMensaje: Hola, ¿se puede visitar el sábado por la mañana?", 0, null},
                        {"ana.garcia@example.com", "Ana García López", "Re: visita Depto. Roma Norte",
                                "Perfecto, nos vemos el jueves a las 17:30. ¿Puedo ir con mi pareja?", -1, 0},
                        {"info@invatocha.example.com", "Inversiones Insurgentes S.A. de C.V.", "Liquidación de octubre",
                                "Buenos días, ¿nos podéis enviar el extracto de rentas de octubre? Gracias.", -2, null}};
                for (Object[] e : em) {
                    ins("emails_inbox", u("cuenta_id", cuenta.get("id"), "message_id", "<ejemplo-" + token() + "@example.com>", "de_email", e[0],
                            "de_nombre", e[1], "para", List.of("bandeja-ejemplo@example.com"), "asunto", e[2], "cuerpo_texto", e[3],
                            "leido", (Integer) e[4] < 0, "estrella", false, "archivado", false, "lead_id", e[5] == null ? null : id("leads", (Integer) e[5]),
                            "etiqueta", "inbox", "fecha", momento((Integer) e[4], 9, 30)));
                }
            }
        }

        // ── Equipo: personal, chat, flujos, paneles ────────────────────────────
        void equipo() {
            // Empleados de ejemplo con acceso a la app (para Equipo, Rendimiento, asignaciones…).
            // No pueden iniciar sesión (contraseña inservible) y no se cobran (PlazasService).
            String sufijo = org.substring(0, Math.min(8, org.length()));
            Object[][] emp = {{"Laura Martín", "laura.martin", "comercial"}, {"Javier Ortega", "javier.ortega", "comercial"},
                    {"Nuria Campos", "nuria.campos", "contable"}};
            List<String> agentes = new ArrayList<>(List.of(org));
            for (Object[] e : emp) {
                Map<String, Object> usr = ins("users", f("email", e[1] + "+" + sufijo + "@example.com", "password", "!ejemplo-" + token(),
                        "name", e[0], "role", e[2], "organization_id", org, "invited_by", org, "active", true, "ai_credits", 0,
                        "created_at", ahora()));
                if (usr != null && "comercial".equals(e[2])) agentes.add(String.valueOf(usr.get("id")));
            }
            // Reparto de leads y visitas entre el equipo: así el rendimiento por agente tiene datos
            List<Map<String, Object>> leads = guardadas.getOrDefault("leads", List.of());
            for (int i = 0; i < leads.size(); i++)
                supabase.update("leads", String.valueOf(leads.get(i).get("id")), Map.of("asignado_a", agentes.get(i % agentes.size())), org);
            List<Map<String, Object>> visitas = guardadas.getOrDefault("visitas", List.of());
            for (int i = 0; i < visitas.size(); i++)
                supabase.update("visitas", String.valueOf(visitas.get(i).get("id")), Map.of("agente_id", agentes.get((i + 1) % agentes.size())), org);

            // Plantillas de email (pantalla Plantillas de email)
            Object[][] pl = {{"Bienvenida a nuevo lead", "Gracias por contactar, {{nombre}}",
                    "Hola {{nombre}}:\n\nGracias por tu interés en {{inmueble}}. Te llamo hoy para resolver tus dudas y, si te encaja, organizar una visita.\n\nUn saludo.",
                    List.of("nombre", "inmueble")},
                    {"Confirmación de visita", "Tu visita a {{inmueble}} el {{fecha}}",
                    "Hola {{nombre}}:\n\nTe confirmo la visita a {{inmueble}} el {{fecha}} a las {{hora}}. Si no puedes venir, responde a este correo y la cambiamos.",
                    List.of("nombre", "inmueble", "fecha", "hora")},
                    {"Recordatorio de renta", "Recibo de alquiler de {{mes}}",
                    "Hola {{inquilino}}:\n\nTe recordamos que el recibo de {{mes}} ({{renta}} " + mon() + ") se cargará en tu cuenta el día 5. Cualquier duda, aquí estamos.",
                    List.of("inquilino", "mes", "renta")}};
            for (Object[] p : pl) ins("templates", u("type", "email", "name", p[0], "subject", p[1], "body", p[2], "variables", p[3]));

            Object[][] pers = {{"Laura Martín", "Agente comercial", "laura.martin@example.com", "+52 55 9930 0219", 24000, "2023-02-01", "Activo", "Especialista en Del Valle y Polanco."},
                    {"Javier Ortega", "Captador", "javier.ortega@example.com", "+52 55 9930 0220", 22000, "2024-05-15", "Activo", "Volanteo y captación en exclusiva."},
                    {"Nuria Campos", "Administración", "nuria.campos@example.com", "+52 55 9930 0221", 21000, "2022-09-01", "Activo", "Facturación, cobranza y contratos."}};
            for (Object[] p : pers) {
                ins("personal", u("name", p[0], "role", p[1], "email", p[2], "phone", p[3], "salary", p[4], "start_date", p[5], "status", p[6], "notes", p[7]));
            }

            List<Map<String, Object>> canales = supabase.selectFilter("chat_canales", "org_id=eq." + org + "&tipo=eq.publico&limit=1");
            Map<String, Object> canal = canales.isEmpty() ? ins("chat_canales", f("org_id", org, "creador_id", org, "nombre", "operaciones",
                    "descripcion", "Operaciones en curso", "icono", "🏠", "tipo", "publico", "archivado", false, "fijado", true,
                    "mensajes_count", 0, "created_at", ahora())) : canales.get(0);
            if (canal != null) {
                String[] msgs = {"¡Promesa firmada de la casa de Santa Fe! 🎉", "Bien!! ¿Para cuándo la notaría?", "En 60 días. Os paso el calendario.",
                        "Mañana enseño el penthouse de Masaryk a David Torres a las 11:00."};
                int i = 0;
                for (String m : msgs) {
                    ins("chat_mensajes", f("canal_id", canal.get("id"), "org_id", org, "autor_id", org, "contenido", m,
                            "contenido_html", com.inmobilia.util.HtmlSeguro.escapar(m), "tipo", "texto", "eliminado", false,
                            "created_at", momento(-1, 10, i++ * 7)));
                }
            }

            ins("workflows", u("nombre", "Bienvenida a leads de portales (ejemplo)", "descripcion", "Al entrar un lead de un portal: tarea de llamada en 1 hora y email de bienvenida.",
                    "activo", false, "trigger_evento", "lead_creado", "trigger_condiciones", Map.of("origen", List.of("idealista", "fotocasa")),
                    "acciones", List.of(Map.of("tipo", "crear_tarea", "titulo", "Llamar al nuevo lead", "en_minutos", 60),
                            Map.of("tipo", "enviar_email", "plantilla", "Bienvenida")),
                    "ejecutar_inmediato", true, "delay_minutos", 0, "solo_horario_laboral", true, "veces_ejecutado", 38, "veces_exito", 37, "veces_error", 1));

            ins("dashboards_custom", u("nombre", "Dirección comercial (ejemplo)", "descripcion", "KPIs para la reunión de los lunes", "es_default", false,
                    "visible_para", "todos", "icono", "📈", "columnas", 12,
                    "widgets", List.of(widget("kpi", "kpi_leads", "📋 Leads", "#8b5cf6"), widget("kpi", "kpi_visitas_mes", "📅 Visitas mes", "#ec4899"),
                            widget("kpi", "kpi_facturado_mes", "💰 Facturado mes", "#10b981"), widget("kpi", "kpi_leads_hot", "🔥 Leads HOT", "#ef4444"))));
        }

        // ── Portal del inquilino: un inquilino con acceso y sus solicitudes ──────
        void portalInquilinos() {
            String inquilino = id("inquilinos", 0);
            if (inquilino == null) return;
            String sufijo = org.substring(0, Math.min(8, org.length()));
            Map<String, Object> inq = guardadas.get("inquilinos").get(0);
            Map<String, Object> usr = ins("users", f("email", "inquilino+" + sufijo + "@example.com", "password", "!ejemplo-" + token(),
                    "name", inq.getOrDefault("name", "Inquilino de ejemplo"), "role", "tenant_portal", "organization_id", org,
                    "tenant_id", inquilino, "active", true, "ai_credits", 0, "created_at", ahora()));
            if (usr == null) return;
            Object prop = inq.get("property_name");
            ins("tenant_requests", f("tenant_user_id", usr.get("id"), "inquilino_id", inquilino, "org_id", org, "property_name", prop,
                    "category", "mantenimiento", "title", "La persiana del salón no sube",
                    "description", "Desde el fin de semana la persiana del salón se queda a medio subir y hace ruido.",
                    "priority", "Alta", "status", "Nueva", "created_at", momento(-1, 18, 20)));
            ins("tenant_requests", f("tenant_user_id", usr.get("id"), "inquilino_id", inquilino, "org_id", org, "property_name", prop,
                    "category", "consulta", "title", "¿Puedo domiciliar el alquiler en otra cuenta?",
                    "description", "He cambiado de banco y quería pasar el recibo a la nueva cuenta a partir del mes que viene.",
                    "priority", "Baja", "status", "Resuelta", "response", "Sí, envíanos la nueva cuenta (CLABE) y lo cambiamos para el próximo recibo.",
                    "responded_at", momento(-5, 10, 0), "created_at", momento(-6, 9, 30)));
        }

        // ── Rentabilidad: análisis guardados, calculados con el mismo motor que la herramienta ──
        void rentabilidad() {
            Object[][] an = {{"Depto. Roma Norte · compra para alquilar", 1, 215000, 21500, 8000, 3000, 172000, 3.1, 25, 1150, 420, 70, 5.0},
                    {"Estudio Coyoacán · al contado", -1, 165000, 16500, 12000, 4000, 0, 0.0, 0, 850, 280, 45, 4.0}};
            for (Object[] a : an) {
                Map<String, Object> datos = new LinkedHashMap<>();
                datos.put("precioCompra", a[2]); datos.put("gastosCompra", a[3]); datos.put("reformas", a[4]); datos.put("mobiliario", a[5]);
                datos.put("hipoteca", (Integer) a[6] > 0); datos.put("capitalHipoteca", a[6]); datos.put("tipoInteres", a[7]); datos.put("plazoAnos", a[8]);
                datos.put("rentaMensual", a[9]); datos.put("incrementoRentaAnual", 2.0); datos.put("ibi", a[10]); datos.put("comunidad", a[11]);
                datos.put("seguroHogar", 220); datos.put("seguroImpago", 300); datos.put("mantenimiento", 500); datos.put("gestion", 0);
                datos.put("otrosGastos", 100); datos.put("tasaVacancia", a[12]); datos.put("revalorizacionAnual", 3.0); datos.put("horizonteAnos", 15);
                Map<String, Object> r = new com.inmobilia.controller.RentabilityController(supabase).calculate(datos);
                int prop = (Integer) a[1];
                ins("analisis_rentabilidad", u("nombre", a[0], "propiedad_id", prop < 0 ? null : id("propiedades", prop),
                        "precio_compra", a[2], "gastos_compra", a[3], "reformas", a[4], "mobiliario", a[5],
                        "hipoteca", (Integer) a[6] > 0, "capital_hipoteca", (Integer) a[6] > 0 ? a[6] : null, "tipo_interes", a[7], "plazo_anos", a[8],
                        "cuota_mensual", r.get("cuotaMensualHipoteca"), "renta_mensual", a[9], "incremento_renta_anual", 2.0,
                        "ibi", a[10], "comunidad", a[11], "seguro_hogar", 220, "seguro_impago", 300, "mantenimiento", 500, "gestion", 0,
                        "otros_gastos", 100, "tasa_vacancia", a[12], "revalorizacion_anual", 3.0, "horizonte_anos", 15,
                        "rentabilidad_bruta", com.inmobilia.controller.RentabilityController.acotar(r.get("rentabilidadBruta")),
                        "rentabilidad_neta", com.inmobilia.controller.RentabilityController.acotar(r.get("rentabilidadNeta")),
                        "cash_on_cash", com.inmobilia.controller.RentabilityController.acotar(r.get("cashOnCash")),
                        "tir_estimada", com.inmobilia.controller.RentabilityController.acotar(r.get("tir"))));
            }
        }

        Map<String, Object> widget(String tipo, String campo, String titulo, String color) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", UUID.randomUUID().toString().substring(0, 8));
            w.put("tipo", tipo);
            w.put("campo", campo);
            w.put("titulo", titulo);
            w.put("color", color);
            w.put("w", 3);
            w.put("h", 2);
            return w;
        }

        // ── IA y automatización: agente, cadencias, scoring, notas de voz, informes, coach, simulador ──
        void inteligencia() {
            ins("agente_acciones", u("tipo", "mensaje_lead", "prioridad", "alta", "titulo", "Reactivar lead caliente: Roberto Díaz Nieto (email)",
                    "motivo", "score 68 · sin contacto desde hace 3 días", "lead_id", id("leads", 1), "estado", "propuesta",
                    "borrador", "Hola Roberto, ¿seguimos con tu búsqueda en Polanco? Tengo un penthouse nuevo que encaja con lo que me contaste. ¿Te va bien verlo el jueves?",
                    "payload_json", "{\"canal\":\"email\",\"asunto\":\"Seguimos con tu búsqueda\"}"));
            ins("agente_acciones", u("tipo", "bajar_precio", "prioridad", "media", "titulo", "Bajar 3 % el Dúplex Narvarte",
                    "motivo", "45 días publicado, 2 visitas y 0 ofertas · el precio está un 6 % por encima de los comparables",
                    "propiedad_id", id("propiedades", 8), "estado", "propuesta",
                    "payload_json", "{\"precio_actual\":610000,\"precio_nuevo\":592000}"));
            ins("agente_acciones", u("tipo", "crear_tarea", "prioridad", "alta", "titulo", "Nuevo match 94 %: Penthouse Masaryk para David Torres",
                    "motivo", "Encaja en zona, presupuesto y terraza", "lead_id", id("leads", 6), "propiedad_id", id("propiedades", 3),
                    "estado", "ejecutada", "resultado", "Tarea creada: llamar a David Torres", "resuelta_at", momento(-1, 9, 0),
                    "payload_json", "{}"));

            ins("cadencia_inscripciones", u("lead_id", id("leads", 2), "lead_nombre", "Marta Fernández Ruiz", "tipo", "nuevo_lead", "paso", 2,
                    "estado", "activa", "proximo_at", momento(1, 10, 0), "ultimo_at", momento(-2, 10, 0), "pasos_dados", 2));
            ins("cadencia_inscripciones", u("lead_id", id("leads", 5), "lead_nombre", "Lucía Ramos Ortega", "tipo", "post_visita", "paso", 1,
                    "estado", "activa", "proximo_at", momento(2, 11, 0), "ultimo_at", momento(-3, 11, 0), "pasos_dados", 1));

            Object[][] inter = {{0, "visita", "presencial", "Visitó el Depto. Roma Norte 12", 15, 1}, {0, "email_abierto", "email", "Abrió la newsletter de octubre", 5, -1},
                    {6, "llamada", "telefono", "Llamada de 12 min: muy interesado en el penthouse", 10, 3}, {1, "formulario", "web", "Contactó desde Inmuebles24", 8, -1}};
            for (Object[] it : inter) {
                int p = (Integer) it[5];
                ins("lead_interacciones", u("lead_id", id("leads", (Integer) it[0]), "tipo", it[1], "canal", it[2], "detalle", it[3],
                        "valor_score", it[4], "propiedad_id", p < 0 ? null : id("propiedades", p), "created_at", momento(-2, 12, 0)));
            }
            ins("lead_score_historico", f("lead_id", id("leads", 6), "score", 64, "temperatura", "templado", "motivo_cambio", "Primer contacto", "created_at", momento(-10, 9, 0)));
            ins("lead_score_historico", f("lead_id", id("leads", 6), "score", 79, "temperatura", "caliente", "motivo_cambio", "Llamada y visita programada", "created_at", momento(-1, 9, 0)));

            ins("notas_voz", u("autor_id", org, "duracion_segundos", 74, "transcripcion_idioma", "es", "lead_id", id("leads", 6), "propiedad_id", id("propiedades", 3),
                    "transcripcion", "Acabo de hablar con David Torres. Le encanta el penthouse pero pregunta si el precio es negociable y si la terraza tiene toldo. Quiere volver con su mujer el jueves.",
                    "resumen_ia", "David quiere segunda visita el jueves con su pareja. Dudas: precio negociable y toldo en la terraza. Siguiente paso: confirmar visita y consultar al propietario margen de negociación.",
                    "fecha_grabacion", momento(-1, 13, 40)));
            ins("notas_voz", u("autor_id", org, "duracion_segundos", 41, "transcripcion_idioma", "es", "propiedad_id", id("propiedades", 0),
                    "transcripcion", "Visita técnica a Reforma 222: el calentador tiene diez años, conviene presupuestar el cambio antes de renovar el contrato.",
                    "resumen_ia", "Calentador de 10 años en Reforma 222: pedir presupuesto de sustitución antes de la renovación.",
                    "fecha_grabacion", momento(-4, 17, 5)));

            ins("informes_mercado", u("propiedad_id", id("propiedades", 7), "token", token(), "titulo", "Informe de precio · Depto. Condesa, Ámsterdam 5",
                    "tipo", "piso", "municipio", "Ciudad de México", "provincia", "Ciudad de México", "metros", 95, "habitaciones", 3, "estado", "buen_estado",
                    "precio_recomendado", 515000, "precio_min", 490000, "precio_max", 540000, "eur_m2", 5421, "confianza", "alta",
                    "comparables_json", "[{\"direccion\":\"Calle de Ibiza 12\",\"metros\":92,\"precio\":505000,\"vendida\":true},"
                            + "{\"direccion\":\"Calle Doctor Castelo 8\",\"metros\":101,\"precio\":548000,\"vendida\":true},"
                            + "{\"direccion\":\"Calle Menorca 3\",\"metros\":88,\"precio\":479000,\"vendida\":false}]",
                    "n_comparables", 3, "eur_m2_zona", 5380, "dias_medios_venta", 52, "propietario_nombre", "Julia Sáez Bravo", "vistas", 6,
                    "narrativa", "Con 95 m² junto al Parque México, el precio de salida recomendado es " + m(515000) + ". Los tres comparables más cercanos se mueven entre " + m(5200) + " y " + m(5700) + " por m²; un precio por encima de " + m(540000) + " alargaría la venta más allá de los 52 días medios de la zona."));

            ins("coaching_sesiones", u("agente_id", org, "escenario", "objecion_precio", "escenario_titulo", "El comprador dice que el departamento es caro",
                    "rol_cliente", "comprador", "dificultad", "media", "n_turnos", 4, "puntuacion", 78,
                    "transcripcion_json", "[{\"rol\":\"cliente\",\"texto\":\"Me gusta, pero " + m(720000) + " me parece mucho.\"},"
                            + "{\"rol\":\"agente\",\"texto\":\"Lo entiendo. ¿Con qué otros penthouses lo estás comparando?\"},"
                            + "{\"rol\":\"cliente\",\"texto\":\"Con uno en Lomas a " + m(680000) + ".\"},"
                            + "{\"rol\":\"agente\",\"texto\":\"Ese no tiene terraza ni garaje; con ellos este sale a menos " + mon() + "/m². ¿Lo vemos en números?\"}]",
                    "fortalezas", "Preguntas abiertas y comparación con datos concretos.",
                    "mejoras", "Cerrar con una propuesta de siguiente paso (segunda visita o reunión con el banco).",
                    "respuesta_modelo", "Entiendo que es una inversión importante. Si comparamos " + mon() + "/m² con terraza y garaje incluidos, este penthouse está un 4 % por debajo de la zona. ¿Te preparo el comparativo y lo vemos el jueves?"));

            ins("simulaciones_hipoteca", u("precio_objetivo", 320000, "ahorro", 70000, "ingresos_mensuales", 4200, "plazo_anos", 30, "tipo_interes", 2.9,
                    "cuota_estimada", 1040, "capital_prestamo", 282000, "capacidad_max", 385000, "esfuerzo_pct", 24.8, "viable", true,
                    "comprador_nombre", "Elena Vargas", "comprador_email", "elena.vargas@example.com", "zona_interes", "Del Valle"));
            ins("simulaciones_hipoteca", u("precio_objetivo", 450000, "ahorro", 30000, "ingresos_mensuales", 3100, "plazo_anos", 30, "tipo_interes", 3.1,
                    "cuota_estimada", 1790, "capital_prestamo", 465000, "capacidad_max", 290000, "esfuerzo_pct", 57.7, "viable", false,
                    "comprador_nombre", "Tomás Jerez", "comprador_email", "tomas.jerez@example.com", "zona_interes", "Condesa"));
        }
    }
}
