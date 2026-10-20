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
@RequestMapping("/reviews")
public class ReviewsController {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> reviews = supabase.selectFilter(
                "reviews", "user_id=eq." + uid + "&order=created_at.desc");

        double promedio = reviews.stream().mapToInt(r -> n(r.get("puntuacion"))).average().orElse(0);
        long pendientes = reviews.stream().filter(r -> !Boolean.TRUE.equals(r.get("aprobada"))).count();
        long aprobadas = reviews.stream().filter(r -> Boolean.TRUE.equals(r.get("aprobada"))).count();
        long destacadas = reviews.stream().filter(r -> Boolean.TRUE.equals(r.get("destacada"))).count();

        model.addAttribute("reviews", reviews);
        model.addAttribute("promedio", promedio);
        model.addAttribute("pendientes", pendientes);
        model.addAttribute("aprobadas", aprobadas);
        model.addAttribute("destacadas", destacadas);
        model.addAttribute("baseUrl", BASE_URL);
        return "reviews_list";
    }

    @PostMapping("/solicitar")
    public String solicitar(@RequestParam String email, @RequestParam String nombre,
                             HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("autor_nombre", nombre);
        data.put("autor_email", email);
        data.put("solicitud_token", token);
        data.put("solicitada_at", OffsetDateTime.now().toString());
        data.put("puntuacion", 0);
        data.put("comentario", "");
        data.put("aprobada", false);
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("reviews", data);

        String url = BASE_URL + "/reviews/publico/" + token;
        try {
            emailService.send(email, "¿Cómo ha sido tu experiencia con nosotros?",
                    String.format(
                            "<h2>👋 Hola %s</h2>" +
                            "<p>¿Qué te ha parecido nuestro servicio? Tu opinión nos ayuda muchísimo.</p>" +
                            "<p><a href='%s' style='display:inline-block;padding:12px 24px;background:#10b981;color:white;border-radius:8px;text-decoration:none;font-weight:bold'>⭐ Dejar mi review</a></p>" +
                            "<p style='color:#64748b;font-size:12px'>Solo te llevará 30 segundos.</p>",
                            nombre, url));
        } catch (Exception ignored) {}

        SessionUtils.flash(session, "📧 Solicitud enviada a " + email);
        return "redirect:/reviews";
    }

    @PostMapping("/{id}/aprobar")
    @ResponseBody
    public Map<String, Object> aprobar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        return Map.of("ok", supabase.update("reviews", id, Map.of("aprobada", true), uid));
    }

    @PostMapping("/{id}/destacar")
    @ResponseBody
    public Map<String, Object> destacar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<Map<String, Object>> r = supabase.selectOwned("reviews", id, uid);
        if (r.isEmpty()) return Map.of("ok", false);
        boolean d = !Boolean.TRUE.equals(r.get(0).get("destacada"));
        supabase.update("reviews", id, Map.of("destacada", d), uid);
        return Map.of("ok", true, "destacada", d);
    }

    @PostMapping("/{id}/responder")
    public String responder(@PathVariable String id, @RequestParam String respuesta, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.update("reviews", id, Map.of(
                "respuesta", respuesta,
                "respuesta_at", OffsetDateTime.now().toString()
        ), uid);
        return "redirect:/reviews";
    }

    // ===== ENDPOINT PÚBLICO =====

    @GetMapping("/publico/{token}")
    public String formPublico(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("reviews", "solicitud_token=eq." + token);
        if (rows.isEmpty()) {
            model.addAttribute("error", "Enlace no válido");
            return "reviews_public";
        }
        model.addAttribute("review", rows.get(0));
        return "reviews_public";
    }

    @PostMapping("/publico/{token}")
    public String guardarPublico(@PathVariable String token, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("reviews", "solicitud_token=eq." + token);
        if (rows.isEmpty()) return "redirect:/";

        Integer puntuacion = com.inmobilia.util.Numeros.entero(form.get("puntuacion"));
        if (puntuacion == null || puntuacion < 1 || puntuacion > 5) { // antes, sin estrellas marcadas: error 500
            model.addAttribute("review", rows.get(0));
            model.addAttribute("aviso", "Marca de 1 a 5 estrellas antes de enviar.");
            return "reviews_public";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("puntuacion", puntuacion);
        data.put("titulo", form.get("titulo"));
        data.put("comentario", form.get("comentario"));
        data.put("autor_rol", form.get("autor_rol"));
        data.put("respondida_at", OffsetDateTime.now().toString());
        supabase.update("reviews", String.valueOf(rows.get(0).get("id")), data);

        model.addAttribute("gracias", true);
        return "reviews_public";
    }

    private static int n(Object o) { try { return Integer.parseInt(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
