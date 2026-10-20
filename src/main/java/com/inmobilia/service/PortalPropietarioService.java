package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🪟 Portal de Transparencia del Propietario
 *
 * Agrega TODA la actividad real de un inmueble (visitas, interés, feedback,
 * ofertas, días en mercado) en un resumen honesto que el propietario ve en
 * tiempo real. Es el arma de retención: el dueño deja de preguntar
 * "¿qué estáis haciendo para vender mi casa?" y renueva la exclusiva.
 *
 * Solo usa datos reales del CRM. No inventa visualizaciones de portales.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortalPropietarioService {

    private final SupabaseService supabase;
    private final AIService aiService;

    /** Resumen completo de actividad de un inmueble para mostrar al propietario. */
    public Map<String, Object> resumen(Map<String, Object> p) {
        String propId = String.valueOf(p.get("id"));
        Map<String, Object> r = new LinkedHashMap<>();

        // ── Días en mercado ──
        long dias = 0;
        try {
            String creada = String.valueOf(p.get("created_at"));
            if (creada != null && creada.length() >= 10) {
                OffsetDateTime alta = OffsetDateTime.parse(creada.length() == 10 ? creada + "T00:00:00Z" : creada);
                dias = Duration.between(alta, OffsetDateTime.now()).toDays();
            }
        } catch (Exception ignored) {}
        r.put("dias_mercado", Math.max(0, dias));

        // ── Visitas ──
        List<Map<String, Object>> visitas = supabase.selectFilter(
                "visitas", "propiedad_id=eq." + propId + "&order=fecha_inicio.desc&limit=100");
        long realizadas = visitas.stream().filter(v -> "realizada".equals(v.get("estado"))).count();
        long programadas = visitas.stream().filter(v ->
                "programada".equals(v.get("estado")) || "confirmada".equals(v.get("estado"))).count();
        r.put("visitas_realizadas", realizadas);
        r.put("visitas_programadas", programadas);
        r.put("visitas_total", visitas.size());

        // Interés medio (1-5) de visitas con feedback
        List<Integer> niveles = new ArrayList<>();
        List<Map<String, Object>> feedbacks = new ArrayList<>();
        for (Map<String, Object> v : visitas) {
            Integer nivel = toInt(v.get("interes_nivel"));
            if (nivel != null && nivel > 0) niveles.add(nivel);
            String fb = str(v.get("feedback_visitante"));
            String obj = str(v.get("objeciones"));
            if (!fb.isBlank() || !obj.isBlank()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("fecha", str(v.get("fecha_inicio")));
                f.put("interes", nivel);
                f.put("comentario", fb);
                f.put("objeciones", obj);
                feedbacks.add(f);
            }
        }
        double interesMedio = niveles.isEmpty() ? 0 :
                niveles.stream().mapToInt(Integer::intValue).average().orElse(0);
        r.put("interes_medio", Math.round(interesMedio * 10) / 10.0);
        r.put("interes_n", niveles.size());
        r.put("feedbacks", feedbacks);

        // ── Ofertas ──
        List<Map<String, Object>> ofertas = supabase.selectFilter(
                "ofertas", "propiedad_id=eq." + propId + "&order=importe.desc&limit=50");
        long ofertasActivas = ofertas.stream().filter(o -> {
            String e = str(o.get("estado"));
            return !"rechazada".equals(e) && !"retirada".equals(e) && !"caducada".equals(e);
        }).count();
        Double mejorOferta = ofertas.stream()
                .map(o -> nd(o.get("importe"))).filter(x -> x > 0)
                .max(Double::compare).orElse(null);
        r.put("ofertas_total", ofertas.size());
        r.put("ofertas_activas", ofertasActivas);
        r.put("mejor_oferta", mejorOferta);

        // ── Posicionamiento de precio (mejor oferta vs precio pedido) ──
        double precio = precio(p);
        if (mejorOferta != null && precio > 0) {
            r.put("oferta_vs_precio_pct", Math.round((mejorOferta / precio) * 100));
        }

        return r;
    }

    /** Narrativa cercana generada por IA para el propietario (opcional). */
    public String narrativaIA(Map<String, Object> p, Map<String, Object> resumen, String userId) {
        try {
            String prompt = String.format(Locale.ROOT,
                ("Resumen de actividad del inmueble en %s (precio %s" + PaisActual.moneda() + "):\n") +
                "- Días en el mercado: %s\n- Visitas realizadas: %s\n- Visitas programadas: %s\n" +
                "- Interés medio de visitantes: %s/5 (%s valoraciones)\n- Ofertas recibidas: %s (activas %s)\n\n" +
                "Escribe un mensaje cercano y honesto al PROPIETARIO (máx 70 palabras) resumiendo cómo va la venta " +
                "y qué se está haciendo. Tono tranquilizador y profesional. Sin prometer resultados. Español.",
                str(p.getOrDefault("address", p.get("name"))), val(precio(p)),
                val(resumen.get("dias_mercado")), val(resumen.get("visitas_realizadas")),
                val(resumen.get("visitas_programadas")), val(resumen.get("interes_medio")),
                val(resumen.get("interes_n")), val(resumen.get("ofertas_total")), val(resumen.get("ofertas_activas")));
            return aiService.call(null, prompt, "Eres un agente inmobiliario honesto que informa a su cliente propietario.", 200, userId);
        } catch (Exception e) {
            return null;
        }
    }

    // ── helpers ──
    /** Precio de referencia: valor actual, o de compra si falta. */
    private static double precio(Map<String, Object> p) {
        double v = nd(p.get("current_value"));
        return v > 0 ? v : nd(p.get("purchase_price"));
    }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
    private static Integer toInt(Object o) { try { return o == null ? null : (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; } }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String val(Object o) { return o == null ? "0" : String.valueOf(o); }
}
