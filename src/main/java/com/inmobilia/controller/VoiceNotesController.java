package com.inmobilia.controller;

import com.inmobilia.service.AIService;
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
@RequestMapping("/notas-voz")
public class VoiceNotesController {

    private final SupabaseService supabase;
    private final AIService ai;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> notas = supabase.selectFilter("notas_voz",
                "user_id=eq." + uid + "&order=fecha_grabacion.desc&limit=50");
        model.addAttribute("notas", notas);
        return "voice_notes";
    }

    /** Guarda nota con transcripción ya hecha en cliente (Web Speech API). */
    @PostMapping("/guardar")
    @ResponseBody
    public Map<String, Object> guardar(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return Map.of("ok", false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("autor_id", realUid);
        data.put("transcripcion", body.get("transcripcion"));
        data.put("duracion_segundos", body.getOrDefault("duracion", 0));
        if (body.get("lead_id") != null) data.put("lead_id", body.get("lead_id"));
        if (body.get("propiedad_id") != null) data.put("propiedad_id", body.get("propiedad_id"));
        data.put("fecha_grabacion", OffsetDateTime.now().toString());
        data.put("created_at", OffsetDateTime.now().toString());

        // Resumen IA opcional
        String txt = String.valueOf(body.get("transcripcion"));
        if (txt != null && txt.length() > 200) {
            try {
                String resumen = ai.call(null, "Resume en 1 frase: " + txt,
                        "Eres asistente que resume notas de campo de agentes inmobiliarios. Conciso.", 100, uid);
                data.put("resumen_ia", resumen);
            } catch (Exception e) {}
        }

        Map<String, Object> saved = supabase.insert("notas_voz", data);
        return Map.of("ok", true, "id", saved.get("id"));
    }
}
