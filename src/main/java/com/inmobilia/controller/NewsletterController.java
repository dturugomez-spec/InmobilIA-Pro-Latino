package com.inmobilia.controller;

import com.inmobilia.service.EmailService;
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
@RequestMapping("/newsletter")
public class NewsletterController {

    private final SupabaseService supabase;
    private final EmailService email;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> suscriptores = supabase.selectFilter("newsletter_suscriptores",
                "user_id=eq." + uid + "&baja_at=is.null&order=created_at.desc&limit=200");
        long confirmados = suscriptores.stream().filter(s -> Boolean.TRUE.equals(s.get("confirmado"))).count();
        List<Map<String, Object>> envios = supabase.selectFilter("newsletter_envios", "user_id=eq." + uid + "&order=fecha_envio.desc&limit=10");

        model.addAttribute("suscriptores", suscriptores);
        model.addAttribute("confirmados", confirmados);
        model.addAttribute("envios", envios);
        return "newsletter_dashboard";
    }

    @PostMapping("/enviar")
    public String enviar(@RequestParam String asunto, @RequestParam String contenido, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> subs = supabase.selectFilter("newsletter_suscriptores",
                "user_id=eq." + uid + "&confirmado=eq.true&baja_at=is.null");

        int enviados = 0;
        for (Map<String, Object> s : subs) {
            try {
                String html = contenido + "<hr><p style='font-size:11px;color:#94a3b8'>Si no quieres más, <a href='" + BASE_URL + "/newsletter/baja/" + s.get("confirmacion_token") + "'>date de baja</a>.</p>";
                email.send(String.valueOf(s.get("email")), asunto, html);
                enviados++;
                Thread.sleep(80);
            } catch (Exception e) {}
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("asunto", asunto);
        data.put("contenido_html", contenido);
        data.put("enviados", enviados);
        data.put("fecha_envio", OffsetDateTime.now().toString());
        supabase.insert("newsletter_envios", data);

        SessionUtils.flash(session, "📧 Enviada a " + enviados + " suscriptores");
        return "redirect:/newsletter";
    }

    // === Endpoints públicos ===

    @PostMapping("/suscribir")
    public String suscribir(@RequestParam String email, @RequestParam(required = false) String nombre,
                             @RequestParam(required = false) String microsite, Model model) {
        // Encontrar user_id por microsite slug
        List<Map<String, Object>> ms = supabase.selectFilter("microsites", "slug=eq." + (microsite != null ? microsite : ""));
        if (ms.isEmpty()) { model.addAttribute("error", true); return "newsletter_public"; }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", ms.get(0).get("user_id"));
        data.put("email", email);
        if (nombre != null) data.put("nombre", nombre);
        data.put("fuente", "microsite");
        data.put("confirmado", false);
        data.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("newsletter_suscriptores", data);

        String url = BASE_URL + "/newsletter/confirmar/" + saved.get("confirmacion_token");
        try {
            this.email.send(email, "Confirma tu suscripción",
                    "<h2>👋 ¡Bienvenido!</h2><p>Click para confirmar:</p>" +
                    "<p><a href='" + url + "' style='display:inline-block;padding:12px 24px;background:#3b82f6;color:white;border-radius:8px;text-decoration:none'>✅ Confirmar</a></p>");
        } catch (Exception e) {}

        model.addAttribute("suscrito", true);
        return "newsletter_public";
    }

    @GetMapping("/confirmar/{token}")
    public String confirmar(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("newsletter_suscriptores", "confirmacion_token=eq." + token);
        if (rows.isEmpty()) { model.addAttribute("error", true); return "newsletter_public"; }
        supabase.update("newsletter_suscriptores", String.valueOf(rows.get(0).get("id")), Map.of(
                "confirmado", true, "confirmado_at", OffsetDateTime.now().toString()
        ));
        model.addAttribute("confirmado", true);
        return "newsletter_public";
    }

    @GetMapping("/baja/{token}")
    public String baja(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("newsletter_suscriptores", "confirmacion_token=eq." + token);
        if (!rows.isEmpty()) {
            supabase.update("newsletter_suscriptores", String.valueOf(rows.get(0).get("id")),
                    Map.of("baja_at", OffsetDateTime.now().toString()));
        }
        model.addAttribute("baja", true);
        return "newsletter_public";
    }
}
