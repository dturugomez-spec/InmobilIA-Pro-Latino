package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Cuando se crea/actualiza una propiedad, busca búsquedas guardadas
 * que encajen y notifica al cliente automáticamente.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReverseSearchService {

    private final SupabaseService supabase;
    private final EmailService emailService;

    @Async
    public void notificarMatches(String propiedadId) {
        try {
            List<Map<String, Object>> props = supabase.selectFilter("propiedades", "id=eq." + propiedadId);
            if (props.isEmpty()) return;
            Map<String, Object> p = props.get(0);
            String userId = String.valueOf(p.get("user_id"));

            List<Map<String, Object>> busquedas = supabase.selectFilter("busquedas_guardadas",
                    "user_id=eq." + userId + "&activa=eq.true");

            for (Map<String, Object> b : busquedas) {
                if (encaja(p, b)) {
                    notificar(b, p);
                    int matches = ((Number) b.getOrDefault("matches_count", 0)).intValue() + 1;
                    supabase.update("busquedas_guardadas", String.valueOf(b.get("id")), Map.of(
                            "matches_count", matches,
                            "ultima_notificacion", OffsetDateTime.now().toString()
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Err matches reverso: {}", e.getMessage());
        }
    }

    private boolean encaja(Map<String, Object> p, Map<String, Object> b) {
        // Esquema real de propiedades: type, city, current_value/purchase_price, area_m2, bedrooms, notes
        if (b.get("tipo") != null && !fuzzyEq(b.get("tipo"), p.get("type"))) return false;
        if (b.get("municipio") != null && !fuzzyEq(b.get("municipio"), p.get("city"))) return false;

        double precio = nd(p.get("current_value"));
        if (precio <= 0) precio = nd(p.get("purchase_price"));
        if (b.get("precio_min") != null && precio < nd(b.get("precio_min"))) return false;
        if (b.get("precio_max") != null && precio > nd(b.get("precio_max"))) return false;

        double metros = nd(p.get("area_m2"));
        if (b.get("metros_min") != null && metros < nd(b.get("metros_min"))) return false;

        int hab = (int) nd(p.get("bedrooms"));
        if (b.get("habitaciones_min") != null && hab < (int) nd(b.get("habitaciones_min"))) return false;

        // Extras: se detectan en la descripción (notes)
        String notas = String.valueOf(p.getOrDefault("notes", "")).toLowerCase();
        if (Boolean.TRUE.equals(b.get("requiere_garaje")) && !notas.contains("garaje")) return false;
        if (Boolean.TRUE.equals(b.get("requiere_ascensor")) && !notas.contains("ascensor")) return false;
        if (Boolean.TRUE.equals(b.get("requiere_terraza")) && !notas.contains("terraza")) return false;

        return true;
    }

    private static boolean fuzzyEq(Object a, Object b) {
        String x = String.valueOf(a == null ? "" : a).toLowerCase().trim();
        String y = String.valueOf(b == null ? "" : b).toLowerCase().trim();
        if (x.isEmpty() || "null".equals(x)) return true;
        if (y.isEmpty() || "null".equals(y)) return false;
        return x.contains(y) || y.contains(x);
    }

    private void notificar(Map<String, Object> b, Map<String, Object> p) {
        String email = String.valueOf(b.get("cliente_email"));
        if (email == null || "null".equals(email) || email.isBlank()) return;

        String html = String.format(
            "<h2>🎯 ¡Tenemos algo que te encaja!</h2>" +
            "<p>Hola %s, hemos encontrado una nueva propiedad que coincide con tu búsqueda <strong>%s</strong>:</p>" +
            "<div style='border:1px solid #e2e8f0;border-radius:8px;padding:15px;margin:15px 0'>" +
            "  <h3>%s</h3>" +
            "  <p style='font-size:24px;color:#3b82f6;font-weight:bold'>%s €</p>" +
            "  <p>🛏 %s · 🚿 %s · 📐 %s m²</p>" +
            "</div>" +
            "<p>Si te interesa, contesta este email o llámanos.</p>",
            b.get("cliente_nombre"), b.get("nombre_busqueda"),
            p.getOrDefault("name", p.get("address")),
            Math.round(nd(p.get("current_value")) > 0 ? nd(p.get("current_value")) : nd(p.get("purchase_price"))),
            p.get("bedrooms"), p.get("bathrooms"), p.get("area_m2"));

        try {
            emailService.send(email, "🎯 Nueva propiedad que te encaja", html);
        } catch (Exception e) {}
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
