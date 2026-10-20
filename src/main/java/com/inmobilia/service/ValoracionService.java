package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 🏷️ AVM · Modelo de valoración automática de inmuebles
 *
 * Estima el precio de un inmueble combinando:
 *   1. Precio de referencia por m² de la ciudad (tabla latinoamericana en USD, convertida a la moneda de la agencia)
 *   2. Ajustes por tipo, estado, planta, extras (ascensor, garaje, terraza)
 *   3. Comparables reales de la cartera de la agencia (si hay)
 *   4. Refinamiento opcional con IA
 *
 * Es el motor del imán de captación: el propietario mete 4 datos y obtiene
 * una horquilla de precio al instante, y la agencia capta el lead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ValoracionService {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final PaisService paises;
    private final CurrencyService divisas;

    public Map<String, Object> valorar(String userId, Map<String, String> in) {
        double metros = pd(in.get("metros"));
        if (metros <= 0) metros = 90;  // fallback razonable

        // País de la agencia (también en la calculadora pública, que no tiene sesión)
        com.inmobilia.util.Pais pais = paises.usar(userId);
        String ciudad = in.getOrDefault("municipio", in.getOrDefault("provincia", ""));
        String provincia = norm(in.getOrDefault("provincia", in.getOrDefault("municipio", "")));
        double base = divisas.convertir(com.inmobilia.util.PreciosReferencia.usdPorM2(pais, ciudad), "USD", pais.moneda());

        // 1) Comparables reales de la cartera de la agencia (afina la base)
        Double baseComparables = comparables(userId, in, metros);
        if (baseComparables != null) base = (base + baseComparables * 2) / 3;  // pondera comparables

        // 2) Ajustes
        double factor = 1.0;
        String tipo = norm(in.get("tipo"));
        switch (tipo) {
            case "atico", "ático" -> factor *= 1.15;
            case "chalet", "casa" -> factor *= 1.05;
            case "duplex", "dúplex" -> factor *= 1.08;
            case "estudio" -> factor *= 0.92;
            case "local" -> factor *= 0.75;
            default -> {}
        }
        String estado = norm(in.get("estado"));
        switch (estado) {
            case "nuevo", "obra_nueva" -> factor *= 1.18;
            case "reformado" -> factor *= 1.10;
            case "buen_estado" -> factor *= 1.0;
            case "a_reformar", "para_reformar" -> factor *= 0.80;
            default -> {}
        }
        if (b(in.get("ascensor"))) factor *= 1.04;
        if (b(in.get("garaje"))) factor *= 1.08;
        if (b(in.get("terraza"))) factor *= 1.05;
        int planta = (int) pd(in.get("planta"));
        if (planta >= 4 && !b(in.get("ascensor"))) factor *= 0.93;  // alto sin ascensor
        int ano = (int) pd(in.get("ano_construccion"));
        if (ano > 0 && ano < 1980 && !"reformado".equals(estado) && !"nuevo".equals(estado)) factor *= 0.92;

        double eurM2 = base * factor;
        double estimado = eurM2 * metros;
        double min = estimado * 0.90;
        double max = estimado * 1.10;

        String confianza = baseComparables != null ? "alta" : (com.inmobilia.util.PreciosReferencia.conocida(pais, ciudad) ? "media" : "baja");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("precio_estimado", redondear(estimado));
        r.put("precio_min", redondear(min));
        r.put("precio_max", redondear(max));
        r.put("eur_m2", Math.round(eurM2));
        r.put("metros", metros);
        r.put("confianza", confianza);
        r.put("metodo", baseComparables != null ? "comparables" : "avm_tabla");
        r.put("provincia", provincia);
        return r;
    }

    /** Media de $/m² de propiedades similares en la cartera de la agencia. */
    private Double comparables(String userId, Map<String, String> in, double metros) {
        try {
            String muni = in.getOrDefault("municipio", "");
            // Esquema real de propiedades: city, area_m2, current_value/purchase_price
            List<Map<String, Object>> props = supabase.selectFilter("propiedades", "user_id=eq." + userId + "&limit=300");
            List<Double> ratios = new ArrayList<>();
            for (Map<String, Object> p : props) {
                if (!muni.isBlank() && p.get("city") != null
                        && !norm(p.get("city")).contains(norm(muni)) && !norm(muni).contains(norm(p.get("city")))) continue;
                double pr = pd(p.get("current_value")); if (pr <= 0) pr = pd(p.get("purchase_price"));
                double m = pd(p.get("area_m2"));
                if (pr > 0 && m > 20) ratios.add(pr / m);
            }
            if (ratios.size() >= 3) {
                Collections.sort(ratios);
                return ratios.get(ratios.size() / 2);  // mediana (robusta a outliers)
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Texto comercial generado por IA para acompañar la valoración (opcional). */
    public String comentarioIA(Map<String, Object> val, Map<String, String> in, String userId) {
        try {
            String prompt = String.format(
                ("Inmueble: %s de %s m² en %s, estado %s. Valoración estimada: %s " + PaisActual.moneda() + " (rango %s-%s " + PaisActual.moneda() + ").\n") +
                "Escribe 2-3 frases comerciales (máx 60 palabras) para el propietario, explicando el valor y " +
                "animándole a una valoración profesional gratuita. Tono cercano y profesional, español.",
                in.getOrDefault("tipo", "inmueble"), in.getOrDefault("metros", "?"),
                in.getOrDefault("municipio", "tu zona"), in.getOrDefault("estado", "?"),
                val.get("precio_estimado"), val.get("precio_min"), val.get("precio_max"));
            return aiService.call(null, prompt, "Eres un asesor inmobiliario experto y cercano.", 180, userId);
        } catch (Exception e) {
            return null;
        }
    }

    private static double pd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
    private static boolean b(Object o) { return o != null && (("true".equalsIgnoreCase(String.valueOf(o))) || "on".equalsIgnoreCase(String.valueOf(o)) || "si".equalsIgnoreCase(String.valueOf(o))); }
    private static String norm(Object o) {
        if (o == null) return "";
        return String.valueOf(o).toLowerCase().trim()
                .replaceAll("[áà]", "a").replaceAll("[éè]", "e").replaceAll("[íì]", "i")
                .replaceAll("[óò]", "o").replaceAll("[úù]", "u");
    }
    private static long redondear(double v) {
        // redondea a los 1000$ más cercanos para que parezca tasación real
        return Math.round(v / 1000.0) * 1000;
    }
}
