package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestión de etiquetas/tags por organización + asociación con entidades.
 */
@Controller
@RequiredArgsConstructor
public class TagController {

    private final SupabaseService supabase;

    @GetMapping("/api/tags")
    @ResponseBody
    public List<Map<String, Object>> list(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        return supabase.findBy("tags", Map.of("org_id", SessionUtils.uid(session)));
    }

    @PostMapping("/api/tags")
    @ResponseBody
    public Map<String, Object> create(@RequestBody Map<String, String> body, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) return Map.of("ok", false, "error", "Nombre vacío");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("org_id", SessionUtils.uid(session));
        data.put("name", name);
        data.put("color", body.getOrDefault("color", "#3b82f6"));
        if (body.get("icon") != null) data.put("icon", body.get("icon"));
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("tags", data);
        return saved != null ? Map.of("ok", true, "tag", saved) : Map.of("ok", false);
    }

    @DeleteMapping("/api/tags/{id}")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        return Map.of("ok", supabase.delete("tags", id, SessionUtils.uid(session)));
    }

    // ─── Asociación con entidades ───
    @GetMapping("/api/entity-tags/{entityType}/{entityId}")
    @ResponseBody
    public List<Map<String, Object>> getEntityTags(@PathVariable String entityType,
                                                    @PathVariable String entityId,
                                                    HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        return supabase.findBy("entity_tags", Map.of(
                "entity_type", entityType, "entity_id", entityId));
    }

    @PostMapping("/api/entity-tags/{entityType}/{entityId}")
    @ResponseBody
    public Map<String, Object> addTag(@PathVariable String entityType,
                                       @PathVariable String entityId,
                                       @RequestBody Map<String, String> body,
                                       HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tag_id", body.get("tagId"));
        data.put("entity_type", entityType);
        data.put("entity_id", entityId);
        Map<String, Object> saved = supabase.insert("entity_tags", data);
        return Map.of("ok", saved != null);
    }
}
