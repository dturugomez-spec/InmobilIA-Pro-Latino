package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Motor de matching propiedades ↔ leads/inquilinos.
 *
 * Score ponderado 0–100:
 *  - Precio dentro de presupuesto: 25 pts (mejor cuanto más cerca del rango)
 *  - Tipo propiedad: 15 pts
 *  - Zona/municipio: 15 pts
 *  - Habitaciones: 10 pts
 *  - Baños: 5 pts
 *  - Superficie: 10 pts
 *  - Características booleanas (garaje, ascensor...): 15 pts (3 cada una)
 *  - Eficiencia energética / año: 5 pts
 *
 * Solo se genera match si score >= 50 (umbral configurable).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingEngine {

    private static final double UMBRAL = 50.0;

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final NotificationService notif;

    /** Recalcula matches para un usuario completo (admin/director). */
    public int runMatchingForUser(String userId) {
        int newMatches = 0;
        List<Map<String, Object>> prefs = supabase.selectFilter(
                "preferencias_busqueda", "user_id=eq." + userId + "&auto_match=eq.true");
        List<Map<String, Object>> props = supabase.selectFilter(
                "propiedades", "user_id=eq." + userId + "&limit=500");
        props.removeIf(p -> "Reservado".equalsIgnoreCase(String.valueOf(p.get("status"))));

        for (Map<String, Object> pref : prefs) {
            for (Map<String, Object> prop : props) {
                MatchResult r = score(pref, prop);
                if (r.score >= UMBRAL) {
                    if (saveMatchIfNew(pref, prop, r, userId)) {
                        newMatches++;
                    }
                }
            }
        }
        log.info("Matching {} → {} nuevos matches", userId, newMatches);
        return newMatches;
    }

    /** Score detallado de una preferencia vs propiedad. */
    public MatchResult score(Map<String, Object> pref, Map<String, Object> prop) {
        MatchResult r = new MatchResult();
        r.score = 0;

        // 1) Operación (filtro duro)
        String operPref = str(pref.get("operacion"));
        String operProp = str(prop.get("operacion"));
        if (operPref != null && operProp != null && !operPref.equalsIgnoreCase(operProp)) {
            r.descartes.add("Operación distinta");
            return r;
        }

        // 2) Precio (25 pts)
        double precio = nd(prop.get("current_value"));
        if (precio <= 0) precio = nd(prop.get("purchase_price"));
        double pMin = nd(pref.get("presupuesto_min"));
        double pMax = nd(pref.get("presupuesto_max"));
        if (precio > 0 && pMax > 0) {
            if (precio <= pMax && precio >= pMin * 0.85) {  // 15% margen abajo
                r.score += 25;
                r.razones.add(String.format("Precio en rango (%.0f€)", precio));
            } else if (precio <= pMax * 1.10) {
                r.score += 12;
                r.razones.add("Precio cercano (10% sobre presupuesto)");
            } else {
                r.descartes.add(String.format("Precio fuera de rango (%.0f€)", precio));
            }
        }

        // 3) Tipo propiedad (15 pts)
        String tipoProp = str(prop.get("type"));
        Object tiposPref = pref.get("tipo_propiedad");
        if (tiposPref instanceof List) {
            List<?> tipos = (List<?>) tiposPref;
            if (tipos.isEmpty() || tipos.stream().anyMatch(t -> Objects.equals(str(t), tipoProp))) {
                r.score += 15;
                r.razones.add("Tipo coincide: " + tipoProp);
            } else {
                r.descartes.add("Tipo no coincide");
            }
        } else {
            r.score += 8;  // si no hay preferencia, asumimos parcial
        }

        // 4) Zona/municipio (15 pts) — la propiedad solo tiene city
        String zonaProp = str(prop.get("city"));
        String muniProp = str(prop.get("city"));
        boolean zonaOk = false;
        if (pref.get("zonas") instanceof List) {
            List<?> zonas = (List<?>) pref.get("zonas");
            if (!zonas.isEmpty() && zonas.stream().anyMatch(z -> Objects.equals(str(z), zonaProp))) {
                r.score += 15;
                r.razones.add("Zona deseada: " + zonaProp);
                zonaOk = true;
            }
        }
        if (!zonaOk && pref.get("municipios") instanceof List) {
            List<?> munis = (List<?>) pref.get("municipios");
            if (!munis.isEmpty() && munis.stream().anyMatch(m -> Objects.equals(str(m), muniProp))) {
                r.score += 10;
                r.razones.add("Municipio coincide: " + muniProp);
                zonaOk = true;
            }
        }

        // 5) Habitaciones (10 pts)
        int habProp = ni(prop.get("bedrooms"));
        int habMin = ni(pref.get("habitaciones_min"));
        int habMax = ni(pref.get("habitaciones_max"));
        if (habProp > 0) {
            if ((habMin == 0 || habProp >= habMin) && (habMax == 0 || habProp <= habMax)) {
                r.score += 10;
                r.razones.add(habProp + " habitaciones");
            } else {
                r.descartes.add("Habitaciones fuera de rango");
            }
        }

        // 6) Baños (5 pts)
        int banosProp = ni(prop.get("bathrooms"));
        int banosMin = ni(pref.get("banos_min"));
        if (banosMin > 0 && banosProp >= banosMin) {
            r.score += 5;
        }

        // 7) Superficie (10 pts)
        double supProp = nd(prop.get("area_m2"));
        double supMin = nd(pref.get("superficie_min"));
        double supMax = nd(pref.get("superficie_max"));
        if (supProp > 0) {
            boolean ok = (supMin <= 0 || supProp >= supMin) && (supMax <= 0 || supProp <= supMax);
            if (ok) {
                r.score += 10;
                r.razones.add(String.format("%.0f m²", supProp));
            }
        }

        // 8) Extras (3 cada uno → 15 pts max) — se detectan en la descripción (notes)
        String notas = str(prop.get("notes"));
        String notasLc = notas == null ? "" : notas.toLowerCase();
        if (bool(pref.get("requiere_garaje")) && notasLc.contains("garaje")) { r.score += 3; r.razones.add("Garaje ✓"); }
        if (bool(pref.get("requiere_ascensor")) && notasLc.contains("ascensor")) { r.score += 3; r.razones.add("Ascensor ✓"); }
        if (bool(pref.get("requiere_terraza")) && notasLc.contains("terraza")) { r.score += 3; r.razones.add("Terraza ✓"); }
        if (bool(pref.get("requiere_piscina")) && notasLc.contains("piscina")) { r.score += 3; r.razones.add("Piscina ✓"); }
        if (bool(pref.get("requiere_amueblado")) && notasLc.contains("amueblad")) { r.score += 3; r.razones.add("Amueblado ✓"); }
        if (bool(pref.get("permite_mascotas")) && notasLc.contains("mascota")) { r.score += 2; r.razones.add("Mascotas OK"); }

        // 9) Eficiencia energética (5 pts)
        String effMin = str(pref.get("eficiencia_min"));
        String effProp = str(prop.get("eficiencia_energetica"));
        if (effMin != null && effProp != null && effProp.compareTo(effMin) <= 0) {
            r.score += 5;
            r.razones.add("Eficiencia " + effProp);
        }

        r.score = Math.min(100, r.score);
        return r;
    }

    private boolean saveMatchIfNew(Map<String, Object> pref, Map<String, Object> prop, MatchResult r, String userId) {
        String prefId = str(pref.get("id"));
        String propId = str(prop.get("id"));
        // Comprobar si ya existe
        List<Map<String, Object>> exist = supabase.selectFilter(
                "matches", "preferencia_id=eq." + prefId + "&propiedad_id=eq." + propId);
        if (!exist.isEmpty()) return false;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", userId);
        data.put("preferencia_id", prefId);
        data.put("propiedad_id", propId);
        data.put("lead_id", pref.get("lead_id"));
        data.put("inquilino_id", pref.get("inquilino_id"));
        data.put("score", r.score);
        data.put("razones", r.razones);
        data.put("descartes", r.descartes);
        data.put("estado", "nuevo");
        data.put("created_at", OffsetDateTime.now().toString());

        supabase.insert("matches", data);

        // Notificar al agente
        try {
            notif.create(userId,
                    "🎯 Nuevo match (" + Math.round(r.score) + "%)",
                    "Propiedad " + str(prop.getOrDefault("name", prop.get("address"))) + " encaja con un lead",
                    "/matches",
                    r.score >= 80 ? "alta" : "media");
        } catch (Exception e) { /* silencioso */ }

        // Auto-email al cliente si habilitado
        if (bool(pref.get("notificar_email")) && r.score >= 70) {
            sendMatchEmail(pref, prop, r);
        }

        return true;
    }

    private void sendMatchEmail(Map<String, Object> pref, Map<String, Object> prop, MatchResult r) {
        try {
            String email = null;
            // Buscar email del lead o inquilino
            if (pref.get("lead_id") != null) {
                List<Map<String, Object>> rows = supabase.selectFilter("leads", "id=eq." + pref.get("lead_id"));
                if (!rows.isEmpty()) email = str(rows.get(0).get("email"));
            } else if (pref.get("inquilino_id") != null) {
                List<Map<String, Object>> rows = supabase.selectFilter("inquilinos", "id=eq." + pref.get("inquilino_id"));
                if (!rows.isEmpty()) email = str(rows.get(0).get("email"));
            }
            if (email == null || email.isBlank()) return;

            String html = String.format(
                "<h2>🏠 Encontramos una propiedad ideal para ti</h2>" +
                "<p><strong>%s</strong></p>" +
                "<p>Precio: <strong>%.0f €</strong></p>" +
                "<p>Habitaciones: %s · Baños: %s · Superficie: %.0f m²</p>" +
                "<p><em>Coincide al %.0f%% con tu búsqueda.</em></p>",
                str(prop.getOrDefault("name", prop.get("address"))),
                nd(prop.get("current_value")) > 0 ? nd(prop.get("current_value")) : nd(prop.get("purchase_price")),
                str(prop.get("bedrooms")),
                str(prop.get("bathrooms")),
                nd(prop.get("area_m2")),
                r.score
            );
            emailService.send(email, "🏠 Nueva propiedad que coincide con tu búsqueda", html);
        } catch (Exception e) {
            log.warn("Error enviando email matching: {}", e.getMessage());
        }
    }

    public static class MatchResult {
        public double score;
        public List<String> razones = new ArrayList<>();
        public List<String> descartes = new ArrayList<>();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static int ni(Object o) { try { return o == null ? 0 : (int) Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static boolean bool(Object o) { if (o == null) return false; return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)); }
}
