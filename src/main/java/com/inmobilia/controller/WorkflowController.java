package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.WorkflowEngine;
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
@RequestMapping("/workflows")
@Slf4j
public class WorkflowController {

    private final SupabaseService supabase;
    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> workflows = supabase.selectFilter(
                "workflows", "user_id=eq." + uid + "&order=created_at.desc");

        long activos = workflows.stream().filter(w -> Boolean.TRUE.equals(w.get("activo"))).count();
        int totalEjecuciones = workflows.stream().mapToInt(w -> num(w.get("veces_ejecutado"))).sum();
        int totalExitos = workflows.stream().mapToInt(w -> num(w.get("veces_exito"))).sum();
        double tasaExito = totalEjecuciones > 0 ? (totalExitos * 100.0 / totalEjecuciones) : 0;

        // Últimas ejecuciones
        List<Map<String, Object>> ejecuciones = supabase.selectFilter(
                "workflow_ejecuciones", "user_id=eq." + uid + "&order=iniciado_at.desc&limit=20");

        model.addAttribute("workflows", workflows);
        model.addAttribute("activos", activos);
        model.addAttribute("totalEjecuciones", totalEjecuciones);
        model.addAttribute("tasaExito", tasaExito);
        model.addAttribute("ejecuciones", ejecuciones);
        return "workflows_list";
    }

    @GetMapping("/nuevo")
    public String form(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("plantillas", getPlantillasPredefinidas());
        return "workflows_form";
    }

    @GetMapping("/{id}/editar")
    public String edit(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("workflows", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/workflows";
        model.addAttribute("workflow", rows.get(0));
        model.addAttribute("plantillas", getPlantillasPredefinidas());
        return "workflows_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", body.get("nombre"));
        data.put("descripcion", body.get("descripcion"));
        data.put("trigger_evento", body.get("trigger_evento"));
        data.put("trigger_condiciones", body.get("trigger_condiciones"));
        data.put("acciones", body.get("acciones"));
        data.put("activo", body.getOrDefault("activo", true));
        data.put("delay_minutos", body.getOrDefault("delay_minutos", 0));
        data.put("solo_horario_laboral", body.getOrDefault("solo_horario_laboral", false));
        data.put("updated_at", OffsetDateTime.now().toString());

        Object id = body.get("id");
        if (id != null && !"".equals(id)) {
            supabase.update("workflows", String.valueOf(id), data, uid);
        } else {
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("workflows", data);
        }
        return "redirect:/workflows";
    }

    @PostMapping("/{id}/toggle")
    @ResponseBody
    public Map<String, Object> toggle(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectFilter("workflows", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        boolean nuevo = !Boolean.TRUE.equals(rows.get(0).get("activo"));
        supabase.update("workflows", id, Map.of("activo", nuevo), uid);
        return Map.of("ok", true, "activo", nuevo);
    }

    @PostMapping("/{id}/test")
    @ResponseBody
    public Map<String, Object> test(@PathVariable String id,
                                     @RequestBody Map<String, Object> payload,
                                     HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> rows = supabase.selectFilter("workflows", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return Map.of("ok", false);
        engine.ejecutarWorkflow(rows.get(0), payload, uid);
        return Map.of("ok", true, "mensaje", "Workflow ejecutado en background");
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.delete("workflows", id, uid);
        return "redirect:/workflows";
    }

    /** Plantillas predefinidas de workflows comunes. */
    private List<Map<String, Object>> getPlantillasPredefinidas() {
        List<Map<String, Object>> list = new ArrayList<>();

        list.add(plantilla(
                "🎯 Auto-asignar nuevo lead",
                "Cuando entra un lead, se asigna al agente con menos carga + email de bienvenida",
                "lead_creado",
                List.of(
                        accion("assign_agent", Map.of("tabla", "leads", "agente_id", "auto")),
                        accion("send_email", Map.of(
                                "to", "{email}",
                                "subject", "👋 Hola {nombre}, gracias por contactarnos",
                                "body", "<p>Hola {nombre},</p><p>Hemos recibido tu solicitud. Un agente te contactará en menos de 24h.</p>"
                        )),
                        accion("create_task", Map.of(
                                "titulo", "Llamar a {nombre}",
                                "descripcion", "Lead nuevo - contactar en 24h",
                                "prioridad", "alta",
                                "dias_limite", 1
                        ))
                )
        ));

        list.add(plantilla(
                "💰 Lead premium → director",
                ("Si el presupuesto > 500.000 " + PaisActual.moneda() + ", asignar a director y notificación urgente"),
                "lead_creado",
                Map.of("campo", "presupuesto_max", "operador", ">", "valor", 500000),
                List.of(
                        accion("send_notification", Map.of(
                                "titulo", "💎 Lead premium",
                                "mensaje", ("{nombre} con presupuesto {presupuesto_max} " + PaisActual.moneda()),
                                "urgencia", "alta",
                                "link", "/leads"
                        )),
                        accion("create_task", Map.of(
                                "titulo", "🚨 LEAD VIP {nombre} - contactar HOY",
                                "prioridad", "urgente",
                                "dias_limite", 0
                        ))
                )
        ));

        list.add(plantilla(
                "📅 Recordatorio post-visita",
                "2 días después de una visita realizada, recordatorio para follow-up",
                "visita_realizada",
                List.of(
                        Map.of("tipo", "wait_minutes", "config", Map.of("minutos", 2880)),
                        accion("create_task", Map.of(
                                "titulo", "Follow-up visita de {visitante_nombre}",
                                "prioridad", "media",
                                "dias_limite", 1
                        )),
                        accion("send_email", Map.of(
                                "to", "{visitante_email}",
                                "subject", "¿Tienes alguna duda sobre la propiedad?",
                                "body", "<p>Hola {visitante_nombre},</p><p>Ha pasado un par de días desde tu visita. ¿Te ha surgido alguna duda?</p>"
                        ))
                )
        ));

        list.add(plantilla(
                "⚠️ Factura impagada → recordatorio",
                "Cuando una factura supera 7 días sin pagar, email + tarea",
                "factura_impagada",
                List.of(
                        accion("send_email", Map.of(
                                "to", "{cliente_email}",
                                "subject", "Recordatorio de pago - Factura {numero}",
                                "body", ("<p>Hola {cliente_nombre},</p><p>Te recordamos que tienes pendiente la factura {numero} por importe de {importe} " + PaisActual.moneda() + ".</p>")
                        )),
                        accion("change_status", Map.of("tabla", "facturas", "estado", "morosa")),
                        accion("create_task", Map.of(
                                "titulo", "Llamar a cliente factura {numero}",
                                "prioridad", "alta"
                        ))
                )
        ));

        list.add(plantilla(
                "🏠 Nueva propiedad → matching",
                "Cuando creas una propiedad, busca leads que encajen",
                "propiedad_creada",
                List.of(
                        accion("send_notification", Map.of(
                                "titulo", "🆕 Propiedad creada",
                                "mensaje", "{direccion} lista. Ejecutando matching automático...",
                                "urgencia", "media",
                                "link", "/matches"
                        ))
                )
        ));

        return list;
    }

    private Map<String, Object> plantilla(String nombre, String desc, String trigger, List<Map<String, Object>> acciones) {
        return plantilla(nombre, desc, trigger, null, acciones);
    }

    private Map<String, Object> plantilla(String nombre, String desc, String trigger, Map<String, Object> cond, List<Map<String, Object>> acciones) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("nombre", nombre);
        p.put("descripcion", desc);
        p.put("trigger_evento", trigger);
        if (cond != null) p.put("trigger_condiciones", cond);
        p.put("acciones", acciones);
        return p;
    }

    private Map<String, Object> accion(String tipo, Map<String, Object> config) {
        return Map.of("tipo", tipo, "config", config);
    }

    private static int num(Object o) { try { return o == null ? 0 : Integer.parseInt(String.valueOf(o).split("\\.")[0]); } catch(Exception e) { return 0; } }
}
