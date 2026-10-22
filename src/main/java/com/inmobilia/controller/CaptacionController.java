package com.inmobilia.controller;

import com.inmobilia.service.CaptacionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🎯 Captación proactiva / Farming por zona.
 *  - /captacion: campañas + KPIs
 *  - /captacion/{id}: mensajes generados por IA + destinatarios (mini-pipeline)
 */
@Controller
@RequiredArgsConstructor
public class CaptacionController {

    private final SupabaseService supabase;
    private final CaptacionService captacion;
    private final com.inmobilia.service.WorkflowEngine workflows;

    @GetMapping("/captacion")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> campanas = supabase.selectFilter(
                "captacion_campanas", "user_id=eq." + uid + "&order=created_at.desc&limit=100");
        List<Map<String, Object>> dest = supabase.selectFilter(
                "captacion_destinatarios", "user_id=eq." + uid + "&limit=1000");

        long contactados = dest.stream().filter(d -> !"pendiente".equals(d.get("estado"))).count();
        long captados = dest.stream().filter(d -> "captado".equals(d.get("estado"))).count();

        model.addAttribute("campanas", campanas);
        model.addAttribute("angulos", CaptacionService.ANGULOS);
        model.addAttribute("totalDest", dest.size());
        model.addAttribute("contactados", contactados);
        model.addAttribute("captados", captados);
        model.addAttribute("enlaceValoracion", captacion.enlaceValoracion(uid));
        return "captacion";
    }

    @PostMapping("/captacion/nueva")
    public String nueva(@RequestParam String nombre,
                        @RequestParam(required = false) String zona,
                        @RequestParam(required = false) String tipo_objetivo,
                        @RequestParam(required = false) String angulo,
                        @RequestParam(required = false) String extra,
                        HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, String> msgs = captacion.generarMensajes(uid, zona, tipo_objetivo, angulo, extra);

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("user_id", uid);
        c.put("nombre", nombre);
        c.put("zona", zona);
        c.put("tipo_objetivo", tipo_objetivo);
        c.put("angulo", angulo);
        c.put("mensaje_carta", msgs.get("carta"));
        c.put("mensaje_whatsapp", msgs.get("whatsapp"));
        c.put("mensaje_email", msgs.get("email"));
        c.put("estado", "activa");
        c.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("captacion_campanas", c);

        Object id = saved != null ? saved.get("id") : null;
        return id != null ? "redirect:/captacion/" + id : "redirect:/captacion";
    }

    @GetMapping("/captacion/{id}")
    public String detalle(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter("captacion_campanas", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/captacion";

        List<Map<String, Object>> dest = supabase.selectFilter(
                "captacion_destinatarios", "campana_id=eq." + id + "&order=created_at.desc&limit=500");

        model.addAttribute("c", rows.get(0));
        model.addAttribute("destinatarios", dest);
        model.addAttribute("angulos", CaptacionService.ANGULOS);
        return "captacion_detail";
    }

    @PostMapping("/captacion/{id}/regenerar")
    public String regenerar(@PathVariable String id,
                            @RequestParam(required = false) String angulo,
                            @RequestParam(required = false) String extra,
                            HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("captacion_campanas", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/captacion";
        Map<String, Object> c = rows.get(0);

        String ang = (angulo == null || angulo.isBlank()) ? String.valueOf(c.get("angulo")) : angulo;
        Map<String, String> msgs = captacion.generarMensajes(uid,
                String.valueOf(c.get("zona")), String.valueOf(c.get("tipo_objetivo")), ang, extra);

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("angulo", ang);
        upd.put("mensaje_carta", msgs.get("carta"));
        upd.put("mensaje_whatsapp", msgs.get("whatsapp"));
        upd.put("mensaje_email", msgs.get("email"));
        supabase.update("captacion_campanas", id, upd, uid);
        return "redirect:/captacion/" + id;
    }

    @PostMapping("/captacion/{id}/destinatario")
    public String addDestinatario(@PathVariable String id,
                                  @RequestParam(required = false) String nombre,
                                  @RequestParam(required = false) String direccion,
                                  @RequestParam(required = false) String telefono,
                                  @RequestParam(required = false) String email,
                                  HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("captacion_campanas", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/captacion";

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("user_id", uid);
        d.put("campana_id", id);
        d.put("nombre", nombre);
        d.put("direccion", direccion);
        d.put("telefono", telefono);
        d.put("email", email != null ? email.trim().toLowerCase() : null);
        d.put("estado", "pendiente");
        d.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("captacion_destinatarios", d);
        return "redirect:/captacion/" + id;
    }

    @PostMapping("/captacion/destinatario/{did}/estado")
    public String estadoDestinatario(@PathVariable String did,
                                     @RequestParam String estado,
                                     @RequestParam String campanaId,
                                     HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("captacion_destinatarios", "id=eq." + did);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/captacion";
        Map<String, Object> d = rows.get(0);

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("estado", estado);
        if (!"pendiente".equals(estado) && d.get("contactado_at") == null) {
            upd.put("contactado_at", OffsetDateTime.now().toString());
        }

        // Al marcar "captado" se crea el lead vendedor (atribuido a captación)
        if ("captado".equals(estado) && d.get("lead_id") == null) {
            Map<String, Object> lead = new LinkedHashMap<>();
            lead.put("user_id", uid);
            lead.put("nombre", d.get("nombre"));
            lead.put("email", d.get("email"));
            lead.put("telefono", d.get("telefono"));
            lead.put("interes", "Venta");
            lead.put("origen", "captacion");
            lead.put("etapa", "Nuevo");
            lead.put("zona", d.get("direccion"));
            lead.put("notas", "Captado por farming. Dirección: " + String.valueOf(d.getOrDefault("direccion", "")));
            lead.put("created_at", OffsetDateTime.now().toString());
            try {
                Map<String, Object> savedLead = supabase.insert("leads", lead);
                if (savedLead != null && savedLead.get("id") != null) {
                    upd.put("lead_id", savedLead.get("id"));
                    workflows.triggerEvent(uid, "lead_creado", savedLead);
                }
            } catch (Exception ignored) {}
        }
        supabase.update("captacion_destinatarios", did, upd, uid);
        return "redirect:/captacion/" + campanaId;
    }

    @PostMapping("/captacion/{id}/eliminar")
    public String eliminar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("captacion_campanas", "id=eq." + id);
        if (!rows.isEmpty() && uid.equals(String.valueOf(rows.get(0).get("user_id")))) {
            supabase.delete("captacion_campanas", id, uid);
        }
        return "redirect:/captacion";
    }
}
