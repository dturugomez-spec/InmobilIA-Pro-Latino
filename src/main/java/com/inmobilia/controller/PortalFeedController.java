package com.inmobilia.controller;

import com.inmobilia.service.PortalFeedXml;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.ClientIp;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Exportación de la cartera a portales inmobiliarios con un enlace XML.
 *
 * Cada inmobiliaria tiene un feed_token único. El feed genérico (ver {@link PortalFeedXml}) lleva la
 * moneda y el país de la inmobiliaria; lo pueden importar los portales de su país o un integrador.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PortalFeedController {

    static final Set<String> PORTALES = Set.of("feed");
    private static final Set<String> OPERACIONES = Set.of("venta", "alquiler", "alquiler_temporal", "traspaso");

    private final SupabaseService supabase;
    private final com.inmobilia.service.PaisService paises;

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
            if (feed == null) feed = new HashMap<>();
        } else {
            feed = rows.get(0);
        }
        model.addAttribute("feed", feed);
        model.addAttribute("baseUrl", com.inmobilia.util.UrlBase.BASE);

        // Inmuebles: qué se publica y qué le falta a cada uno para anunciarse bien
        List<Map<String, Object>> props = supabase.selectFilter("propiedades",
                "user_id=eq." + uid + "&order=created_at.desc&limit=300");
        Map<String, List<String>> galerias = galerias(uid, props);
        Set<String> ejemplo = deEjemplo(uid);
        boolean todas = Boolean.TRUE.equals(feed.get("publicar_todas"));
        List<Map<String, Object>> inmuebles = new ArrayList<>();
        int enPortales = 0;
        for (Map<String, Object> p : props) {
            Map<String, Object> n = PortalFeedXml.normalizar(p, galerias.get(String.valueOf(p.get("id"))));
            boolean marcado = Boolean.TRUE.equals(p.get("publicar_portales"));
            boolean esEjemplo = ejemplo.contains(String.valueOf(p.get("id")));
            boolean sale = !esEjemplo && (todas || marcado) && PortalFeedXml.publicable(p, n);
            if (sale) enPortales++;
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("id", p.get("id"));
            fila.put("titulo", n.get("titulo") != null ? n.get("titulo") : "Inmueble sin nombre");
            fila.put("municipio", n.get("municipio"));
            fila.put("precio", n.get("precio"));
            fila.put("operacion", n.get("operacion"));
            fila.put("fotos", ((List<?>) n.get("fotos")).size());
            fila.put("marcado", marcado);
            fila.put("sale", sale);
            fila.put("faltan", esEjemplo ? "es de ejemplo (nunca se publica)" : String.join(", ", PortalFeedXml.faltan(n)));
            inmuebles.add(fila);
        }
        model.addAttribute("inmuebles", inmuebles);
        model.addAttribute("enPortales", enPortales);
        com.inmobilia.util.Pais pais = com.inmobilia.util.PaisActual.get();
        model.addAttribute("portales", List.of(
                portal(feed, "feed", "Feed XML de propiedades", true,
                        "Da este enlace al área técnica de tu portal o integrador. Portales habituales en "
                                + pais.nombre() + ": " + pais.portales()
                                + ". Si un portal pide su propio formato, avísanos y lo añadimos.")));

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
        data.put("feed_enabled", "on".equals(form.get("feed_enabled")));
        data.put("publicar_todas", "on".equals(form.get("publicar_todas")));
        data.put("updated_at", OffsetDateTime.now().toString());

        List<Map<String, Object>> rows = supabase.selectFilter("portal_feeds", "user_id=eq." + uid);
        boolean ok = false;
        if (!rows.isEmpty()) {
            String id = String.valueOf(rows.get(0).get("id"));
            ok = supabase.update("portal_feeds", id, data, uid);
        }
        SessionUtils.flash(session, ok ? "✅ Configuración guardada" : "⚠️ No se pudo guardar la configuración");
        return "redirect:/feeds";
    }

    /** Marca qué inmuebles van a los portales y si son venta o alquiler. */
    @PostMapping("/feeds/inmuebles")
    public String guardarInmuebles(@RequestParam(name = "publicar", required = false) List<String> publicar,
                                   @RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Set<String> marcados = publicar == null ? Set.of() : new HashSet<>(publicar);
        int cambios = 0, fallos = 0;
        for (Map<String, Object> p : supabase.selectFilter("propiedades", "user_id=eq." + uid + "&limit=300")) {
            String id = String.valueOf(p.get("id"));
            if (!form.containsKey("op_" + id)) continue; // no estaba en la pantalla
            Map<String, Object> upd = new LinkedHashMap<>();
            boolean pub = marcados.contains(id);
            if (pub != Boolean.TRUE.equals(p.get("publicar_portales"))) upd.put("publicar_portales", pub);
            String op = form.get("op_" + id);
            if (OPERACIONES.contains(op) && !op.equals(PortalFeedXml.normalizar(p, List.of()).get("operacion")))
                upd.put("operacion", op);
            if (upd.isEmpty()) continue;
            if (supabase.update("propiedades", id, upd, uid)) cambios++; else fallos++;
        }
        SessionUtils.flash(session, fallos > 0
                ? "⚠️ No se pudieron guardar " + fallos + " inmuebles (¿falta ejecutar la migración V86 en Supabase?)"
                : cambios == 0 ? "Sin cambios" : "✅ " + cambios + " inmuebles actualizados");
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
                    Map.of("feed_token", newToken, "updated_at", OffsetDateTime.now().toString()), uid);
        }
        SessionUtils.flash(session, "🔄 Token regenerado");
        return "redirect:/feeds";
    }

    // ===== Endpoints públicos para portales =====

    @GetMapping(value = "/portal/{token}/{portal}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public ResponseEntity<String> feed(@PathVariable String token, @PathVariable String portal, HttpServletRequest req) {
        if (!PORTALES.contains(portal) || !token.matches("[A-Za-z0-9]{8,64}")) return ResponseEntity.notFound().build();
        long t0 = System.currentTimeMillis();

        List<Map<String, Object>> feeds = supabase.selectFilter("portal_feeds", "feed_token=eq." + token);
        if (feeds.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> cfg = feeds.get(0);
        // Activo salvo que se haya desactivado
        if (Boolean.FALSE.equals(cfg.get(portal + "_enabled"))) {
            return ResponseEntity.status(403).body("<error>Portal disabled</error>");
        }
        String userId = String.valueOf(cfg.get("user_id"));
        com.inmobilia.util.Pais pais = paises.usar(userId); // moneda y país de la inmobiliaria en el XML

        StringBuilder f = new StringBuilder("user_id=eq.").append(userId);
        if (!Boolean.TRUE.equals(cfg.get("publicar_todas"))) f.append("&publicar_portales=eq.true");
        f.append("&limit=500");
        List<Map<String, Object>> crudas = supabase.selectFilter("propiedades", f.toString());
        Map<String, List<String>> galerias = galerias(userId, crudas);
        Set<String> ejemplo = deEjemplo(userId);
        List<Map<String, Object>> props = new ArrayList<>();
        for (Map<String, Object> p : crudas) {
            if (ejemplo.contains(String.valueOf(p.get("id")))) continue; // los datos de demo nunca van a un portal
            Map<String, Object> n = PortalFeedXml.normalizar(p, galerias.get(String.valueOf(p.get("id"))));
            if (PortalFeedXml.publicable(p, n)) props.add(n);
        }

        String xml = PortalFeedXml.generico(cfg, props, com.inmobilia.util.UrlBase.BASE, pais);

        try {
            String ip = ClientIp.of(req);
            Map<String, Object> logData = new LinkedHashMap<>();
            logData.put("user_id", userId);
            logData.put("portal", portal);
            logData.put("ip", ip);
            logData.put("user_agent", req.getHeader("User-Agent"));
            logData.put("propiedades_count", props.size());
            logData.put("bytes_servidos", xml.getBytes(StandardCharsets.UTF_8).length);
            logData.put("duracion_ms", (int) (System.currentTimeMillis() - t0));
            logData.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("portal_feed_logs", logData);

            int total = cfg.get("accesos_total") instanceof Number n ? n.intValue() + 1 : 1;
            supabase.update("portal_feeds", String.valueOf(cfg.get("id")), Map.of(
                    "accesos_total", total,
                    "ultimo_acceso", OffsetDateTime.now().toString(),
                    "ultimo_ip", ip), userId);
        } catch (Exception e) { log.warn("Log feed err: {}", e.getMessage()); }

        return ResponseEntity.ok().contentType(new MediaType("application", "xml", StandardCharsets.UTF_8)).body(xml);
    }

    private static Map<String, Object> portal(Map<String, Object> feed, String clave, String nombre, boolean listo, String estado) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clave", clave);
        m.put("nombre", nombre);
        m.put("listo", listo);
        m.put("estado", estado);
        m.put("activo", !Boolean.FALSE.equals(feed.get(clave + "_enabled")));
        m.put("url", com.inmobilia.util.UrlBase.BASE + "/portal/" + feed.get("feed_token") + "/" + clave + ".xml");
        return m;
    }

    /** Inmuebles cargados como ejemplo para demos (ver EjemplosService). */
    private Set<String> deEjemplo(String orgId) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> r : supabase.selectFilter("ejemplos_demo", "user_id=eq." + orgId + "&tabla=eq.propiedades&limit=1000"))
            ids.add(String.valueOf(r.get("registro_id")));
        return ids;
    }

    /** Fotos de la galería de cada inmueble (las de la propia inmobiliaria), en pocas consultas. */
    private Map<String, List<String>> galerias(String orgId, List<Map<String, Object>> props) {
        List<String> ids = props.stream().map(p -> String.valueOf(p.get("id")))
                .filter(s -> s.matches("[0-9a-fA-F-]{1,64}")).toList();
        Map<String, List<String>> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 100) {
            String lote = String.join(",", ids.subList(i, Math.min(i + 100, ids.size())));
            for (Map<String, Object> a : supabase.selectFilter("attachments", "org_id=eq." + orgId
                    + "&entity_type=eq.propiedad&file_type=eq.image&entity_id=in.(" + lote + ")&order=created_at.asc&limit=5000")) {
                out.computeIfAbsent(String.valueOf(a.get("entity_id")), k -> new ArrayList<>()).add(String.valueOf(a.get("url")));
            }
        }
        return out;
    }
}
