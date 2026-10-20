package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notificaciones in-app: alertas en la campanita del topbar.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SupabaseService supabase;

    /** Compat (Sprints 47-110): firma simplificada title/message/link/level. */
    @Async
    public void create(String userId, String title, String message, String link, String level) {
        create(userId, "info", levelToIcon(level), title, message, link);
    }

    private String levelToIcon(String level) {
        if (level == null) return "info";
        return switch (level) {
            case "alta", "high", "critical" -> "alert-triangle";
            case "media", "medium", "warning" -> "alert-circle";
            case "baja", "low" -> "info";
            default -> "bell";
        };
    }

    @Async
    public void create(String userId, String type, String icon, String title, String message, String link) {
        try {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("user_id", userId);
            n.put("type", type);
            if (icon != null) n.put("icon", icon);
            n.put("title", title);
            if (message != null) n.put("message", message);
            if (link != null) n.put("link", link);
            n.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("notifications", n);
        } catch (Exception e) {
            log.warn("No se pudo crear notificación: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getRecent(String userId, int limit) {
        try {
            var all = supabase.findBy("notifications", Map.of("user_id", userId));
            return all.stream()
                    .sorted((a, b) -> String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at"))))
                    .limit(limit)
                    .toList();
        } catch (Exception e) { return List.of(); }
    }

    public long countUnread(String userId) {
        try {
            return supabase.findBy("notifications", Map.of("user_id", userId)).stream()
                    .filter(n -> n.get("read_at") == null || "null".equals(String.valueOf(n.get("read_at"))))
                    .count();
        } catch (Exception e) { return 0; }
    }

    public boolean markRead(String notificationId, String userId) {
        return supabase.update("notifications", notificationId,
                Map.of("read_at", OffsetDateTime.now().toString()), userId);
    }

    public void markAllRead(String userId) {
        try {
            var unread = supabase.findBy("notifications", Map.of("user_id", userId)).stream()
                    .filter(n -> n.get("read_at") == null || "null".equals(String.valueOf(n.get("read_at"))))
                    .toList();
            String now = OffsetDateTime.now().toString();
            for (var n : unread) {
                supabase.update("notifications", String.valueOf(n.get("id")),
                        Map.of("read_at", now), userId);
            }
        } catch (Exception e) {
            log.warn("Error marcando todas como leídas: {}", e.getMessage());
        }
    }
}
