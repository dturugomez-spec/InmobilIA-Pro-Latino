package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 📊 Informe de Mercado / CMA (Comparative Market Analysis)
 *
 * Construye el documento que gana la exclusiva: precio recomendado (reutiliza el
 * motor de valoración), comparables reales de la cartera de la agencia, métricas
 * de zona y un análisis honesto generado por IA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InformeMercadoService {

    private final SupabaseService supabase;
    private final ValoracionService valoracion;
    private final AIService aiService;
    private final ObjectMapper objectMapper;

    /** Construye los datos del informe a partir de un inmueble de la cartera. */
    public Map<String, Object> construir(String uid, Map<String, Object> prop) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Esquema real de propiedades (entidad Propiedad): city, type, area_m2
        String municipio = str(prop.get("city"));
        String tipo = str(prop.get("type"));
        double metros = nd(prop.get("area_m2"));

        // 1) Precio recomendado (reutiliza el AVM)
        Map<String, String> in = new HashMap<>();
        in.put("metros", String.valueOf(metros));
        in.put("municipio", municipio);
        in.put("provincia", municipio);
        in.put("tipo", tipo);
        in.put("estado", estadoConservacion(prop));
        Map<String, Object> val = valoracion.valorar(uid, in);

        // 2) Comparables reales de la cartera
        List<Map<String, Object>> comps = comparables(uid, prop, municipio, tipo);

        // 3) Métricas de zona
        OptionalDouble avgEurM2 = comps.stream().mapToDouble(c -> nd(c.get("eur_m2"))).filter(x -> x > 0).average();
        OptionalDouble avgDias = comps.stream().mapToDouble(c -> nd(c.get("dias"))).filter(x -> x > 0).average();

        out.put("subject", subjectSnapshot(prop, metros));
        out.put("precio_recomendado", val.get("precio_estimado"));
        out.put("precio_min", val.get("precio_min"));
        out.put("precio_max", val.get("precio_max"));
        out.put("eur_m2", val.get("eur_m2"));
        out.put("confianza", val.get("confianza"));
        out.put("comparables", comps);
        out.put("n_comparables", comps.size());
        out.put("eur_m2_zona", avgEurM2.isPresent() ? Math.round(avgEurM2.getAsDouble()) : val.get("eur_m2"));
        out.put("dias_medios_venta", avgDias.isPresent() ? (int) Math.round(avgDias.getAsDouble()) : null);

        // 4) Narrativa IA
        out.put("narrativa", narrativa(uid, out, municipio, tipo));
        return out;
    }

    private Map<String, Object> subjectSnapshot(Map<String, Object> p, double metros) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("titulo", p.getOrDefault("name", p.getOrDefault("address", "Inmueble")));
        s.put("tipo", p.get("type"));
        s.put("municipio", p.get("city"));
        s.put("provincia", p.get("city"));
        s.put("metros", metros);
        s.put("habitaciones", p.get("bedrooms"));
        s.put("estado", p.get("status"));
        return s;
    }

    /** Comparables: propiedades de la cartera del mismo municipio y tipo. */
    private List<Map<String, Object>> comparables(String uid, Map<String, Object> subject, String municipio, String tipo) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String subjectId = str(subject.get("id"));
            List<Map<String, Object>> props = supabase.selectFilter(
                    "propiedades", "user_id=eq." + uid + "&limit=300");
            for (Map<String, Object> p : props) {
                if (subjectId.equals(str(p.get("id")))) continue;
                double precio = precio(p), m = nd(p.get("area_m2"));
                if (precio <= 0 || m < 20) continue;
                // mismo municipio (fuzzy) y mismo tipo si está disponible
                if (!municipio.isBlank() && !fuzzy(str(p.get("city")), municipio)) continue;
                if (!tipo.isBlank() && p.get("type") != null && !tipo.equalsIgnoreCase(str(p.get("type")))) continue;

                Map<String, Object> c = new LinkedHashMap<>();
                c.put("direccion", p.getOrDefault("name", p.getOrDefault("address", "—")));
                c.put("municipio", p.get("city"));
                c.put("metros", m);
                c.put("precio", precio);
                c.put("eur_m2", Math.round(precio / m));
                c.put("estado", p.get("status"));
                c.put("vendida", "Reservado".equalsIgnoreCase(str(p.get("status"))));
                c.put("dias", diasMercado(p.get("created_at")));
                result.add(c);
                if (result.size() >= 8) break;
            }
        } catch (Exception e) {
            log.warn("comparables CMA: {}", e.getMessage());
        }
        return result;
    }

    /** Precio de referencia del inmueble: valor actual, o de compra si falta. */
    private static double precio(Map<String, Object> p) {
        double v = nd(p.get("current_value"));
        return v > 0 ? v : nd(p.get("purchase_price"));
    }

    /** Estado de conservación derivado del status de ocupación. */
    private static String estadoConservacion(Map<String, Object> p) {
        String st = str(p.get("status")).toLowerCase();
        if (st.contains("reforma")) return "a_reformar";
        return "buen_estado";
    }

    private String narrativa(String uid, Map<String, Object> data, String municipio, String tipo) {
        try {
            String prompt = String.format(Locale.ROOT,
                "Informe de mercado para un %s en %s.\n" +
                ("Precio recomendado: %s" + PaisActual.moneda() + " (rango %s-%s" + PaisActual.moneda() + ", ~%s" + PaisActual.moneda() + "/m²).\n") +
                ("Comparables analizados en la zona: %s. " + PaisActual.moneda() + "/m² medio de zona: %s. Días medios en venta: %s.\n\n") +
                "Escribe un análisis de mercado profesional y honesto para el PROPIETARIO (110-150 palabras): " +
                "posicionamiento del precio, qué dicen los comparables y recomendación de estrategia de venta. " +
                "Sin prometer resultados. Español de España.",
                blank(tipo, "inmueble"), blank(municipio, "la zona"),
                val(data.get("precio_recomendado")), val(data.get("precio_min")), val(data.get("precio_max")),
                val(data.get("eur_m2")), val(data.get("n_comparables")), val(data.get("eur_m2_zona")),
                data.get("dias_medios_venta") == null ? "n/d" : val(data.get("dias_medios_venta")));
            return aiService.call(null, prompt, "Eres un analista inmobiliario experto y honesto.", 320, uid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Genera el informe completo de un inmueble y lo persiste con su token público.
     * Devuelve el registro guardado (incluye "token" y "precio_recomendado").
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generarYGuardar(String uid, Map<String, Object> prop, String propietarioNombre) {
        Map<String, Object> data = construir(uid, prop);
        Map<String, Object> subject = (Map<String, Object>) data.get("subject");
        List<Map<String, Object>> comps = (List<Map<String, Object>>) data.get("comparables");

        String token = "cma" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("user_id", uid);
        rec.put("propiedad_id", prop.get("id"));
        rec.put("token", token);
        rec.put("titulo", subject.get("titulo"));
        rec.put("tipo", subject.get("tipo"));
        rec.put("municipio", subject.get("municipio"));
        rec.put("provincia", subject.get("provincia"));
        rec.put("metros", subject.get("metros"));
        rec.put("habitaciones", subject.get("habitaciones"));
        rec.put("estado", subject.get("estado"));
        rec.put("precio_recomendado", data.get("precio_recomendado"));
        rec.put("precio_min", data.get("precio_min"));
        rec.put("precio_max", data.get("precio_max"));
        rec.put("eur_m2", data.get("eur_m2"));
        rec.put("confianza", data.get("confianza"));
        rec.put("comparables_json", comparablesJson(comps));
        rec.put("n_comparables", data.get("n_comparables"));
        rec.put("eur_m2_zona", data.get("eur_m2_zona"));
        rec.put("dias_medios_venta", data.get("dias_medios_venta"));
        rec.put("narrativa", data.get("narrativa"));
        rec.put("propietario_nombre", propietarioNombre);
        rec.put("created_at", java.time.OffsetDateTime.now().toString());
        supabase.insert("informes_mercado", rec);
        return rec;
    }

    public String comparablesJson(List<Map<String, Object>> comps) {
        try { return objectMapper.writeValueAsString(comps); } catch (Exception e) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parseComparables(Object json) {
        try {
            if (json == null) return List.of();
            return objectMapper.readValue(str(json), List.class);
        } catch (Exception e) { return List.of(); }
    }

    // ── helpers ──
    private static int diasMercado(Object createdAt) {
        try {
            String c = String.valueOf(createdAt);
            if (c == null || c.length() < 10) return 0;
            OffsetDateTime alta = OffsetDateTime.parse(c.length() == 10 ? c + "T00:00:00Z" : c);
            return (int) Math.max(0, Duration.between(alta, OffsetDateTime.now()).toDays());
        } catch (Exception e) { return 0; }
    }
    private static boolean fuzzy(String a, String b) {
        a = norm(a); b = norm(b);
        return !a.isBlank() && !b.isBlank() && (a.contains(b) || b.contains(a));
    }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
    private static boolean truthy(Object o) { return o != null && ("true".equalsIgnoreCase(String.valueOf(o)) || "t".equalsIgnoreCase(String.valueOf(o)) || "si".equalsIgnoreCase(String.valueOf(o))); }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String val(Object o) { return o == null ? "0" : String.valueOf(o); }
    private static String blank(String s, String def) { return s == null || s.isBlank() ? def : s; }
    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().trim()
                .replaceAll("[áà]", "a").replaceAll("[éè]", "e").replaceAll("[íì]", "i")
                .replaceAll("[óò]", "o").replaceAll("[úù]", "u");
    }
}
