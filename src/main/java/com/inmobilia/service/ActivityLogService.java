package com.inmobilia.service;

import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registra cambios en entidades (CRUD) en activity_log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLogService {

    private final SupabaseService supabase;

    @Async
    public void log(HttpSession session, String entityType, String entityId,
                    String action, String field, String oldVal, String newVal) {
        if (session == null || !SessionUtils.isLoggedIn(session)) return;
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user_id", SessionUtils.realUid(session));
            entry.put("org_id", SessionUtils.uid(session));
            entry.put("entity_type", entityType);
            entry.put("entity_id", entityId);
            entry.put("action", action);
            if (field != null) entry.put("field", field);
            if (oldVal != null) entry.put("old_value", oldVal.length() > 200 ? oldVal.substring(0, 200) : oldVal);
            if (newVal != null) entry.put("new_value", newVal.length() > 200 ? newVal.substring(0, 200) : newVal);
            entry.put("actor_name", SessionUtils.name(session));
            entry.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("activity_log", entry);
        } catch (Exception e) {
            log.warn("Activity log failed: {}", e.getMessage());
        }
    }

    public void logCreated(HttpSession s, String type, String id) { log(s, type, id, "created", null, null, null); }
    public void logUpdated(HttpSession s, String type, String id) { log(s, type, id, "updated", null, null, null); }
    public void logDeleted(HttpSession s, String type, String id) { log(s, type, id, "deleted", null, null, null); }
}
