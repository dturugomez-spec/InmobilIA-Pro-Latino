package com.inmobilia.controller;

import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat interno avanzado tipo Slack: canales (públicos/privados/DMs), menciones,
 * reacciones, hilos, polling para tiempo real, contadores de no leídos.
 *
 * Reemplaza al TeamChatController básico con funcionalidades pro.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/equipo-chat")
@Slf4j
public class TeamChatV2Controller {

    private final SupabaseService supabase;
    private final NotificationService notif;

    private static final Pattern MENTION_PATTERN = Pattern.compile("@([a-zA-Z0-9_.-]+)");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w.-]+(?:/[\\w./?=&%-]*)?)");

    @GetMapping
    public String chat(HttpSession session, Model model,
                       @RequestParam(required = false) String canal) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return "redirect:/login";

        crearCanalesDefault(uid, realUid);

        List<Map<String, Object>> canales = supabase.selectFilter(
                "chat_canales",
                "org_id=eq." + uid + "&archivado=eq.false&order=fijado.desc,ultimo_mensaje_at.desc");

        // Filtrar privados
        canales = canales.stream().filter(c -> {
            String tipo = String.valueOf(c.get("tipo"));
            if ("publico".equals(tipo)) return true;
            Object m = c.get("miembros_ids");
            if (m instanceof List) return ((List<?>) m).contains(realUid);
            return false;
        }).toList();

        Map<String, Integer> noLeidos = new HashMap<>();
        for (Map<String, Object> c : canales) {
            int n = contarNoLeidos(String.valueOf(c.get("id")), realUid);
            noLeidos.put(String.valueOf(c.get("id")), n);
        }

        Map<String, Object> canalActivo = null;
        List<Map<String, Object>> mensajes = new ArrayList<>();
        if (canal != null && !canal.isBlank()) {
            for (Map<String, Object> c : canales) {
                if (canal.equals(String.valueOf(c.get("id")))) { canalActivo = c; break; }
            }
        }
        if (canalActivo == null && !canales.isEmpty()) canalActivo = canales.get(0);

        if (canalActivo != null) {
            mensajes = supabase.selectFilter(
                    "chat_mensajes",
                    "canal_id=eq." + canalActivo.get("id") +
                            "&eliminado=eq.false&hilo_padre_id=is.null" +
                            "&order=created_at.asc&limit=200");

            for (Map<String, Object> m : mensajes) {
                List<Map<String, Object>> autor = supabase.selectFilter("users", "id=eq." + m.get("autor_id"));
                if (!autor.isEmpty()) {
                    m.put("autor_nombre", autor.get(0).get("name"));
                    m.put("autor_avatar", autor.get(0).get("avatar_url"));
                }
            }
            if (!mensajes.isEmpty()) {
                Object lastId = mensajes.get(mensajes.size() - 1).get("id");
                marcarLeido(String.valueOf(canalActivo.get("id")), realUid, lastId);
            }
        }

        List<Map<String, Object>> miembros = supabase.selectFilter(
                "users", "org_id=eq." + uid + "&active=eq.true");

        model.addAttribute("canales", canales);
        model.addAttribute("canalActivo", canalActivo);
        model.addAttribute("mensajes", mensajes);
        model.addAttribute("miembros", miembros);
        model.addAttribute("noLeidos", noLeidos);
        model.addAttribute("realUid", realUid);
        return "team_chat_v2";
    }

    @PostMapping("/mensaje")
    @ResponseBody
    public Map<String, Object> enviarMensaje(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return Map.of("ok", false);

        String canalId = String.valueOf(body.get("canal_id"));
        String contenido = String.valueOf(body.get("contenido"));
        if (contenido == null || contenido.isBlank()) return Map.of("ok", false);

        List<String> menciones = new ArrayList<>();
        Matcher mm = MENTION_PATTERN.matcher(contenido);
        while (mm.find()) {
            String nombre = mm.group(1);
            List<Map<String, Object>> u = supabase.selectFilter(
                    "users", "org_id=eq." + uid + "&name=ilike.*" + nombre + "*&limit=1");
            if (!u.isEmpty()) menciones.add(String.valueOf(u.get(0).get("id")));
        }

        String html = procesarHtml(contenido);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("canal_id", canalId);
        msg.put("org_id", uid);
        msg.put("autor_id", realUid);
        msg.put("contenido", contenido);
        msg.put("contenido_html", html);
        msg.put("tipo", body.getOrDefault("tipo", "texto"));
        if (!menciones.isEmpty()) msg.put("menciones_ids", menciones);
        if (body.get("hilo_padre_id") != null) msg.put("hilo_padre_id", body.get("hilo_padre_id"));
        if (body.get("propiedad_id") != null) msg.put("propiedad_id", body.get("propiedad_id"));
        if (body.get("lead_id") != null) msg.put("lead_id", body.get("lead_id"));
        msg.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("chat_mensajes", msg);

        List<Map<String, Object>> canales = supabase.selectFilter("chat_canales", "id=eq." + canalId);
        if (!canales.isEmpty()) {
            int total = ((Number) canales.get(0).getOrDefault("mensajes_count", 0)).intValue() + 1;
            supabase.update("chat_canales", canalId, Map.of(
                    "mensajes_count", total,
                    "ultimo_mensaje_at", OffsetDateTime.now().toString()
            ));
        }

        String autorNombre = SessionUtils.name(session);
        for (String mid : menciones) {
            if (!mid.equals(realUid)) {
                notif.create(mid, "💬 @" + autorNombre,
                        "Te mencionó: " + (contenido.length() > 80 ? contenido.substring(0, 80) + "..." : contenido),
                        "/equipo-chat?canal=" + canalId, "alta");
            }
        }

        return Map.of("ok", true, "id", saved.get("id"));
    }

    @GetMapping("/api/{canalId}/nuevos")
    @ResponseBody
    public List<Map<String, Object>> nuevos(@PathVariable String canalId,
                                            @RequestParam(defaultValue = "0") long desde,
                                            HttpSession session) {
        String realUid = SessionUtils.realUid(session);
        if (realUid == null) return Collections.emptyList();

        List<Map<String, Object>> mensajes = supabase.selectFilter(
                "chat_mensajes",
                "canal_id=eq." + canalId + "&id=gt." + desde +
                        "&eliminado=eq.false&hilo_padre_id=is.null" +
                        "&order=created_at.asc&limit=50");

        for (Map<String, Object> m : mensajes) {
            List<Map<String, Object>> autor = supabase.selectFilter("users", "id=eq." + m.get("autor_id"));
            if (!autor.isEmpty()) m.put("autor_nombre", autor.get(0).get("name"));
        }

        if (!mensajes.isEmpty()) {
            Object lastId = mensajes.get(mensajes.size() - 1).get("id");
            marcarLeido(canalId, realUid, lastId);
        }

        return mensajes;
    }

    @PostMapping("/canal/crear")
    public String crearCanal(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("org_id", uid);
        data.put("creador_id", realUid);
        data.put("nombre", form.get("nombre"));
        data.put("descripcion", form.get("descripcion"));
        data.put("tipo", form.getOrDefault("tipo", "publico"));
        data.put("icono", "privado".equals(form.get("tipo")) ? "🔒" : "#");
        if ("privado".equals(form.get("tipo"))) {
            data.put("miembros_ids", List.of(realUid));
        }
        data.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("chat_canales", data);
        return "redirect:/equipo-chat?canal=" + saved.get("id");
    }

    @PostMapping("/mensaje/{id}/reaccionar")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public Map<String, Object> reaccionar(@PathVariable String id,
                                          @RequestBody Map<String, String> body,
                                          HttpSession session) {
        String realUid = SessionUtils.realUid(session);
        String orgId = SessionUtils.uid(session);
        if (realUid == null || orgId == null) return Map.of("ok", false);
        String emoji = body.get("emoji");

        // Solo mensajes del chat de esta inmobiliaria (la tabla va por org_id)
        List<Map<String, Object>> rows = supabase.selectFilter("chat_mensajes", "id=eq." + id + "&org_id=eq." + orgId);
        if (rows.isEmpty()) return Map.of("ok", false);
        Map<String, Object> msg = rows.get(0);

        Map<String, Object> reacciones = msg.get("reacciones") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) msg.get("reacciones"))
                : new LinkedHashMap<>();

        List<String> usuarios = reacciones.get(emoji) instanceof List
                ? new ArrayList<>((List<String>) reacciones.get(emoji))
                : new ArrayList<>();
        if (usuarios.contains(realUid)) usuarios.remove(realUid);
        else usuarios.add(realUid);
        if (usuarios.isEmpty()) reacciones.remove(emoji);
        else reacciones.put(emoji, usuarios);

        supabase.update("chat_mensajes", id, Map.of("reacciones", reacciones));
        return Map.of("ok", true, "reacciones", reacciones);
    }

    @PostMapping("/mensaje/{id}/eliminar")
    @ResponseBody
    public Map<String, Object> eliminar(@PathVariable String id, HttpSession session) {
        String realUid = SessionUtils.realUid(session);
        if (realUid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectFilter("chat_mensajes", "id=eq." + id);
        if (rows.isEmpty()) return Map.of("ok", false);
        if (!realUid.equals(String.valueOf(rows.get(0).get("autor_id")))) {
            return Map.of("ok", false, "error", "Solo puedes eliminar tus mensajes");
        }
        supabase.update("chat_mensajes", id, Map.of("eliminado", true));
        return Map.of("ok", true);
    }

    private int contarNoLeidos(String canalId, String userId) {
        try {
            List<Map<String, Object>> lec = supabase.selectFilter(
                    "chat_lecturas",
                    "user_id=eq." + userId + "&canal_id=eq." + canalId);
            long ultimoLeido = lec.isEmpty() ? 0 : ((Number) lec.get(0).getOrDefault("ultimo_mensaje_id", 0)).longValue();
            List<Map<String, Object>> nuevos = supabase.selectFilter(
                    "chat_mensajes",
                    "canal_id=eq." + canalId + "&id=gt." + ultimoLeido +
                            "&autor_id=neq." + userId + "&eliminado=eq.false");
            return nuevos.size();
        } catch (Exception e) { return 0; }
    }

    private void marcarLeido(String canalId, String userId, Object lastId) {
        try {
            List<Map<String, Object>> existe = supabase.selectFilter(
                    "chat_lecturas",
                    "user_id=eq." + userId + "&canal_id=eq." + canalId);
            if (existe.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user_id", userId);
                data.put("canal_id", canalId);
                data.put("ultimo_mensaje_id", lastId);
                data.put("leido_at", OffsetDateTime.now().toString());
                supabase.insert("chat_lecturas", data);
            } else {
                // update por clave compuesta -- simplificado: borrar existentes y crear nuevo
                var existing = supabase.selectFilter("chat_lecturas",
                        "user_id=eq." + userId + "&canal_id=eq." + canalId);
                for (var ex : existing) {
                    supabase.delete("chat_lecturas", String.valueOf(ex.get("id")));
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user_id", userId);
                data.put("canal_id", canalId);
                data.put("ultimo_mensaje_id", lastId);
                data.put("leido_at", OffsetDateTime.now().toString());
                supabase.insert("chat_lecturas", data);
            }
        } catch (Exception ignored) {}
    }

    private String procesarHtml(String texto) {
        if (texto == null) return "";
        String html = texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        html = URL_PATTERN.matcher(html).replaceAll("<a href=\"$1\" target=\"_blank\" class=\"text-blue-600 underline\">$1</a>");
        html = MENTION_PATTERN.matcher(html).replaceAll("<span class=\"text-blue-700 font-semibold bg-blue-50 px-1 rounded\">@$1</span>");
        html = html.replace("\n", "<br>");
        html = html.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("`([^`]+)`", "<code class=\"bg-slate-100 px-1 rounded text-sm\">$1</code>");
        return html;
    }

    private void crearCanalesDefault(String orgId, String creadorId) {
        List<Map<String, Object>> exist = supabase.selectFilter("chat_canales", "org_id=eq." + orgId);
        if (!exist.isEmpty()) return;

        String[][] canales = {
                {"general", "👋 Canal general del equipo", "#"},
                {"ventas", "💰 Cierres y oportunidades comerciales", "💰"},
                {"captacion", "📋 Nuevas propiedades y propietarios", "🏠"},
                {"random", "☕ Conversación libre, memes, café...", "🎲"}
        };

        for (String[] c : canales) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("org_id", orgId);
            data.put("creador_id", creadorId);
            data.put("nombre", c[0]);
            data.put("descripcion", c[1]);
            data.put("icono", c[2]);
            data.put("tipo", "publico");
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("chat_canales", data);
        }
    }
}
