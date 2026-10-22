package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 💞 Marketplace "Tinder de pisos" (compradores finales).
 *
 * Solo entran inmuebles de agencias con el add-on activo (tinder_activo = true,
 * que pagan +25% sobre su plan). Cada like con contacto genera un lead para la
 * agencia dueña del inmueble.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketplaceService {

    private final SupabaseService supabase;
    private final NotificationService notif;
    private final EmailService email;
    private final WorkflowEngine workflows;

    /** Inmuebles elegibles para swipe, con filtros opcionales del comprador. */
    /** Pool GLOBAL (red compartida): pisos publicados de TODAS las agencias con el add-on activo. */
    public List<Map<String, Object>> pool(Map<String, String> filtros, Set<String> excluir, int limite) {
        List<Map<String, Object>> agencias = supabase.selectFilter("users", "tinder_activo=eq.true&limit=500");
        if (agencias.isEmpty()) return List.of();
        String ids = agencias.stream().map(a -> String.valueOf(a.get("id"))).collect(Collectors.joining(","));
        Map<String, String> nombreAgencia = new HashMap<>();
        for (Map<String, Object> a : agencias) nombreAgencia.put(String.valueOf(a.get("id")), str(a.get("name")));

        List<Map<String, Object>> props = supabase.selectFilter(
                "propiedades", "user_id=in.(" + ids + ")&publicada=eq.true&limit=300");
        return filtrarYConstruir(props, filtros, excluir, limite, nombreAgencia);
    }

    /** Pool POR AGENCIA (buscador propio en su microsite): SOLO los pisos publicados de esa agencia. */
    public List<Map<String, Object>> poolAgencia(String agencyId, Map<String, String> filtros, Set<String> excluir, int limite) {
        if (agencyId == null || agencyId.isBlank()) return List.of();
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + agencyId);
        Map<String, String> nombreAgencia = new HashMap<>();
        if (!u.isEmpty()) nombreAgencia.put(agencyId, str(u.get(0).get("name")));

        List<Map<String, Object>> props = supabase.selectFilter(
                "propiedades", "user_id=eq." + agencyId + "&publicada=eq.true&limit=300");
        return filtrarYConstruir(props, filtros, excluir, limite, nombreAgencia);
    }

    /** Aplica los filtros del comprador (tolerantes, en memoria) y construye las tarjetas. */
    private List<Map<String, Object>> filtrarYConstruir(List<Map<String, Object>> props, Map<String, String> filtros,
                                                        Set<String> excluir, int limite, Map<String, String> nombreAgencia) {
        double presMax = pd(filtros.get("presupuesto"));
        String zona = norm(filtros.getOrDefault("zona", ""));
        int habMin = (int) pd(filtros.get("habitaciones"));
        String tipo = norm(filtros.getOrDefault("tipo", ""));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : props) {
            String pid = String.valueOf(p.get("id"));
            if (excluir != null && excluir.contains(pid)) continue;
            if ("Reservado".equalsIgnoreCase(str(p.get("status")))) continue;
            double precio = precio(p);
            if (precio <= 0) continue;
            if (presMax > 0 && precio > presMax * 1.05) continue;
            if (!zona.isBlank() && !norm(p.get("city")).contains(zona) && !zona.contains(norm(p.get("city")))) continue;
            if (habMin > 0 && pd(p.get("bedrooms")) < habMin) continue;
            if (!tipo.isBlank() && p.get("type") != null && !norm(p.get("type")).contains(tipo)) continue;

            out.add(card(p, precio, nombreAgencia.getOrDefault(String.valueOf(p.get("user_id")), "")));
            if (out.size() >= limite) break;
        }
        Collections.shuffle(out);
        return out;
    }

    private Map<String, Object> card(Map<String, Object> p, double precio, String agencia) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", p.get("id"));
        c.put("agency_id", p.get("user_id"));
        c.put("agencia", agencia);
        c.put("titulo", p.getOrDefault("name", p.getOrDefault("address", "Inmueble")));
        c.put("ciudad", p.get("city"));
        c.put("tipo", p.get("type"));
        c.put("precio", (long) precio);
        c.put("habitaciones", toInt(p.get("bedrooms")));
        c.put("banos", toInt(p.get("bathrooms")));
        c.put("metros", toInt(p.get("area_m2")));
        c.put("foto", p.get("featured_photo"));
        c.put("descripcion", p.get("notes"));
        return c;
    }

    /** Registra un swipe; si es like/superlike con contacto, crea lead para la agencia. */
    public void registrarSwipe(String propiedadId, String accion, Map<String, String> buyer) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + propiedadId);
        if (rows.isEmpty()) return;
        String agencyId = String.valueOf(rows.get(0).get("user_id"));

        Map<String, Object> sw = new LinkedHashMap<>();
        sw.put("agency_id", agencyId);
        sw.put("propiedad_id", propiedadId);
        sw.put("accion", accion);
        sw.put("buyer_device", buyer.get("device"));
        sw.put("buyer_nombre", buyer.get("nombre"));
        sw.put("buyer_email", buyer.get("email") != null ? buyer.get("email").trim().toLowerCase() : null);
        sw.put("buyer_telefono", buyer.get("telefono"));
        sw.put("buyer_zona", buyer.get("zona"));
        sw.put("created_at", OffsetDateTime.now().toString());

        boolean esInteres = "like".equals(accion) || "superlike".equals(accion);
        boolean hayContacto = buyer.get("email") != null && !buyer.get("email").isBlank();
        if (esInteres && hayContacto) {
            String leadId = crearLead(agencyId, propiedadId, rows.get(0), accion, buyer);
            if (leadId != null) sw.put("lead_id", leadId);
        }
        try { supabase.insert("tinder_swipes", sw); } catch (Exception ignored) {}
    }

    private String crearLead(String agencyId, String propiedadId, Map<String, Object> prop, String accion, Map<String, String> buyer) {
        try {
            Map<String, Object> lead = new LinkedHashMap<>();
            lead.put("user_id", agencyId);
            lead.put("nombre", buyer.getOrDefault("nombre", "Comprador marketplace"));
            lead.put("email", buyer.get("email") != null ? buyer.get("email").trim().toLowerCase() : null);
            lead.put("telefono", buyer.get("telefono"));
            lead.put("interes", "Compra");
            lead.put("origen", "marketplace");
            lead.put("etapa", "Nuevo");
            lead.put("zona", buyer.getOrDefault("zona", str(prop.get("city"))));
            // (antes se guardaba "property_id", columna que leads no tiene: Supabase rechazaba el lead)
            lead.put("score", "superlike".equals(accion) ? 80 : 60);
            lead.put("notas", ("superlike".equals(accion) ? "⭐ SUPER LIKE" : "❤️ Like") +
                    " en el marketplace sobre: " + str(prop.getOrDefault("name", prop.get("address"))));
            lead.put("created_at", OffsetDateTime.now().toString());
            Map<String, Object> saved = supabase.insert("leads", lead);
            if (saved != null) {
                Map<String, Object> evento = new LinkedHashMap<>(saved);
                evento.put("propiedad_id", propiedadId);
                workflows.triggerEvent(agencyId, "lead_creado", evento);
            }

            notif.create(agencyId, ("superlike".equals(accion) ? "⭐" : "❤️") + " Nuevo comprador del marketplace",
                    buyer.getOrDefault("nombre", "Un comprador") + " mostró interés en " +
                            str(prop.getOrDefault("name", prop.get("address"))),
                    "/leads", "superlike".equals(accion) ? "alta" : "normal");
            return saved != null ? String.valueOf(saved.get("id")) : null;
        } catch (Exception e) {
            log.warn("marketplace lead: {}", e.getMessage());
            return null;
        }
    }

    /**
     * La agencia hace "match" con un comprador interesado: notifica al comprador
     * por email que la agencia también quiere enseñarle el inmueble.
     */
    public boolean hacerMatch(String swipeId, String agencyId) {
        List<Map<String, Object>> rows = supabase.selectFilter("tinder_swipes", "id=eq." + swipeId);
        if (rows.isEmpty() || !agencyId.equals(String.valueOf(rows.get(0).get("agency_id")))) return false;
        Map<String, Object> sw = rows.get(0);
        if (Boolean.TRUE.equals(sw.get("matched"))) return true;  // idempotente

        String buyerEmail = str(sw.get("buyer_email"));
        String buyerNombre = str(sw.get("buyer_nombre"));
        if (buyerEmail.isBlank()) return false;

        // Datos de inmueble y agencia para el email
        String titulo = "tu inmueble favorito", agencia = "la agencia";
        List<Map<String, Object>> pr = supabase.selectFilter("propiedades", "id=eq." + sw.get("propiedad_id"));
        if (!pr.isEmpty()) titulo = str(pr.get(0).getOrDefault("name", pr.get(0).get("address")));
        List<Map<String, Object>> ag = supabase.selectFilter("users", "id=eq." + agencyId);
        if (!ag.isEmpty()) agencia = str(ag.get(0).getOrDefault("name", "la agencia"));

        try {
            String html = "<div style=\"font-family:sans-serif;max-width:480px;margin:auto\">" +
                    "<h2>💞 ¡Es un match!</h2>" +
                    "<p>Hola " + (buyerNombre.isBlank() ? "" : buyerNombre) + ",</p>" +
                    "<p><strong>" + agencia + "</strong> también quiere enseñarte <strong>" + titulo + "</strong>. " +
                    "Se pondrán en contacto contigo muy pronto para organizar una visita.</p>" +
                    "<p style=\"color:#64748b;font-size:13px\">Recibes este email porque marcaste este inmueble en el buscador de InmobilIA.</p></div>";
            email.send(buyerEmail, "💞 ¡Es un match! " + agencia + " quiere enseñarte el inmueble", html);
        } catch (Exception e) {
            log.warn("match email: {}", e.getMessage());
        }

        try {
            supabase.updateDe("tinder_swipes", swipeId,
                    Map.of("matched", true, "matched_at", OffsetDateTime.now().toString()), "agency_id", agencyId);
        } catch (Exception ignored) {}

        notif.create(agencyId, "💞 Match confirmado",
                "Has hecho match con " + (buyerNombre.isBlank() ? "un comprador" : buyerNombre) + ". Le hemos avisado por email.",
                "/leads", "normal");
        return true;
    }

    // ── helpers ──
    private static double precio(Map<String, Object> p) {
        double v = pd(p.get("current_value"));
        return v > 0 ? v : pd(p.get("purchase_price"));
    }
    private static double pd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
    private static Integer toInt(Object o) { try { return o == null ? null : (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; } }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String norm(Object o) {
        if (o == null) return "";
        return String.valueOf(o).toLowerCase().trim()
                .replaceAll("[áà]", "a").replaceAll("[éè]", "e").replaceAll("[íì]", "i")
                .replaceAll("[óò]", "o").replaceAll("[úù]", "u");
    }
}
