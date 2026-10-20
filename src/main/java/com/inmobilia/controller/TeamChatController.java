package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat interno del equipo: canal general + mensajes directos + comentarios
 * por entidad (inquilino, propiedad, lead).
 */
@Controller
@RequiredArgsConstructor
public class TeamChatController {

    private final SupabaseService supabase;

    @GetMapping("/team/chat")
    public String chat(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        var members = supabase.findBy("users", Map.of("organization_id", orgId));
        var msgs = supabase.findBy("team_messages", Map.of("org_id", orgId)).stream()
                .filter(m -> m.get("to_user_id") == null || "null".equals(String.valueOf(m.get("to_user_id"))))
                .sorted((a, b) -> String.valueOf(a.get("created_at")).compareTo(String.valueOf(b.get("created_at"))))
                .limit(100)
                .toList();

        model.addAttribute("page", "Chat del equipo");
        model.addAttribute("members", members);
        model.addAttribute("msgs", msgs);
        return "team_chat";
    }

    @PostMapping("/api/team/chat")
    @ResponseBody
    public Map<String, Object> send(@RequestBody Map<String, String> body, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String txt = body.getOrDefault("body", "").trim();
        if (txt.isEmpty()) return Map.of("ok", false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("org_id", SessionUtils.uid(session));
        data.put("from_user_id", SessionUtils.realUid(session));
        if (body.get("toUserId") != null && !body.get("toUserId").isBlank())
            data.put("to_user_id", body.get("toUserId"));
        if (body.get("entityType") != null) data.put("entity_type", body.get("entityType"));
        if (body.get("entityId") != null)   data.put("entity_id", body.get("entityId"));
        data.put("body", txt);
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("team_messages", data);
        return saved != null ? Map.of("ok", true, "msg", saved) : Map.of("ok", false);
    }

    @GetMapping("/api/team/chat/recent")
    @ResponseBody
    public List<Map<String, Object>> recent(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        return supabase.findBy("team_messages", Map.of("org_id", SessionUtils.uid(session))).stream()
                .filter(m -> m.get("to_user_id") == null || "null".equals(String.valueOf(m.get("to_user_id"))))
                .sorted((a, b) -> String.valueOf(a.get("created_at")).compareTo(String.valueOf(b.get("created_at"))))
                .toList();
    }
}
