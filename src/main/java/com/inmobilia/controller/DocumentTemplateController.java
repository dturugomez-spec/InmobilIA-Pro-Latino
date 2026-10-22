package com.inmobilia.controller;

import com.inmobilia.service.DocumentTemplateService;
import com.inmobilia.service.SignatureService;
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

@Controller
@RequiredArgsConstructor
@RequestMapping("/plantillas-doc")
@Slf4j
public class DocumentTemplateController {

    private final SupabaseService supabase;
    private final DocumentTemplateService templateService;

    @GetMapping
    public String list(HttpSession session, Model model,
                       @RequestParam(required = false) String categoria) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Crear plantillas del sistema si no las tiene
        templateService.crearPlantillasDelSistema(uid);

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=favorita.desc,veces_usada.desc");
        if (categoria != null && !categoria.isBlank()) q.append("&categoria=eq.").append(categoria);
        List<Map<String, Object>> plantillas = supabase.selectFilter("plantillas_documento", q.toString());

        // Agrupar por categoría
        Map<String, List<Map<String, Object>>> porCat = new LinkedHashMap<>();
        for (Map<String, Object> p : plantillas) {
            String cat = String.valueOf(p.get("categoria"));
            porCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(p);
        }

        model.addAttribute("plantillas", plantillas);
        model.addAttribute("porCategoria", porCat);
        return "doc_templates_list";
    }

    @GetMapping("/nueva")
    public String form(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("plantilla", new LinkedHashMap<>());
        return "doc_templates_form";
    }

    @GetMapping("/{id}/editar")
    public String edit(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter(
                "plantillas_documento", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/plantillas-doc";
        model.addAttribute("plantilla", rows.get(0));
        return "doc_templates_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("descripcion", form.get("descripcion"));
        data.put("categoria", form.getOrDefault("categoria", "otros"));
        data.put("icono", form.getOrDefault("icono", "📄"));
        data.put("contenido_html", form.get("contenido_html"));
        data.put("variables_detectadas", templateService.detectarVariables(form.get("contenido_html")));
        data.put("favorita", "on".equals(form.get("favorita")));
        data.put("notas_uso", form.get("notas_uso"));
        data.put("updated_at", OffsetDateTime.now().toString());

        String id = form.get("id");
        if (id != null && !id.isBlank()) {
            supabase.update("plantillas_documento", id, data, uid); // solo las propias
        } else {
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("plantillas_documento", data);
        }
        SessionUtils.flash(session, "✅ Plantilla guardada");
        return "redirect:/plantillas-doc";
    }

    @PostMapping("/{id}/favorita")
    @ResponseBody
    public Map<String, Object> toggleFav(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectOwned("plantillas_documento", id, uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        boolean fav = !Boolean.TRUE.equals(rows.get(0).get("favorita"));
        supabase.update("plantillas_documento", id, Map.of("favorita", fav), uid);
        return Map.of("ok", true, "favorita", fav);
    }

    /** Página para generar documento desde plantilla. */
    @GetMapping("/{id}/generar")
    public String generateForm(@PathVariable String id, HttpSession session, Model model,
                                @RequestParam(required = false) String leadId,
                                @RequestParam(required = false) String propiedadId,
                                @RequestParam(required = false) String contratoId) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter(
                "plantillas_documento", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/plantillas-doc";

        model.addAttribute("plantilla", rows.get(0));
        model.addAttribute("leads", supabase.selectFilter("leads", "user_id=eq." + uid));
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        model.addAttribute("contratos", supabase.selectFilter("contratos", "user_id=eq." + uid));
        model.addAttribute("inquilinos", supabase.selectFilter("inquilinos", "user_id=eq." + uid));
        model.addAttribute("propietarios", supabase.selectFilter("propietarios", "user_id=eq." + uid));
        model.addAttribute("preseleccion", Map.of(
                "lead_id", leadId != null ? leadId : "",
                "propiedad_id", propiedadId != null ? propiedadId : "",
                "contrato_id", contratoId != null ? contratoId : ""
        ));
        return "doc_templates_generate";
    }

    /** Genera documento rellenando variables (preview). */
    @PostMapping("/{id}/preview")
    @ResponseBody
    public Map<String, Object> preview(@PathVariable String id,
                                       @RequestBody Map<String, String> ids,
                                       HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        List<Map<String, Object>> rows = supabase.selectOwned("plantillas_documento", id, uid);
        if (rows.isEmpty()) return Map.of("ok", false);

        String plantillaHtml = String.valueOf(rows.get(0).get("contenido_html"));
        Map<String, Map<String, Object>> ctx = templateService.cargarContexto(uid, ids);
        String resultado = com.inmobilia.util.HtmlSeguro.limpiar(templateService.rellenar(plantillaHtml, ctx));

        return Map.of("ok", true, "html", resultado, "contexto", ctx);
    }

    /** Genera y guarda el documento. */
    @PostMapping("/{id}/generar")
    public String generate(@PathVariable String id,
                           @RequestParam Map<String, String> form,
                           HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectOwned("plantillas_documento", id, uid);
        if (rows.isEmpty()) return "redirect:/plantillas-doc";

        Map<String, Object> plantilla = rows.get(0);
        Map<String, Map<String, Object>> ctx = templateService.cargarContexto(uid, form);
        String html = com.inmobilia.util.HtmlSeguro.limpiar(
                templateService.rellenar(String.valueOf(plantilla.get("contenido_html")), ctx));

        // Guardar documento generado
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("user_id", uid);
        doc.put("plantilla_id", id);
        doc.put("titulo", form.getOrDefault("titulo", String.valueOf(plantilla.get("nombre"))));
        doc.put("contenido_html", html);
        if (notBlank(form.get("lead_id"))) doc.put("lead_id", form.get("lead_id"));
        if (notBlank(form.get("propiedad_id"))) doc.put("propiedad_id", form.get("propiedad_id"));
        if (notBlank(form.get("contrato_id"))) doc.put("contrato_id", form.get("contrato_id"));
        if (notBlank(form.get("inquilino_id"))) doc.put("inquilino_id", form.get("inquilino_id"));
        if (notBlank(form.get("propietario_id"))) doc.put("propietario_id", form.get("propietario_id"));
        doc.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("documentos_generados", doc);

        // Incrementar contador
        int v = num(plantilla.get("veces_usada")) + 1;
        supabase.update("plantillas_documento", id, Map.of("veces_usada", v), uid);

        // Si quiere enviar a firma directamente
        if ("on".equals(form.get("enviar_firma"))) {
            return "redirect:/firmas/nueva?plantillaDocId=" + saved.get("id");
        }

        SessionUtils.flash(session, "✅ Documento generado");
        return "redirect:/plantillas-doc/historial";
    }

    @GetMapping("/historial")
    public String history(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> docs = supabase.selectFilter(
                "documentos_generados", "user_id=eq." + uid + "&order=created_at.desc&limit=100");
        model.addAttribute("documentos", docs);
        return "doc_templates_history";
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static int num(Object o) { try { return o == null ? 0 : Integer.parseInt(String.valueOf(o).split("\\.")[0]); } catch(Exception e) { return 0; } }
}
