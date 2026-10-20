package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.BrandingService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bandeja única de leads: TODOS los contactos (web, portal, marketplace, chatbot,
 * open house...) en un solo sitio, ordenados por prioridad, con respuesta redactada
 * por IA en un clic (WhatsApp/email pre-rellenados). Responder rápido = cerrar más.
 */
@Controller
@RequiredArgsConstructor
public class LeadInboxController {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final BrandingService branding;

    @GetMapping("/bandeja")
    public String inbox(HttpSession session, Model model,
                        @RequestParam(required = false) String etapa,
                        @RequestParam(required = false) String origen) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        StringBuilder filtro = new StringBuilder("user_id=eq." + uid);
        if (etapa != null && !etapa.isBlank()) filtro.append("&etapa=eq.").append(etapa);
        if (origen != null && !origen.isBlank()) filtro.append("&origen=eq.").append(origen);
        filtro.append("&order=created_at.desc&limit=200");

        List<Map<String, Object>> leads = supabase.selectFilter("leads", filtro.toString());

        long nuevos = leads.stream().filter(l -> "Nuevo".equalsIgnoreCase(str(l.get("etapa")))).count();
        Set<String> origenes = new TreeSet<>();
        for (Map<String, Object> l : leads) { String o = str(l.get("origen")); if (!o.isBlank()) origenes.add(o); }

        model.addAttribute("page", "Bandeja de leads");
        model.addAttribute("etapas", List.of("Nuevo", "Contactado", "Visita", "Oferta", "Firmado", "Perdido"));
        model.addAttribute("leads", leads);
        model.addAttribute("nuevos", nuevos);
        model.addAttribute("origenes", origenes);
        model.addAttribute("fEtapa", etapa == null ? "" : etapa);
        model.addAttribute("fOrigen", origen == null ? "" : origen);
        return "lead_inbox";
    }

    /** Genera con IA un primer mensaje para el lead + enlaces de WhatsApp y email listos para enviar. */
    @PostMapping("/bandeja/{id}/redactar")
    @ResponseBody
    public Map<String, Object> redactar(@PathVariable String id,
                                        @RequestParam(required = false, defaultValue = "whatsapp") String canal,
                                        HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false, "error", "Sesión expirada");

        List<Map<String, Object>> rows = supabase.selectFilter("leads", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return Map.of("ok", false, "error", "Lead no encontrado");
        Map<String, Object> l = rows.get(0);

        Map<String, Object> b = branding.branding(uid);
        String agencia = str(b.get("nombre"));
        String nombre = str(l.get("nombre"));
        String interes = str(l.get("interes"));
        String zona = str(l.get("zona"));
        String presupuesto = str(l.get("presupuesto"));

        String prompt = "Redacta el PRIMER mensaje de contacto de un agente de la inmobiliaria \"" + agencia + "\""
                + " para un cliente potencial llamado " + (nombre.isBlank() ? "el cliente" : nombre) + "."
                + " Interés: " + (interes.isBlank() ? "vivienda" : interes) + "."
                + (zona.isBlank() ? "" : " Zona: " + zona + ".")
                + (presupuesto.isBlank() ? "" : " Presupuesto: " + presupuesto + " euros.")
                + " El mensaje debe: saludar por su nombre, presentarse como " + agencia + ", agradecer su interés,"
                + " proponer un siguiente paso concreto (una llamada corta o una visita) y pedir su disponibilidad."
                + " Tono cercano y profesional. Máximo 4 frases. En español. Sin emojis. Solo el texto del mensaje.";

        String system = "Eres un agente inmobiliario español experto en atención al cliente. Respondes SIEMPRE en español, breve y cálido.";
        String mensaje;
        try {
            mensaje = aiService.call(null, prompt, system, 400, uid);
        } catch (Exception e) {
            mensaje = null;
        }
        if (mensaje == null || mensaje.isBlank()) {
            String n = nombre.isBlank() ? "" : " " + nombre;
            mensaje = "Hola" + n + ", soy de " + agencia + ". Gracias por tu interés. Me encantaría ayudarte a encontrar"
                    + " lo que buscas; ¿te viene bien una llamada rápida esta semana para concretar? Dime tu disponibilidad y lo vemos.";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("mensaje", mensaje);
        String tel = digits(str(l.get("telefono")));
        if (!tel.isBlank()) {
            if (tel.length() == 9) tel = "34" + tel;
            out.put("whatsapp", "https://wa.me/" + tel + "?text=" + enc(mensaje));
        }
        String email = str(l.get("email"));
        if (!email.isBlank()) {
            out.put("mailto", "mailto:" + email + "?subject=" + enc("Tu consulta en " + agencia)
                    + "&body=" + enc(mensaje));
        }
        return out;
    }

    /** Marca un lead como Contactado (acción rápida desde la bandeja). */
    @PostMapping("/bandeja/{id}/contactado")
    public String contactado(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("leads", "id=eq." + id + "&user_id=eq." + uid);
        if (!rows.isEmpty()) supabase.update("leads", id, Map.of("etapa", "Contactado"), uid);
        return "redirect:/bandeja";
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String digits(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }
    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
