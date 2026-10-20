package com.inmobilia.controller;

import com.inmobilia.service.NotificationService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    @GetMapping("/api/notifications/recent")
    @ResponseBody
    public Map<String, Object> recent(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String uid = SessionUtils.realUid(session);
        List<Map<String, Object>> list = notifications.getRecent(uid, 10);
        long unread = notifications.countUnread(uid);
        return Map.of("ok", true, "unread", unread, "items", list);
    }

    @PostMapping("/api/notifications/{id}/read")
    @ResponseBody
    public Map<String, Object> markRead(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        boolean ok = notifications.markRead(id, SessionUtils.realUid(session));
        return Map.of("ok", ok);
    }

    @PostMapping("/api/notifications/read-all")
    @ResponseBody
    public Map<String, Object> markAllRead(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        notifications.markAllRead(SessionUtils.realUid(session));
        return Map.of("ok", true);
    }
}
