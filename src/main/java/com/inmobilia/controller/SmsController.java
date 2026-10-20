package com.inmobilia.controller;

import com.inmobilia.service.SmsService;
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
@RequestMapping("/sms")
public class SmsController {

    private final SupabaseService supabase;
    private final SmsService smsService;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> cfgs = supabase.selectFilter("sms_config", "user_id=eq." + uid);
        List<Map<String, Object>> mensajes = supabase.selectFilter("sms_mensajes", "user_id=eq." + uid + "&order=enviado_at.desc&limit=50");
        model.addAttribute("cfg", cfgs.isEmpty() ? new LinkedHashMap<>() : cfgs.get(0));
        model.addAttribute("mensajes", mensajes);
        return "sms_panel";
    }

    @PostMapping("/config")
    public String config(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("proveedor", form.getOrDefault("proveedor", "twilio"));
        data.put("account_sid", form.get("account_sid"));
        data.put("auth_token", form.get("auth_token"));
        data.put("numero_emisor", form.get("numero_emisor"));
        data.put("activo", "on".equals(form.getOrDefault("activo", "on")));
        List<Map<String, Object>> ex = supabase.selectFilter("sms_config", "user_id=eq." + uid);
        if (!ex.isEmpty()) supabase.update("sms_config", String.valueOf(ex.get(0).get("id")), data);
        else {
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("sms_config", data);
        }
        return "redirect:/sms";
    }

    @PostMapping("/enviar")
    @ResponseBody
    public Map<String, Object> enviar(@RequestParam String destino, @RequestParam String mensaje, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        return smsService.enviar(uid, destino, mensaje);
    }
}
