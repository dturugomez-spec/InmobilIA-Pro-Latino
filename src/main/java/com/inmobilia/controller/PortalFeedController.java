package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Feeds XML públicos para portales inmobiliarios.
 *
 * Cada usuario tiene un feed_token único. Los portales (Idealista, Fotocasa,
 * Habitaclia, Pisos.com) consumen estas URLs vía cron diario.
 *
 * Formatos soportados:
 *  - Idealista DKV XML (estándar internacional)
 *  - Fotocasa XML
 *  - Habitaclia XML
 *  - Pisos.com XML (formato propio)
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PortalFeedController {

    private final SupabaseService supabase;

    // ===== Configuración (panel del usuario) =====

    @GetMapping("/feeds")
    public String config(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter("portal_feeds", "user_id=eq." + uid);
        Map<String, Object> feed;
        if (rows.isEmpty()) {
            // Crear automáticamente
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id", uid);
            data.put("created_at", OffsetDateTime.now().toString());
            feed = supabase.insert("portal_feeds", data);
        } else {
            feed = rows.get(0);
        }
        model.addAttribute("feed", feed);
        model.addAttribute("baseUrl", com.inmobilia.util.UrlBase.BASE);

        // Estadísticas últimos accesos
        List<Map<String, Object>> logs = supabase.selectFilter(
                "portal_feed_logs", "user_id=eq." + uid + "&order=created_at.desc&limit=20");
        model.addAttribute("logs", logs);
        return "portal_feeds_config";
    }

    @PostMapping("/feeds/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("company_name", form.get("company_name"));
        data.put("company_phone", form.get("company_phone"));
        data.put("company_email", form.get("company_email"));
        data.put("company_website", form.get("company_website"));
        data.put("company_logo_url", form.get("company_logo_url"));
        data.put("idealista_enabled", "on".equals(form.get("idealista_enabled")));
        data.put("fotocasa_enabled", "on".equals(form.get("fotocasa_enabled")));
        data.put("habitaclia_enabled", "on".equals(form.get("habitaclia_enabled")));
        data.put("pisoscom_enabled", "on".equals(form.get("pisoscom_enabled")));
        data.put("publicar_todas", "on".equals(form.get("publicar_todas")));
        data.put("updated_at", OffsetDateTime.now().toString());

        List<Map<String, Object>> rows = supabase.selectFilter("portal_feeds", "user_id=eq." + uid);
        if (!rows.isEmpty()) {
            supabase.update("portal_feeds", String.valueOf(rows.get(0).get("id")), data);
        }
        SessionUtils.flash(session, "✅ Configuración guardada");
        return "redirect:/feeds";
    }

    @PostMapping("/feeds/regenerar-token")
    public String regenerate(HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("portal_feeds", "user_id=eq." + uid);
        if (!rows.isEmpty()) {
            String newToken = UUID.randomUUID().toString().replace("-", "");
            supabase.update("portal_feeds", String.valueOf(rows.get(0).get("id")),
                    Map.of("feed_token", newToken, "updated_at", OffsetDateTime.now().toString()));
        }
        SessionUtils.flash(session, "🔄 Token regenerado");
        return "redirect:/feeds";
    }

    // ===== Endpoints públicos para portales =====

    @GetMapping(value = "/portal/{token}/idealista.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> idealistaFeed(@PathVariable String token, HttpServletRequest req) {
        return generateFeed(token, "idealista", req);
    }

    @GetMapping(value = "/portal/{token}/fotocasa.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> fotocasaFeed(@PathVariable String token, HttpServletRequest req) {
        return generateFeed(token, "fotocasa", req);
    }

    @GetMapping(value = "/portal/{token}/habitaclia.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> habitacliaFeed(@PathVariable String token, HttpServletRequest req) {
        return generateFeed(token, "habitaclia", req);
    }

    @GetMapping(value = "/portal/{token}/pisoscom.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> pisoscomFeed(@PathVariable String token, HttpServletRequest req) {
        return generateFeed(token, "pisoscom", req);
    }

    // ===== Lógica común =====

    private ResponseEntity<String> generateFeed(String token, String portal, HttpServletRequest req) {
        long t0 = System.currentTimeMillis();

        List<Map<String, Object>> feeds = supabase.selectFilter("portal_feeds", "feed_token=eq." + token);
        if (feeds.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> cfg = feeds.get(0);

        // Comprobar portal activo
        String portalCol = portal + "_enabled";
        if (!Boolean.TRUE.equals(cfg.get(portalCol))) {
            return ResponseEntity.status(403).body("<error>Portal disabled</error>");
        }

        String userId = String.valueOf(cfg.get("user_id"));

        // Filtro propiedades
        StringBuilder f = new StringBuilder("user_id=eq.").append(userId);
        if (!Boolean.TRUE.equals(cfg.get("publicar_todas"))) {
            f.append("&publicar_portales=eq.true");
        }
        f.append("&limit=500");

        List<Map<String, Object>> props = supabase.selectFilter("propiedades", f.toString());
        props.removeIf(p -> "Reservado".equalsIgnoreCase(String.valueOf(p.get("status"))));
        props = normalizarParaFeed(props);

        String xml;
        switch (portal) {
            case "idealista": xml = buildIdealistaXml(cfg, props); break;
            case "fotocasa":  xml = buildFotocasaXml(cfg, props); break;
            case "habitaclia": xml = buildHabitacliaXml(cfg, props); break;
            case "pisoscom":  xml = buildPisoscomXml(cfg, props); break;
            default: return ResponseEntity.badRequest().body("<error>Unknown portal</error>");
        }

        // Log
        try {
            Map<String, Object> logData = new LinkedHashMap<>();
            logData.put("user_id", userId);
            logData.put("portal", portal);
            logData.put("ip", req.getRemoteAddr());
            logData.put("user_agent", req.getHeader("User-Agent"));
            logData.put("propiedades_count", props.size());
            logData.put("bytes_servidos", xml.getBytes().length);
            logData.put("duracion_ms", (int)(System.currentTimeMillis() - t0));
            logData.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("portal_feed_logs", logData);

            // Incrementar contador
            int total = ((Number) cfg.getOrDefault("accesos_total", 0)).intValue() + 1;
            supabase.update("portal_feeds", String.valueOf(cfg.get("id")), Map.of(
                    "accesos_total", total,
                    "ultimo_acceso", OffsetDateTime.now().toString(),
                    "ultimo_ip", req.getRemoteAddr()
            ));
        } catch (Exception e) { log.warn("Log feed err: {}", e.getMessage()); }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }

    // ===== Normalización al vocabulario de los feeds =====

    /**
     * Convierte cada propiedad del esquema real (name/city/current_value/area_m2/...)
     * a las claves en español que esperan los generadores XML de los 4 portales.
     * Los extras (garaje, ascensor...) se detectan en la descripción.
     */
    private List<Map<String, Object>> normalizarParaFeed(List<Map<String, Object>> props) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : props) {
            Map<String, Object> n = new LinkedHashMap<>(p);
            Object precio = p.get("current_value") != null ? p.get("current_value") : p.get("purchase_price");
            n.put("precio", precio);
            n.put("metros_cuadrados", p.get("area_m2"));
            n.put("habitaciones", p.get("bedrooms"));
            n.put("banos", p.get("bathrooms"));
            n.put("direccion", p.getOrDefault("address", p.get("name")));
            n.put("titulo", p.getOrDefault("name", p.get("address")));
            n.put("municipio", p.get("city"));
            n.put("provincia", p.get("city"));
            n.put("codigo_postal", p.get("postal_code"));
            n.put("descripcion", p.get("notes"));
            n.put("referencia", p.get("reference"));
            n.put("tipo", p.get("type"));
            n.put("operacion", "venta");
            if (p.get("featured_photo") != null && !String.valueOf(p.get("featured_photo")).isBlank()) {
                n.put("fotos", List.of(p.get("featured_photo")));
            }
            String notas = String.valueOf(p.getOrDefault("notes", "")).toLowerCase();
            n.put("garaje", notas.contains("garaje"));
            n.put("ascensor", notas.contains("ascensor"));
            n.put("terraza", notas.contains("terraza"));
            n.put("piscina", notas.contains("piscina"));
            n.put("amueblado", notas.contains("amueblad"));
            n.put("aire_acondicionado", notas.contains("aire acondicionado") || notas.contains("a/a"));
            out.add(n);
        }
        return out;
    }

    // ===== Generadores XML =====

    private String buildIdealistaXml(Map<String, Object> cfg, List<Map<String, Object>> props) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<properties xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"2.0\">\n");
        for (Map<String, Object> p : props) {
            x.append("  <property>\n");
            x.append("    <reference>").append(esc(p.get("referencia") != null ? p.get("referencia") : p.get("id"))).append("</reference>\n");
            x.append("    <operation>").append(mapOperationIdealista(str(p.get("operacion")))).append("</operation>\n");
            x.append("    <property_type>").append(mapTypeIdealista(str(p.get("tipo")))).append("</property_type>\n");
            x.append("    <price>").append(num(p.get("precio"))).append("</price>\n");
            x.append("    <currency>EUR</currency>\n");
            x.append("    <surface>").append(num(p.get("metros_cuadrados"))).append("</surface>\n");
            x.append("    <rooms>").append(num(p.get("habitaciones"))).append("</rooms>\n");
            x.append("    <bathrooms>").append(num(p.get("banos"))).append("</bathrooms>\n");
            x.append("    <address>").append(esc(p.get("direccion"))).append("</address>\n");
            x.append("    <town>").append(esc(p.get("municipio"))).append("</town>\n");
            x.append("    <province>").append(esc(p.get("provincia"))).append("</province>\n");
            x.append("    <country>ES</country>\n");
            if (p.get("codigo_postal") != null) x.append("    <postal_code>").append(esc(p.get("codigo_postal"))).append("</postal_code>\n");
            if (p.get("lat") != null && p.get("lng") != null) {
                x.append("    <latitude>").append(num(p.get("lat"))).append("</latitude>\n");
                x.append("    <longitude>").append(num(p.get("lng"))).append("</longitude>\n");
            }
            x.append("    <description><![CDATA[").append(s(p.get("descripcion"))).append("]]></description>\n");

            // Características
            x.append("    <features>\n");
            if (Boolean.TRUE.equals(p.get("garaje"))) x.append("      <feature>garage</feature>\n");
            if (Boolean.TRUE.equals(p.get("ascensor"))) x.append("      <feature>lift</feature>\n");
            if (Boolean.TRUE.equals(p.get("terraza"))) x.append("      <feature>terrace</feature>\n");
            if (Boolean.TRUE.equals(p.get("piscina"))) x.append("      <feature>swimming_pool</feature>\n");
            if (Boolean.TRUE.equals(p.get("amueblado"))) x.append("      <feature>furnished</feature>\n");
            if (Boolean.TRUE.equals(p.get("aire_acondicionado"))) x.append("      <feature>air_conditioning</feature>\n");
            x.append("    </features>\n");

            // Eficiencia energética
            if (p.get("eficiencia_energetica") != null) {
                x.append("    <energy_certificate>\n");
                x.append("      <consumption>").append(esc(p.get("eficiencia_energetica"))).append("</consumption>\n");
                x.append("    </energy_certificate>\n");
            }

            // Fotos
            Object fotos = p.get("fotos");
            if (fotos instanceof List) {
                x.append("    <images>\n");
                int order = 1;
                for (Object f : (List<?>) fotos) {
                    x.append("      <image order=\"").append(order++).append("\">").append(esc(str(f))).append("</image>\n");
                }
                x.append("    </images>\n");
            }

            // Contacto
            x.append("    <contact>\n");
            x.append("      <name>").append(esc(cfg.get("company_name"))).append("</name>\n");
            x.append("      <phone>").append(esc(cfg.get("company_phone"))).append("</phone>\n");
            x.append("      <email>").append(esc(cfg.get("company_email"))).append("</email>\n");
            x.append("    </contact>\n");

            x.append("  </property>\n");
        }
        x.append("</properties>\n");
        return x.toString();
    }

    private String buildFotocasaXml(Map<String, Object> cfg, List<Map<String, Object>> props) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<fotocasa>\n");
        x.append("  <agency>\n");
        x.append("    <name>").append(esc(cfg.get("company_name"))).append("</name>\n");
        x.append("    <phone>").append(esc(cfg.get("company_phone"))).append("</phone>\n");
        x.append("    <email>").append(esc(cfg.get("company_email"))).append("</email>\n");
        x.append("  </agency>\n");
        x.append("  <properties>\n");
        for (Map<String, Object> p : props) {
            x.append("    <property>\n");
            x.append("      <id>").append(esc(p.get("id"))).append("</id>\n");
            x.append("      <ref>").append(esc(p.get("referencia") != null ? p.get("referencia") : p.get("id"))).append("</ref>\n");
            x.append("      <type>").append(esc(p.get("tipo"))).append("</type>\n");
            x.append("      <transaction>").append(esc(p.get("operacion"))).append("</transaction>\n");
            x.append("      <price>").append(num(p.get("precio"))).append("</price>\n");
            x.append("      <area>").append(num(p.get("metros_cuadrados"))).append("</area>\n");
            x.append("      <bedrooms>").append(num(p.get("habitaciones"))).append("</bedrooms>\n");
            x.append("      <bathrooms>").append(num(p.get("banos"))).append("</bathrooms>\n");
            x.append("      <address>").append(esc(p.get("direccion"))).append("</address>\n");
            x.append("      <city>").append(esc(p.get("municipio"))).append("</city>\n");
            x.append("      <region>").append(esc(p.get("provincia"))).append("</region>\n");
            x.append("      <description><![CDATA[").append(s(p.get("descripcion"))).append("]]></description>\n");
            Object fotos = p.get("fotos");
            if (fotos instanceof List) {
                x.append("      <photos>\n");
                for (Object f : (List<?>) fotos) {
                    x.append("        <photo>").append(esc(str(f))).append("</photo>\n");
                }
                x.append("      </photos>\n");
            }
            x.append("    </property>\n");
        }
        x.append("  </properties>\n</fotocasa>\n");
        return x.toString();
    }

    private String buildHabitacliaXml(Map<String, Object> cfg, List<Map<String, Object>> props) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<habitaclia version=\"3.0\">\n");
        x.append("  <agency name=\"").append(esc(cfg.get("company_name"))).append("\" phone=\"").append(esc(cfg.get("company_phone"))).append("\" email=\"").append(esc(cfg.get("company_email"))).append("\"/>\n");
        for (Map<String, Object> p : props) {
            x.append("  <listing>\n");
            x.append("    <code>").append(esc(p.get("referencia") != null ? p.get("referencia") : p.get("id"))).append("</code>\n");
            x.append("    <action>").append(esc(p.get("operacion"))).append("</action>\n");
            x.append("    <typology>").append(esc(p.get("tipo"))).append("</typology>\n");
            x.append("    <price currency=\"EUR\">").append(num(p.get("precio"))).append("</price>\n");
            x.append("    <surface unit=\"sqm\">").append(num(p.get("metros_cuadrados"))).append("</surface>\n");
            x.append("    <rooms>").append(num(p.get("habitaciones"))).append("</rooms>\n");
            x.append("    <baths>").append(num(p.get("banos"))).append("</baths>\n");
            x.append("    <location>\n");
            x.append("      <address>").append(esc(p.get("direccion"))).append("</address>\n");
            x.append("      <city>").append(esc(p.get("municipio"))).append("</city>\n");
            x.append("    </location>\n");
            x.append("    <description xml:lang=\"es\"><![CDATA[").append(s(p.get("descripcion"))).append("]]></description>\n");
            x.append("  </listing>\n");
        }
        x.append("</habitaclia>\n");
        return x.toString();
    }

    private String buildPisoscomXml(Map<String, Object> cfg, List<Map<String, Object>> props) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<pisos>\n");
        for (Map<String, Object> p : props) {
            x.append("  <piso>\n");
            x.append("    <referencia>").append(esc(p.get("referencia") != null ? p.get("referencia") : p.get("id"))).append("</referencia>\n");
            x.append("    <operacion>").append(esc(p.get("operacion"))).append("</operacion>\n");
            x.append("    <tipo>").append(esc(p.get("tipo"))).append("</tipo>\n");
            x.append("    <precio>").append(num(p.get("precio"))).append("</precio>\n");
            x.append("    <metros>").append(num(p.get("metros_cuadrados"))).append("</metros>\n");
            x.append("    <habitaciones>").append(num(p.get("habitaciones"))).append("</habitaciones>\n");
            x.append("    <banos>").append(num(p.get("banos"))).append("</banos>\n");
            x.append("    <direccion>").append(esc(p.get("direccion"))).append("</direccion>\n");
            x.append("    <ciudad>").append(esc(p.get("municipio"))).append("</ciudad>\n");
            x.append("    <provincia>").append(esc(p.get("provincia"))).append("</provincia>\n");
            x.append("    <descripcion><![CDATA[").append(s(p.get("descripcion"))).append("]]></descripcion>\n");
            x.append("    <contacto>\n");
            x.append("      <empresa>").append(esc(cfg.get("company_name"))).append("</empresa>\n");
            x.append("      <telefono>").append(esc(cfg.get("company_phone"))).append("</telefono>\n");
            x.append("    </contacto>\n");
            x.append("  </piso>\n");
        }
        x.append("</pisos>\n");
        return x.toString();
    }

    // Mapeos
    private String mapOperationIdealista(String o) {
        if (o == null) return "sale";
        return switch (o.toLowerCase()) {
            case "alquiler", "alquiler_temporal" -> "rent";
            case "transfer", "traspaso" -> "transfer";
            default -> "sale";
        };
    }

    private String mapTypeIdealista(String t) {
        if (t == null) return "flat";
        return switch (t.toLowerCase()) {
            case "piso", "apartamento" -> "flat";
            case "casa", "chalet" -> "house";
            case "atico", "ático" -> "penthouse";
            case "duplex", "dúplex" -> "duplex";
            case "estudio" -> "studio";
            case "local" -> "premises";
            case "oficina" -> "office";
            case "garaje" -> "garage";
            case "trastero" -> "storage_room";
            case "terreno", "parcela" -> "land";
            default -> "flat";
        };
    }

    // Helpers
    private static String esc(Object o) {
        if (o == null) return "";
        return String.valueOf(o)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String num(Object o) { if (o == null) return "0"; try { return String.valueOf(Double.parseDouble(String.valueOf(o))); } catch(Exception e) { return "0"; } }
}
