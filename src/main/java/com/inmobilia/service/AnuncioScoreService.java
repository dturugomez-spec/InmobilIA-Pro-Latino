package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 📣 Optimizador de anuncios ("Anuncio Score").
 *
 * Puntúa la calidad/completitud del anuncio de cada inmueble (0-100), detecta
 * lo que falta y si el precio está fuera de mercado (comparado con el $/m²
 * mediano de la propia cartera). Objetivo: vender más rápido. Sin llamadas extra
 * por inmueble (calcula la mediana una sola vez).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnuncioScoreService {

    private final AIService aiService;

    private record Factor(String campo, String label, int peso) {}
    private static final List<Factor> FACTORES = List.of(
            new Factor("featured_photo", "Foto principal", 20),
            new Factor("notes",          "Descripción completa (120+ caracteres)", 20),
            new Factor("current_value",  "Precio de venta", 15),
            new Factor("area_m2",        "Superficie (m²)", 10),
            new Factor("bedrooms",       "Habitaciones", 8),
            new Factor("bathrooms",      "Baños", 7),
            new Factor("type",           "Tipo de inmueble", 5),
            new Factor("city",           "Población", 5),
            new Factor("address",        "Dirección", 5),
            new Factor("reference",      "Referencia", 5)
    );

    /** Mediana de $/m² de la cartera (para posicionar precios). */
    public double medianaEurM2(List<Map<String, Object>> props) {
        List<Double> ratios = new ArrayList<>();
        for (Map<String, Object> p : props) {
            double precio = precio(p), m = nd(p.get("area_m2"));
            if (precio > 0 && m > 20) ratios.add(precio / m);
        }
        if (ratios.isEmpty()) return 0;
        Collections.sort(ratios);
        return ratios.get(ratios.size() / 2);
    }

    /** Evalúa un inmueble: score, checklist de factores y posicionamiento de precio. */
    public Map<String, Object> evaluar(Map<String, Object> p, double medianaZona) {
        int score = 0;
        List<Map<String, Object>> checklist = new ArrayList<>();
        for (Factor f : FACTORES) {
            boolean ok = cumple(p, f.campo());
            if (ok) score += f.peso();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("label", f.label());
            c.put("ok", ok);
            c.put("peso", f.peso());
            checklist.add(c);
        }

        // Posicionamiento de precio vs mediana de la cartera
        String precioMsg = null; String precioNivel = "ok";
        double precio = precio(p), m = nd(p.get("area_m2"));
        if (precio > 0 && m > 20 && medianaZona > 0) {
            double eurM2 = precio / m;
            double desvio = (eurM2 - medianaZona) / medianaZona * 100.0;
            if (desvio > 12) { precioNivel = "alto"; precioMsg = String.format("Precio ~%.0f%% por encima de tu cartera. Puede frenar visitas.", desvio); }
            else if (desvio < -12) { precioNivel = "bajo"; precioMsg = String.format("Precio ~%.0f%% por debajo de tu cartera. Revisa que no estés infravalorando.", Math.abs(desvio)); }
            else { precioNivel = "ok"; precioMsg = "Precio alineado con tu cartera de la zona."; }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("score", score);
        r.put("checklist", checklist);
        r.put("precio_msg", precioMsg);
        r.put("precio_nivel", precioNivel);
        r.put("nivel", score >= 80 ? "alto" : (score >= 50 ? "medio" : "bajo"));
        return r;
    }

    /** Consejo breve generado por IA para mejorar un anuncio concreto. */
    public String consejoIA(Map<String, Object> p, Map<String, Object> eval, String uid) {
        try {
            StringBuilder falta = new StringBuilder();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checklist = (List<Map<String, Object>>) eval.get("checklist");
            if (checklist != null) for (Map<String, Object> c : checklist)
                if (Boolean.FALSE.equals(c.get("ok"))) falta.append("- ").append(c.get("label")).append("\n");

            String prompt = "Anuncio inmobiliario: " + str(p.getOrDefault("name", p.get("address"))) +
                    " en " + str(p.get("city")) + ". Score de calidad: " + eval.get("score") + "/100.\n" +
                    (falta.length() > 0 ? "Le falta:\n" + falta : "Está bastante completo.\n") +
                    (eval.get("precio_msg") != null ? "Precio: " + eval.get("precio_msg") + "\n" : "") +
                    "\nDa 3 consejos concretos y accionables (máx 70 palabras en total) para que este anuncio venda más rápido. Español de Latinoamérica (" + com.inmobilia.util.PaisActual.get().nombre() + "), con ustedes y sin vosotros.";
            return aiService.call(null, prompt, "Eres un experto en marketing inmobiliario.", 200, uid);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean cumple(Map<String, Object> p, String campo) {
        Object v = p.get(campo);
        if (v == null) return false;
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equals(s)) return false;
        return switch (campo) {
            case "notes" -> s.length() >= 120;
            case "current_value", "area_m2", "bedrooms", "bathrooms" -> nd(v) > 0;
            default -> true;
        };
    }

    private static double precio(Map<String, Object> p) {
        double v = nd(p.get("current_value"));
        return v > 0 ? v : nd(p.get("purchase_price"));
    }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
