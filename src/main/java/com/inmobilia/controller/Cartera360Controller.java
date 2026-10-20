package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Vista 360° de un cliente: agrega toda su información (leads, visitas, ofertas,
 * contratos, facturas, emails, SMS, WhatsApp, NPS) en una sola timeline.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/cartera")
public class Cartera360Controller {

    private final SupabaseService supabase;

    @GetMapping
    public String buscar(HttpSession session, Model model, @RequestParam(required = false) String q) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        if (q != null && !q.isBlank()) {
            // Buscar en leads, inquilinos y propietarios
            String filter = "user_id=eq." + uid + "&or=(nombre.ilike.*" + q + "*,email.ilike.*" + q + "*,telefono.ilike.*" + q + "*)";
            List<Map<String, Object>> leads = supabase.selectFilter("leads", filter);
            List<Map<String, Object>> inquilinos = supabase.selectFilter("inquilinos", filter);
            List<Map<String, Object>> propietarios = supabase.selectFilter("propietarios", filter);
            model.addAttribute("leads", leads);
            model.addAttribute("inquilinos", inquilinos);
            model.addAttribute("propietarios", propietarios);
        }
        model.addAttribute("q", q);
        return "cartera_buscar";
    }

    @GetMapping("/cliente/{tipo}/{id}")
    public String view360(@PathVariable String tipo, @PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Cargar el cliente base
        String tabla = switch (tipo) {
            case "lead" -> "leads";
            case "inquilino" -> "inquilinos";
            case "propietario" -> "propietarios";
            default -> "leads";
        };
        List<Map<String, Object>> rows = supabase.selectFilter(tabla, "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/cartera";
        Map<String, Object> cliente = rows.get(0);
        String email = String.valueOf(cliente.get("email"));
        String telefono = String.valueOf(cliente.get("telefono"));

        // Recopilar TODA la actividad cruzando por email/teléfono
        List<Map<String, Object>> timeline = new ArrayList<>();

        // Interacciones leads
        if ("lead".equals(tipo)) {
            for (Map<String, Object> i : supabase.selectFilter("lead_interacciones", "lead_id=eq." + id + "&order=created_at.desc")) {
                timeline.add(evento("interaccion", i.get("tipo"), String.valueOf(i.get("detalle")), String.valueOf(i.get("created_at"))));
            }
        }

        // Visitas (por nombre o email)
        for (Map<String, Object> v : supabase.selectFilter("visitas",
                "user_id=eq." + uid + "&or=(visitante_email.eq." + email + ",lead_id.eq." + id + ")&order=fecha_inicio.desc&limit=50")) {
            timeline.add(evento("visita", "Visita programada",
                    String.valueOf(v.get("estado")), String.valueOf(v.get("fecha_inicio"))));
        }

        // Contratos
        if (telefono != null && !"null".equals(telefono)) {
            for (Map<String, Object> c : supabase.selectFilter("contratos", "user_id=eq." + uid + "&inquilino_id=eq." + id)) {
                timeline.add(evento("contrato", "Contrato",
                        "Renta " + c.get("renta_mensual") + (" " + PaisActual.moneda()), String.valueOf(c.get("created_at"))));
            }
        }

        // Facturas
        for (Map<String, Object> f : supabase.selectFilter("facturas",
                "user_id=eq." + uid + "&cliente_email=eq." + email + "&order=fecha.desc&limit=20")) {
            timeline.add(evento("factura", "Factura " + f.get("numero"),
                    f.get("total") + (" " + PaisActual.moneda() + " - ") + f.get("estado"), String.valueOf(f.get("fecha"))));
        }

        // WhatsApp
        if (telefono != null && !"null".equals(telefono)) {
            String num = telefono.replaceAll("[\\s+\\-]", "");
            for (Map<String, Object> w : supabase.selectFilter("whatsapp_mensajes",
                    "user_id=eq." + uid + "&chat_id=eq." + num + "&order=fecha.desc&limit=30")) {
                timeline.add(evento("whatsapp", "WhatsApp " + w.get("direccion"),
                        String.valueOf(w.get("mensaje")), String.valueOf(w.get("fecha"))));
            }
        }

        // SMS
        if (telefono != null && !"null".equals(telefono)) {
            for (Map<String, Object> s : supabase.selectFilter("sms_mensajes",
                    "user_id=eq." + uid + "&destinatario=ilike.*" + telefono.replaceAll("[\\s+\\-]", "") + "&order=enviado_at.desc&limit=20")) {
                timeline.add(evento("sms", "SMS", String.valueOf(s.get("mensaje")), String.valueOf(s.get("enviado_at"))));
            }
        }

        // Emails campañas
        for (Map<String, Object> e : supabase.selectFilter("campana_envios",
                "user_id=eq." + uid + "&destinatario_email=eq." + email + "&order=created_at.desc&limit=20")) {
            timeline.add(evento("email", "Campaña email",
                    String.valueOf(e.get("estado")), String.valueOf(e.get("created_at"))));
        }

        // Reviews
        for (Map<String, Object> r : supabase.selectFilter("reviews",
                "user_id=eq." + uid + "&autor_email=eq." + email)) {
            timeline.add(evento("review", "⭐ Review " + r.get("puntuacion") + "/5",
                    String.valueOf(r.get("comentario")), String.valueOf(r.get("created_at"))));
        }

        // Ordenar por fecha desc
        timeline.sort((a, b) -> String.valueOf(b.get("fecha")).compareTo(String.valueOf(a.get("fecha"))));

        // Stats
        long visitasCount = timeline.stream().filter(t -> "visita".equals(t.get("tipo"))).count();
        long emailsCount = timeline.stream().filter(t -> "email".equals(t.get("tipo"))).count();
        long whatsappCount = timeline.stream().filter(t -> "whatsapp".equals(t.get("tipo"))).count();

        model.addAttribute("cliente", cliente);
        model.addAttribute("tipoCliente", tipo);
        model.addAttribute("timeline", timeline);
        model.addAttribute("visitasCount", visitasCount);
        model.addAttribute("emailsCount", emailsCount);
        model.addAttribute("whatsappCount", whatsappCount);
        return "cartera_360";
    }

    private Map<String, Object> evento(String tipo, Object titulo, String detalle, String fecha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", tipo);
        m.put("titulo", titulo);
        m.put("detalle", detalle);
        m.put("fecha", fecha);
        return m;
    }
}
