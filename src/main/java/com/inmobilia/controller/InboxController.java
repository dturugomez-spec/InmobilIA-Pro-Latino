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
@RequestMapping("/inbox")
public class InboxController {

    private final SupabaseService supabase;
    private final com.inmobilia.service.CifradoService cifrado;

    @GetMapping
    public String inbox(HttpSession session, Model model,
                        @RequestParam(defaultValue = "inbox") String carpeta,
                        @RequestParam(required = false) String emailId) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> cuentas = supabase.selectFilter("imap_cuentas", "user_id=eq." + uid);
        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=fecha.desc&limit=100");
        if ("inbox".equals(carpeta)) q.append("&archivado=eq.false");
        else if ("estrella".equals(carpeta)) q.append("&estrella=eq.true");
        else if ("archivados".equals(carpeta)) q.append("&archivado=eq.true");

        List<Map<String, Object>> emails = supabase.selectFilter("emails_inbox", q.toString());
        long noLeidos = emails.stream().filter(e -> !Boolean.TRUE.equals(e.get("leido"))).count();

        Map<String, Object> emailActivo = null;
        if (emailId != null) {
            List<Map<String, Object>> rows = supabase.selectOwned("emails_inbox", emailId, uid);
            if (!rows.isEmpty()) {
                // El HTML de un email lo escribe quien lo envía (cualquiera de fuera): se limpia antes de mostrarlo
                emailActivo = new java.util.LinkedHashMap<>(rows.get(0));
                Object cuerpo = emailActivo.get("cuerpo_html");
                if (cuerpo != null) emailActivo.put("cuerpo_html", com.inmobilia.util.HtmlSeguro.limpiar(String.valueOf(cuerpo)));
                supabase.update("emails_inbox", emailId, Map.of("leido", true), uid);
            }
        }

        model.addAttribute("cuentas", cuentas);
        model.addAttribute("emails", emails);
        model.addAttribute("noLeidos", noLeidos);
        model.addAttribute("emailActivo", emailActivo);
        model.addAttribute("carpeta", carpeta);
        return "inbox";
    }

    @PostMapping("/cuenta/guardar")
    public String saveCuenta(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("email", form.get("email"));
        data.put("imap_host", form.get("imap_host"));
        int puerto;
        try { puerto = Integer.parseInt(form.getOrDefault("imap_port", "").trim()); } catch (NumberFormatException e) { puerto = 993; }
        data.put("imap_port", puerto); // antes un puerto vacío daba error 500
        data.put("imap_user", form.get("imap_user"));
        // La contraseña del correo se guarda cifrada (AES-GCM con CIFRADO_KEY), no en claro
        data.put("imap_pass", cifrado.cifrar(form.get("imap_pass")));
        data.put("smtp_host", form.get("smtp_host"));
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("imap_cuentas", data);
        SessionUtils.flash(session, "✅ Cuenta IMAP añadida");
        return "redirect:/inbox";
    }

    @PostMapping("/{id}/marcar")
    @ResponseBody
    public Map<String, Object> marcar(@PathVariable String id, @RequestParam String accion, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        Map<String, Object> upd = switch (accion) {
            case "leido" -> Map.of("leido", true);
            case "no_leido" -> Map.of("leido", false);
            case "estrella" -> Map.of("estrella", true);
            case "archivar" -> Map.of("archivado", true);
            default -> Map.of();
        };
        if (upd.isEmpty()) return Map.of("ok", false);
        return Map.of("ok", supabase.update("emails_inbox", id, upd, uid));
    }
}
