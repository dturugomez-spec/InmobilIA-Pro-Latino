package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/wiki")
public class WikiController {

    private final SupabaseService supabase;

    @GetMapping
    public String home(HttpSession session, Model model, @RequestParam(required = false) String q) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        crearContenidoDefault(uid);

        List<Map<String, Object>> categorias = supabase.selectFilter(
                "wiki_categorias", "user_id=eq." + uid + "&order=orden.asc");

        StringBuilder filter = new StringBuilder("user_id=eq.").append(uid).append("&publicado=eq.true&order=fijado.desc,updated_at.desc&limit=50");
        if (q != null && !q.isBlank()) {
            filter.append("&or=(titulo.ilike.*").append(q).append("*,contenido_markdown.ilike.*").append(q).append("*)");
        }
        List<Map<String, Object>> articulos = supabase.selectFilter("wiki_articulos", filter.toString());

        // Top articles
        List<Map<String, Object>> topRead = supabase.selectFilter(
                "wiki_articulos", "user_id=eq." + uid + "&order=veces_leido.desc&limit=5");

        model.addAttribute("categorias", categorias);
        model.addAttribute("articulos", articulos);
        model.addAttribute("topRead", topRead);
        model.addAttribute("q", q);
        return "wiki_home";
    }

    @GetMapping("/articulo/{slug}")
    public String articulo(@PathVariable String slug, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter(
                "wiki_articulos", "user_id=eq." + uid + "&slug=eq." + slug);
        if (rows.isEmpty()) return "redirect:/wiki";
        Map<String, Object> art = rows.get(0);

        // Incrementar contador
        int leidos = ((Number) art.getOrDefault("veces_leido", 0)).intValue() + 1;
        supabase.update("wiki_articulos", String.valueOf(art.get("id")), Map.of("veces_leido", leidos));

        // Convertir markdown a HTML básico
        String html = markdownToHtml(String.valueOf(art.getOrDefault("contenido_markdown", "")));
        art.put("contenido_html_render", html);

        // Artículos relacionados (misma categoría)
        List<Map<String, Object>> relacionados = new ArrayList<>();
        if (art.get("categoria_id") != null) {
            relacionados = supabase.selectFilter("wiki_articulos",
                    "user_id=eq." + uid + "&categoria_id=eq." + art.get("categoria_id") +
                            "&id=neq." + art.get("id") + "&limit=5");
        }

        model.addAttribute("articulo", art);
        model.addAttribute("relacionados", relacionados);
        return "wiki_article";
    }

    @GetMapping("/nuevo")
    public String form(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("articulo", new LinkedHashMap<>());
        model.addAttribute("categorias", supabase.selectFilter("wiki_categorias", "user_id=eq." + uid));
        return "wiki_form";
    }

    @GetMapping("/articulo/{slug}/editar")
    public String edit(@PathVariable String slug, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("wiki_articulos", "user_id=eq." + uid + "&slug=eq." + slug);
        if (rows.isEmpty()) return "redirect:/wiki";
        model.addAttribute("articulo", rows.get(0));
        model.addAttribute("categorias", supabase.selectFilter("wiki_categorias", "user_id=eq." + uid));
        return "wiki_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("autor_id", realUid);
        data.put("titulo", form.get("titulo"));
        data.put("slug", form.getOrDefault("slug", slugify(form.get("titulo"))));
        data.put("resumen", form.get("resumen"));
        data.put("contenido_markdown", form.get("contenido_markdown"));
        if (form.get("categoria_id") != null && !form.get("categoria_id").isBlank())
            data.put("categoria_id", form.get("categoria_id"));
        data.put("publicado", "on".equals(form.getOrDefault("publicado", "on")));
        data.put("fijado", "on".equals(form.get("fijado")));
        data.put("updated_at", OffsetDateTime.now().toString());

        String id = form.get("id");
        if (id != null && !id.isBlank()) {
            supabase.update("wiki_articulos", id, data, uid); // solo los propios
        } else {
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("wiki_articulos", data);
        }
        return "redirect:/wiki/articulo/" + data.get("slug");
    }

    @PostMapping("/articulo/{id}/util")
    @ResponseBody
    public Map<String, Object> votarUtil(@PathVariable String id, @RequestParam boolean util, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectOwned("wiki_articulos", id, uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        Map<String, Object> art = rows.get(0);
        String campo = util ? "util_count" : "no_util_count";
        int v = ((Number) art.getOrDefault(campo, 0)).intValue() + 1;
        supabase.update("wiki_articulos", id, Map.of(campo, v), uid);
        return Map.of("ok", true);
    }

    @PostMapping("/categoria/crear")
    public String crearCategoria(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("descripcion", form.get("descripcion"));
        data.put("icono", form.getOrDefault("icono", "📁"));
        data.put("color", form.getOrDefault("color", "#3b82f6"));
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("wiki_categorias", data);
        return "redirect:/wiki";
    }

    private void crearContenidoDefault(String uid) {
        List<Map<String, Object>> exist = supabase.selectFilter("wiki_categorias", "user_id=eq." + uid);
        if (!exist.isEmpty()) return;

        // 4 categorías iniciales
        String[][] cats = {
                {"Procesos comerciales", "📋", "#3b82f6"},
                {"Marco legal", "⚖️", "#ef4444"},
                {"Plantillas y modelos", "📄", "#10b981"},
                {"FAQs clientes", "💬", "#f59e0b"}
        };
        for (String[] c : cats) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id", uid);
            data.put("nombre", c[0]);
            data.put("icono", c[1]);
            data.put("color", c[2]);
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("wiki_categorias", data);
        }
    }

    private static final java.util.regex.Pattern ENLACE_MD = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");

    /**
     * Conversor markdown → HTML muy simple. Escapa primero el texto: antes un artículo con
     * &lt;script&gt; o con un enlace javascript: ejecutaba código a quien lo abriera (otro
     * compañero, el administrador...). Los enlaces solo pueden ser web, email o rutas de la app.
     */
    static String markdownToHtml(String md) {
        if (md == null) return "";
        String escapado = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
        java.util.regex.Matcher m = ENLACE_MD.matcher(escapado);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String destino = m.group(2);
            String enlace = destino.matches("(?i)(https?://|mailto:|/(?!/)).*")
                    ? "<a href=\"" + destino + "\" class=\"text-blue-600 underline\" rel=\"noopener noreferrer\">" + m.group(1) + "</a>"
                    : m.group(1);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(enlace));
        }
        m.appendTail(sb);
        String html = sb.toString()
                .replaceAll("(?m)^### (.+)$", "<h3>$1</h3>")
                .replaceAll("(?m)^## (.+)$", "<h2>$1</h2>")
                .replaceAll("(?m)^# (.+)$", "<h1>$1</h1>")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>")
                .replaceAll("(?<!\\w)\\*([^*]+)\\*(?!\\w)", "<em>$1</em>")
                .replaceAll("`([^`]+)`", "<code>$1</code>")
                .replaceAll("(?m)^- (.+)$", "<li>$1</li>")
                .replaceAll("(?s)(<li>.+?</li>(\\n<li>.+?</li>)*)", "<ul>$1</ul>")
                .replaceAll("\n\n", "</p><p>");
        return "<p>" + html + "</p>";
    }

    private String slugify(String s) {
        if (s == null) return "art-" + System.currentTimeMillis();
        return s.toLowerCase()
                .replaceAll("[áàä]", "a").replaceAll("[éèë]", "e").replaceAll("[íìï]", "i")
                .replaceAll("[óòö]", "o").replaceAll("[úùü]", "u").replaceAll("ñ", "n")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
