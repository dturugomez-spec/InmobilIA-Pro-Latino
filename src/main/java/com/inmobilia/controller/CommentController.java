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
 * Comentarios, timeline y adjuntos en cualquier entidad.
 * Endpoints AJAX consumidos desde las fichas 360.
 */
@Controller
@RequiredArgsConstructor
public class CommentController {

    private final SupabaseService supabase;

    // ─── COMENTARIOS ───
    @GetMapping("/api/comments/{entityType}/{entityId}")
    @ResponseBody
    public List<Map<String, Object>> list(@PathVariable String entityType,
                                          @PathVariable String entityId,
                                          HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        String orgId = SessionUtils.uid(session);
        return supabase.findBy("comments", Map.of(
                "org_id", orgId,
                "entity_type", entityType,
                "entity_id", entityId
        )).stream()
        .sorted((a, b) -> String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at"))))
        .toList();
    }

    @PostMapping("/api/comments/{entityType}/{entityId}")
    @ResponseBody
    public Map<String, Object> create(@PathVariable String entityType,
                                      @PathVariable String entityId,
                                      @RequestBody Map<String, String> body,
                                      HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String txt = body.get("body");
        if (txt == null || txt.isBlank()) return Map.of("ok", false, "error", "Comentario vacío");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", SessionUtils.realUid(session));
        data.put("org_id", SessionUtils.uid(session));
        data.put("entity_type", entityType);
        data.put("entity_id", entityId);
        data.put("body", txt);
        data.put("author_name", SessionUtils.name(session));
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("comments", data);
        return Map.of("ok", saved != null, "comment", saved != null ? saved : Map.of());
    }

    @DeleteMapping("/api/comments/{id}")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        boolean ok = supabase.delete("comments", id, SessionUtils.realUid(session));
        return Map.of("ok", ok);
    }

    // ─── ADJUNTOS ───
    @GetMapping("/api/attachments/{entityType}/{entityId}")
    @ResponseBody
    public List<Map<String, Object>> attachments(@PathVariable String entityType,
                                                  @PathVariable String entityId,
                                                  HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        return supabase.findBy("attachments", Map.of(
                "org_id", SessionUtils.uid(session),
                "entity_type", entityType,
                "entity_id", entityId
        ));
    }

    @PostMapping("/api/attachments/{entityType}/{entityId}")
    @ResponseBody
    public Map<String, Object> addAttachment(@PathVariable String entityType,
                                              @PathVariable String entityId,
                                              @RequestBody Map<String, Object> body,
                                              HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);

        String name = String.valueOf(body.getOrDefault("name", ""));
        String url  = String.valueOf(body.getOrDefault("url", ""));
        if (name.isBlank() || url.isBlank()) return Map.of("ok", false, "error", "Nombre y URL obligatorios");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", SessionUtils.realUid(session));
        data.put("org_id", SessionUtils.uid(session));
        data.put("entity_type", entityType);
        data.put("entity_id", entityId);
        data.put("name", name);
        data.put("url", url);
        data.put("file_type", inferType(url));
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("attachments", data);
        return Map.of("ok", saved != null, "attachment", saved != null ? saved : Map.of());
    }

    @DeleteMapping("/api/attachments/{id}")
    @ResponseBody
    public Map<String, Object> deleteAttachment(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        boolean ok = supabase.delete("attachments", id, SessionUtils.realUid(session));
        return Map.of("ok", ok);
    }

    // ─── TIMELINE ───
    @GetMapping("/api/timeline/{entityType}/{entityId}")
    @ResponseBody
    public List<Map<String, Object>> timeline(@PathVariable String entityType,
                                               @PathVariable String entityId,
                                               HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        return supabase.findBy("activity_log", Map.of(
                "org_id", SessionUtils.uid(session),
                "entity_type", entityType,
                "entity_id", entityId
        )).stream()
        .sorted((a, b) -> String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at"))))
        .limit(50)
        .toList();
    }

    private static String inferType(String url) {
        String u = url.toLowerCase();
        if (u.endsWith(".pdf")) return "pdf";
        if (u.matches(".*\\.(jpg|jpeg|png|gif|webp|svg)([?#].*)?$")) return "image";
        if (u.matches(".*\\.(doc|docx|odt)([?#].*)?$")) return "doc";
        if (u.matches(".*\\.(xls|xlsx|csv|ods)([?#].*)?$")) return "sheet";
        return "other";
    }
}
