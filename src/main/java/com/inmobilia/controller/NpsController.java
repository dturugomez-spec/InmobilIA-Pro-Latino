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
@RequestMapping("/nps")
public class NpsController {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> respuestas = supabase.selectFilter("nps_respuestas", "user_id=eq." + uid + "&respondida_at=not.is.null");

        long promotores = respuestas.stream().filter(r -> n(r.get("puntuacion")) >= 9).count();
        long pasivos = respuestas.stream().filter(r -> { int p = n(r.get("puntuacion")); return p >= 7 && p <= 8; }).count();
        long detractores = respuestas.stream().filter(r -> n(r.get("puntuacion")) <= 6).count();
        int total = respuestas.size();
        double nps = total > 0 ? ((promotores - detractores) * 100.0 / total) : 0;

        model.addAttribute("respuestas", respuestas);
        model.addAttribute("promotores", promotores);
        model.addAttribute("pasivos", pasivos);
        model.addAttribute("detractores", detractores);
        model.addAttribute("nps", nps);
        return "nps_dashboard";
    }

    @PostMapping("/solicitar")
    public String solicitar(@RequestParam String nombre, @RequestParam String email, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("cliente_nombre", nombre);
        data.put("cliente_email", email);
        data.put("token", token);
        data.put("solicitada_at", OffsetDateTime.now().toString());
        supabase.insert("nps_respuestas", data);

        String url = BASE_URL + "/nps/responder/" + token;
        try {
            emailService.send(email, "¿Nos recomendarías?",
                    "<h2>Hola " + nombre + "</h2><p>En una escala de 0 a 10, ¿qué probabilidad hay de que nos recomiendes?</p>" +
                    "<p><a href='" + url + "' style='display:inline-block;padding:12px 24px;background:#3b82f6;color:white;border-radius:8px;text-decoration:none'>📝 Responder</a></p>");
        } catch (Exception e) {}
        return "redirect:/nps";
    }

    @GetMapping("/responder/{token}")
    public String form(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("nps_respuestas", "token=eq." + token);
        if (rows.isEmpty()) { model.addAttribute("error", true); return "nps_public"; }
        model.addAttribute("r", rows.get(0));
        return "nps_public";
    }

    @PostMapping("/responder/{token}")
    public String guardar(@PathVariable String token, @RequestParam int puntuacion,
                          @RequestParam(required = false) String comentario, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("nps_respuestas", "token=eq." + token);
        if (rows.isEmpty()) return "redirect:/";

        String categoria = puntuacion >= 9 ? "promotor" : puntuacion >= 7 ? "pasivo" : "detractor";
        supabase.update("nps_respuestas", String.valueOf(rows.get(0).get("id")), Map.of(
                "puntuacion", puntuacion,
                "comentario", comentario != null ? comentario : "",
                "categoria", categoria,
                "respondida_at", OffsetDateTime.now().toString()
        ));
        model.addAttribute("gracias", true);
        return "nps_public";
    }

    private static int n(Object o) { try { return Integer.parseInt(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
