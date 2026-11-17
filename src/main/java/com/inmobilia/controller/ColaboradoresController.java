package com.inmobilia.controller;

import com.inmobilia.service.EmailService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.StorageService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Red de colaboradores de la inmobiliaria: fotógrafos, técnicos,
 * reformas, limpieza, cerrajeros… Se les pide un trabajo desde la ficha del inmueble; el
 * colaborador recibe un enlace (sin cuenta) donde ve el encargo, lo acepta con precio y fecha, y
 * sube el resultado. La agencia lo ve en la ficha del encargo y lo valora.
 *
 * Por ahora son los colaboradores de confianza de cada inmobiliaria: no hay red común ni cobros
 * ni comisiones dentro de la app.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ColaboradoresController {

    /** Servicios que se pueden pedir, con su nombre. */
    public static final Map<String, String> SERVICIOS = new LinkedHashMap<>();
    static {
        SERVICIOS.put("fotos", "Fotografía y vídeo");
        SERVICIOS.put("plano", "Plano");
        SERVICIOS.put("limpieza", "Limpieza");
        SERVICIOS.put("reformas", "Reformas y reparaciones");
        SERVICIOS.put("cerrajero", "Cerrajería");
        SERVICIOS.put("mudanzas", "Mudanzas y vaciado");
        SERVICIOS.put("tasacion", "Tasación");
        SERVICIOS.put("abogado", "Abogado o gestoría");
        SERVICIOS.put("otro", "Otro servicio");
    }
    private static final int DIAS_ENLACE = 60;
    private static final String BASE = com.inmobilia.util.UrlBase.BASE;

    private final SupabaseService supabase;
    private final StorageService storage;
    private final EmailService email;
    private final NotificationService notif;
    private final com.inmobilia.service.PaisService paises;

    // ── Pantalla de la agencia ───────────────────────────────────────────

    @GetMapping("/colaboradores")
    public String pantalla(@RequestParam(required = false) String propiedad, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        List<Map<String, Object>> colaboradores = supabase.selectFilter("colaboradores", "user_id=eq." + org + "&order=servicio.asc,nombre.asc&limit=1000");
        List<Map<String, Object>> encargos = supabase.selectFilter("encargos_colaborador", "user_id=eq." + org + "&order=created_at.desc&limit=300");
        Map<String, Map<String, Object>> porId = new HashMap<>();
        for (Map<String, Object> c : colaboradores) {
            porId.put(String.valueOf(c.get("id")), c);
            c.put("_servicio", SERVICIOS.getOrDefault(String.valueOf(c.get("servicio")), "Otro servicio"));
            List<Integer> notas = new ArrayList<>();
            int hechos = 0;
            for (Map<String, Object> e : encargos) {
                if (!String.valueOf(c.get("id")).equals(String.valueOf(e.get("colaborador_id")))) continue;
                if ("hecho".equals(e.get("estado"))) hechos++;
                if (e.get("valoracion") instanceof Number n) notas.add(n.intValue());
            }
            c.put("_hechos", hechos);
            c.put("_nota", notas.isEmpty() ? null : String.format(Locale.ROOT, "%.1f", notas.stream().mapToInt(Integer::intValue).average().orElse(0)).replace('.', ','));
        }
        Map<String, String> inmuebles = new LinkedHashMap<>();
        List<Map<String, Object>> props = supabase.selectFilter("propiedades", "select=id,name,address,city&user_id=eq." + org + "&limit=2000");
        for (Map<String, Object> p : props) inmuebles.put(String.valueOf(p.get("id")), Objects.toString(p.get("name"), "Inmueble"));
        for (Map<String, Object> e : encargos) {
            Map<String, Object> c = porId.get(String.valueOf(e.get("colaborador_id")));
            e.put("_colaborador", c == null ? "—" : c.get("nombre"));
            e.put("_servicio", SERVICIOS.getOrDefault(String.valueOf(e.get("servicio")), "Otro servicio"));
            e.put("_inmueble", inmuebles.getOrDefault(String.valueOf(e.get("propiedad_id")), ""));
        }
        model.addAttribute("page", "Colaboradores");
        model.addAttribute("colaboradores", colaboradores);
        model.addAttribute("activos", colaboradores.stream().filter(c -> !Boolean.FALSE.equals(c.get("activo"))).toList());
        model.addAttribute("encargos", encargos);
        model.addAttribute("servicios", SERVICIOS);
        model.addAttribute("propiedades", props);
        model.addAttribute("propiedadSel", propiedad != null && inmuebles.containsKey(propiedad) ? propiedad : "");
        model.addAttribute("hoy", LocalDate.now());
        return "colaboradores/lista";
    }

    @PostMapping("/colaboradores")
    public String guardar(@RequestParam(required = false) String id, @RequestParam String nombre, @RequestParam String servicio,
                          @RequestParam(required = false) String email, @RequestParam(required = false) String telefono,
                          @RequestParam(required = false) String zona, @RequestParam(required = false) String precio_orientativo,
                          @RequestParam(required = false) String notas, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        String n = recorta(nombre, 150);
        if (n == null || !SERVICIOS.containsKey(servicio)) {
            SessionUtils.flash(session, "Indica el nombre y el servicio.");
            return "redirect:/colaboradores";
        }
        String correo = recorta(email, 200);
        if (correo != null && !correo.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            SessionUtils.flash(session, "Ese correo no parece válido.");
            return "redirect:/colaboradores";
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nombre", n);
        d.put("servicio", servicio);
        d.put("email", vaciable(correo == null ? null : correo.toLowerCase(Locale.ROOT)));
        d.put("telefono", vaciable(recorta(telefono, 40)));
        d.put("zona", vaciable(recorta(zona, 150)));
        d.put("precio_orientativo", vaciable(recorta(precio_orientativo, 150)));
        d.put("notas", vaciable(recorta(notas, 1000)));
        boolean ok;
        if (id != null && id.matches("[0-9a-fA-F-]{36}") && !supabase.selectOwned("colaboradores", id, org).isEmpty()) {
            ok = supabase.update("colaboradores", id, d, org);
        } else {
            d.replaceAll((k, v) -> v == SupabaseService.VACIAR ? null : v);
            d.put("user_id", org);
            d.put("activo", true);
            Map<String, Object> r = supabase.insert("colaboradores", d);
            ok = r != null && !r.isEmpty();
        }
        SessionUtils.flash(session, ok ? "Colaborador guardado." : "No se pudo guardar. " + Objects.toString(supabase.lastError(), ""));
        return "redirect:/colaboradores";
    }

    @PostMapping("/colaboradores/{id}/activo")
    public String activo(@PathVariable String id, @RequestParam boolean activo, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        if (id.matches("[0-9a-fA-F-]{36}")) supabase.update("colaboradores", id, Map.of("activo", activo), org);
        return "redirect:/colaboradores";
    }

    /** Pedir un trabajo: crea el encargo, manda el enlace al colaborador y avisa de cómo compartirlo si no tiene correo. */
    @PostMapping("/colaboradores/encargos")
    public String pedir(@RequestParam String colaborador, @RequestParam(required = false) String propiedad,
                        @RequestParam(required = false) String descripcion, @RequestParam(required = false) String contacto,
                        @RequestParam(required = false) String fecha_deseada, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        List<Map<String, Object>> c = colaborador.matches("[0-9a-fA-F-]{36}") ? supabase.selectOwned("colaboradores", colaborador, org) : List.of();
        if (c.isEmpty()) { SessionUtils.flash(session, "Elige un colaborador."); return "redirect:/colaboradores"; }
        Map<String, Object> col = c.get(0);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("user_id", org);
        d.put("colaborador_id", colaborador);
        d.put("servicio", col.get("servicio"));
        d.put("descripcion", recorta(descripcion, 2000));
        d.put("contacto", recorta(contacto, 300));
        try { if (fecha_deseada != null && !fecha_deseada.isBlank()) d.put("fecha_deseada", LocalDate.parse(fecha_deseada).toString()); } catch (Exception ignored) { }
        String inmueble = "";
        if (propiedad != null && propiedad.matches("[0-9a-fA-F-]{36}")) {
            List<Map<String, Object>> p = supabase.selectOwned("propiedades", propiedad, org);
            if (!p.isEmpty()) {
                d.put("propiedad_id", propiedad);
                inmueble = Objects.toString(p.get(0).get("name"), "");
                String dir = String.join(", ", Arrays.asList(Objects.toString(p.get(0).get("address"), ""), Objects.toString(p.get(0).get("postal_code"), ""),
                        Objects.toString(p.get(0).get("city"), ""))).replaceAll("(, )+$|^(, )+", "").replaceAll("(, ){2,}", ", ");
                d.put("direccion", dir.isBlank() ? inmueble : dir);
            }
        }
        d.put("estado", "enviado");
        d.put("token", nuevoToken());
        d.put("token_caduca", OffsetDateTime.now().plusDays(DIAS_ENLACE).toString());
        d.put("created_by", SessionUtils.realUid(session));
        Map<String, Object> r = supabase.insert("encargos_colaborador", d);
        if (r == null || r.get("id") == null) {
            SessionUtils.flash(session, "No se pudo crear el encargo. " + Objects.toString(supabase.lastError(), ""));
            return "redirect:/colaboradores";
        }
        String enlace = BASE + "/encargo/" + d.get("token");
        boolean enviado = false;
        String correo = Objects.toString(col.get("email"), "");
        if (!correo.isBlank()) {
            Map<String, Object> negocio = negocio(org);
            String agencia = Objects.toString(negocio.get("legal_name"), "Una inmobiliaria");
            String html = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;color:#0f172a'>"
                    + "<p>Hola " + esc(Objects.toString(col.get("nombre"), "")) + ",</p>"
                    + "<p><strong>" + esc(agencia) + "</strong> te pide un trabajo: <strong>" + esc(SERVICIOS.getOrDefault(String.valueOf(col.get("servicio")), "servicio")) + "</strong>"
                    + (d.get("direccion") != null ? " en " + esc(String.valueOf(d.get("direccion"))) : "") + ".</p>"
                    + (d.get("descripcion") != null ? "<p>" + esc(String.valueOf(d.get("descripcion"))) + "</p>" : "")
                    + "<p style='margin:24px 0'><a href='" + enlace + "' style='display:inline-block;padding:12px 24px;background:#0f172a;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold'>Ver el encargo y contestar</a></p>"
                    + "<p style='font-size:12px;color:#64748b'>Desde ese enlace puedes aceptarlo con tu precio y fecha, y subir el resultado. No necesitas cuenta.</p></div>";
            try {
                EmailService.SendResult s = email.sendConRespuesta(correo, "Encargo de " + agencia + ": " + SERVICIOS.getOrDefault(String.valueOf(col.get("servicio")), "servicio"),
                        html, Objects.toString(negocio.get("contact_email"), SessionUtils.email(session)));
                enviado = s != null && s.success();
            } catch (Exception ex) { log.warn("Correo de encargo: {}", ex.getMessage()); }
        }
        SessionUtils.flash(session, enviado ? "Encargo enviado por correo a " + col.get("nombre") + "."
                : "Encargo creado. No se ha podido enviar por correo: comparte tú el enlace con " + col.get("nombre") + " desde la ficha del encargo.");
        return "redirect:/colaboradores/encargos/" + r.get("id");
    }

    @GetMapping("/colaboradores/encargos/{id}")
    public String encargo(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        Map<String, Object> e = encargoDe(id, org);
        if (e == null) return "redirect:/colaboradores";
        Map<String, Object> col = uno(supabase.selectOwned("colaboradores", String.valueOf(e.get("colaborador_id")), org));
        String enlace = BASE + "/encargo/" + e.get("token");
        String tel = col == null ? "" : com.inmobilia.util.PaisActual.get().telefonoInternacional(Objects.toString(col.get("telefono"), ""));
        model.addAttribute("page", "Encargo · " + SERVICIOS.getOrDefault(String.valueOf(e.get("servicio")), "servicio"));
        model.addAttribute("e", e);
        model.addAttribute("col", col == null ? Map.of() : col);
        model.addAttribute("servicio", SERVICIOS.getOrDefault(String.valueOf(e.get("servicio")), "Otro servicio"));
        model.addAttribute("enlace", abierto(e) ? enlace : null);
        model.addAttribute("whatsapp", abierto(e) && tel.length() >= 11
                ? "https://wa.me/" + tel + "?text=" + URLEncoder.encode("Hola, te paso un encargo: " + enlace, StandardCharsets.UTF_8) : null);
        return "colaboradores/encargo";
    }

    @PostMapping("/colaboradores/encargos/{id}/cancelar")
    public String cancelar(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        Map<String, Object> e = encargoDe(id, org);
        if (e == null) return "redirect:/colaboradores";
        if (!"hecho".equals(e.get("estado"))) supabase.update("encargos_colaborador", id, Map.of("estado", "cancelado"), org);
        SessionUtils.flash(session, "Encargo cancelado: el enlace del colaborador ya no funciona.");
        return "redirect:/colaboradores/encargos/" + id;
    }

    @PostMapping("/colaboradores/encargos/{id}/valorar")
    public String valorar(@PathVariable String id, @RequestParam int valoracion, @RequestParam(required = false) String nota, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        if (encargoDe(id, org) == null) return "redirect:/colaboradores";
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("valoracion", Math.max(1, Math.min(5, valoracion)));
        d.put("valoracion_nota", vaciable(recorta(nota, 500)));
        supabase.update("encargos_colaborador", id, d, org);
        SessionUtils.flash(session, "Valoración guardada.");
        return "redirect:/colaboradores/encargos/" + id;
    }

    @GetMapping("/colaboradores/encargos/{id}/entrega")
    public ResponseEntity<byte[]> entrega(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return ResponseEntity.status(404).build();
        String org = SessionUtils.uid(session);
        Map<String, Object> e = encargoDe(id, org);
        String ruta = e == null ? "" : Objects.toString(e.get("entrega_ruta"), "");
        if (ruta.isBlank() || !ruta.startsWith(org.replaceAll("[^a-zA-Z0-9-]", "") + "/")) return ResponseEntity.status(404).build();
        byte[] b = storage.descargarPrivado(ruta);
        if (b == null) return ResponseEntity.status(404).build();
        String nombre = Objects.toString(e.get("entrega_nombre"), "entrega").replaceAll("[^A-Za-z0-9._-]", "_");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(tipoContenido(ruta)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nombre + "\"").body(b);
    }

    // ── Página del colaborador (sin cuenta) ──────────────────────────────

    @GetMapping("/encargo/{token}")
    public String publica(@PathVariable String token, Model model) {
        Map<String, Object> e = porToken(token);
        if (e == null) { model.addAttribute("caducado", true); return "colaboradores/publico"; }
        Map<String, Object> negocio = negocio(String.valueOf(e.get("user_id")));
        paises.usar(String.valueOf(e.get("user_id")));   // moneda y formato del país de la inmobiliaria
        model.addAttribute("e", e);
        model.addAttribute("agencia", Objects.toString(negocio.get("legal_name"), "La inmobiliaria"));
        model.addAttribute("telefonoAgencia", negocio.get("contact_phone"));
        model.addAttribute("servicio", SERVICIOS.getOrDefault(String.valueOf(e.get("servicio")), "Servicio"));
        model.addAttribute("token", token);
        return "colaboradores/publico";
    }

    @PostMapping("/encargo/{token}/responder")
    public String responder(@PathVariable String token, @RequestParam String decision, @RequestParam(required = false) String precio,
                            @RequestParam(required = false) String fecha_prevista, @RequestParam(required = false) String respuesta, Model model) {
        Map<String, Object> e = porToken(token);
        if (e == null) { model.addAttribute("caducado", true); return "colaboradores/publico"; }
        String org = String.valueOf(e.get("user_id")), id = String.valueOf(e.get("id"));
        if (!"aceptar".equals(decision) && !"rechazar".equals(decision)) return publica(token, model);
        boolean acepta = "aceptar".equals(decision);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("estado", acepta ? "aceptado" : "rechazado");
        d.put("respondido_at", OffsetDateTime.now().toString());
        d.put("respuesta", vaciable(recorta(respuesta, 1000)));
        if (acepta) {
            d.put("precio", numero(precio));
            try { d.put("fecha_prevista", fecha_prevista == null || fecha_prevista.isBlank() ? SupabaseService.VACIAR : LocalDate.parse(fecha_prevista).toString()); }
            catch (Exception ex) { d.put("fecha_prevista", SupabaseService.VACIAR); }
        }
        supabase.update("encargos_colaborador", id, d, org);
        notif.create(org, acepta ? "Encargo aceptado" : "Encargo rechazado", "El colaborador ha " + (acepta ? "aceptado" : "rechazado") + " el encargo de "
                + SERVICIOS.getOrDefault(String.valueOf(e.get("servicio")), "servicio") + ".", "/colaboradores/encargos/" + id, acepta ? "media" : "alta");
        if (!acepta) {   // al rechazarlo el enlace deja de valer: se le da las gracias y nada más
            model.addAttribute("rechazado", true);
            model.addAttribute("agencia", Objects.toString(negocio(org).get("legal_name"), "La inmobiliaria"));
            return "colaboradores/publico";
        }
        model.addAttribute("mensaje", "Aceptado. Cuando termines, sube aquí el resultado.");
        return publica(token, model);
    }

    @PostMapping("/encargo/{token}/entregar")
    public String entregar(@PathVariable String token, @RequestParam(value = "archivo", required = false) MultipartFile archivo,
                           @RequestParam(required = false) String respuesta, Model model) {
        Map<String, Object> e = porToken(token);
        if (e == null) { model.addAttribute("caducado", true); return "colaboradores/publico"; }
        String org = String.valueOf(e.get("user_id")), id = String.valueOf(e.get("id"));
        Map<String, Object> d = new LinkedHashMap<>();
        try {
            if (archivo != null && !archivo.isEmpty()) {
                if (!StorageService.esDocumento(archivo)) {
                    model.addAttribute("mensaje", "Sube un PDF o una foto (JPG o PNG). Si son muchas fotos, envíaselas a la inmobiliaria por el medio habitual.");
                    return publica(token, model);
                }
                d.put("entrega_ruta", storage.subirPrivado(archivo.getBytes(), archivo.getContentType(), archivo.getOriginalFilename(), org));
                String nombre = archivo.getOriginalFilename() == null ? "entrega" : archivo.getOriginalFilename();
                d.put("entrega_nombre", nombre.length() > 150 ? nombre.substring(0, 150) : nombre);
            }
        } catch (Exception ex) {
            model.addAttribute("mensaje", "No se pudo guardar el archivo. Inténtalo de nuevo.");
            return publica(token, model);
        }
        d.put("estado", "hecho");
        d.put("hecho_at", OffsetDateTime.now().toString());
        if (recorta(respuesta, 1000) != null) d.put("respuesta", recorta(respuesta, 1000));
        supabase.update("encargos_colaborador", id, d, org);
        notif.create(org, "Encargo terminado", "El colaborador ha terminado el encargo de " + SERVICIOS.getOrDefault(String.valueOf(e.get("servicio")), "servicio")
                + (d.containsKey("entrega_ruta") ? " y ha subido el resultado." : "."), "/colaboradores/encargos/" + id, "media");
        model.addAttribute("hecho", true);
        model.addAttribute("agencia", Objects.toString(negocio(org).get("legal_name"), "La inmobiliaria"));
        return "colaboradores/publico";
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    /** El enlace del colaborador vale mientras el encargo esté enviado o aceptado y no haya caducado. */
    static boolean abierto(Map<String, Object> e) {
        OffsetDateTime c = com.inmobilia.util.Fechas.leer(e.get("token_caduca"));
        return ("enviado".equals(e.get("estado")) || "aceptado".equals(e.get("estado"))) && c != null && c.isAfter(OffsetDateTime.now());
    }

    private Map<String, Object> porToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,64}")) return null;
        Map<String, Object> e = uno(supabase.selectFilter("encargos_colaborador", "token=eq." + token + "&limit=1"));
        return e == null || !abierto(e) ? null : e;
    }

    private Map<String, Object> encargoDe(String id, String org) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return null;
        return uno(supabase.selectOwned("encargos_colaborador", id, org));
    }

    private Map<String, Object> negocio(String org) {
        Map<String, Object> n = uno(supabase.selectFilter("business_profile", "org_id=eq." + org + "&limit=1"));
        return n == null ? Map.of() : n;
    }

    private static Map<String, Object> uno(List<Map<String, Object>> l) { return l == null || l.isEmpty() ? null : l.get(0); }

    private static String recorta(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Object vaciable(String s) { return s == null ? SupabaseService.VACIAR : s; }

    private static String esc(String s) { return HtmlUtils.htmlEscape(s == null ? "" : s); }

    static String tipoContenido(String ruta) {
        String r = ruta.toLowerCase(java.util.Locale.ROOT);
        return r.endsWith(".pdf") ? "application/pdf" : r.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    /** Enlace secreto del colaborador: 24 bytes al azar. */
    static String nuevoToken() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Precio escrito por el colaborador en el formato de su país: "1.234,56", "1,234.56", "$ 1200"…
     * Si lleva punto y coma, el último es el decimal; si solo lleva uno repetido en grupos de tres, son miles.
     */
    static Object numero(String s) {
        try {
            if (s == null || s.isBlank()) return SupabaseService.VACIAR;
            String t = s.trim().replaceAll("[^0-9.,-]", "");
            int coma = t.lastIndexOf(','), punto = t.lastIndexOf('.');
            if (coma >= 0 && punto >= 0) {
                char dec = coma > punto ? ',' : '.';
                char mil = dec == ',' ? '.' : ',';
                t = t.replace(String.valueOf(mil), "").replace(dec, '.');
            } else if (coma >= 0) {
                t = t.matches("-?\\d{1,3}(,\\d{3})+") ? t.replace(",", "") : t.replace(',', '.');
            } else if (punto >= 0 && t.matches("-?\\d{1,3}(\\.\\d{3})+")) {
                t = t.replace(".", "");
            }
            double v = Double.parseDouble(t);
            return v < 0 || Double.isNaN(v) || Double.isInfinite(v) ? SupabaseService.VACIAR : v;
        } catch (Exception e) { return SupabaseService.VACIAR; }
    }
}
