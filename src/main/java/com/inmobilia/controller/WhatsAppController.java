package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.WhatsAppService;
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
@RequestMapping("/whatsapp")
public class WhatsAppController {

    private final SupabaseService supabase;
    private final WhatsAppService waService;

    @GetMapping
    public String chat(HttpSession session, Model model, @RequestParam(required = false) String chatId) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> cfgs = supabase.selectFilter("whatsapp_config", "user_id=eq." + uid);
        Map<String, Object> cfg = cfgs.isEmpty() ? null : cfgs.get(0);

        // Chats agrupados
        List<Map<String, Object>> mensajes = supabase.selectFilter(
                "whatsapp_mensajes", "user_id=eq." + uid + "&order=fecha.desc&limit=500");
        Map<String, List<Map<String, Object>>> porChat = new LinkedHashMap<>();
        for (Map<String, Object> m : mensajes) {
            String c = String.valueOf(m.get("chat_id"));
            porChat.computeIfAbsent(c, k -> new ArrayList<>()).add(m);
        }

        List<Map<String, Object>> mensajesChat = chatId != null ? porChat.getOrDefault(chatId, new ArrayList<>()) : new ArrayList<>();
        Collections.reverse(mensajesChat);

        List<Map<String, Object>> plantillas = supabase.selectFilter(
                "whatsapp_plantillas", "user_id=eq." + uid + "&estado_aprobacion=eq.APPROVED");

        model.addAttribute("cfg", cfg);
        model.addAttribute("chats", porChat);
        model.addAttribute("chatActivo", chatId);
        model.addAttribute("mensajesChat", mensajesChat);
        model.addAttribute("plantillas", plantillas);
        return "whatsapp_chat";
    }

    @PostMapping("/config")
    public String config(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("phone_number_id", form.get("phone_number_id"));
        data.put("business_account_id", form.get("business_account_id"));
        data.put("access_token", form.get("access_token"));
        data.put("webhook_verify_token", form.get("webhook_verify_token"));
        data.put("numero_emisor", form.get("numero_emisor"));
        data.put("nombre_emisor", form.get("nombre_emisor"));
        data.put("activo", "on".equals(form.getOrDefault("activo", "on")));
        data.put("updated_at", OffsetDateTime.now().toString());

        List<Map<String, Object>> ex = supabase.selectFilter("whatsapp_config", "user_id=eq." + uid);
        if (!ex.isEmpty()) supabase.update("whatsapp_config", String.valueOf(ex.get(0).get("id")), data);
        else {
            data.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("whatsapp_config", data);
        }
        return "redirect:/whatsapp";
    }

    @PostMapping("/enviar")
    @ResponseBody
    public Map<String, Object> enviar(@RequestParam String numero, @RequestParam String mensaje, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        return waService.enviarTexto(uid, numero, mensaje);
    }

    @PostMapping("/enviar-plantilla")
    @ResponseBody
    public Map<String, Object> enviarPlantilla(@RequestParam String numero,
                                                @RequestParam String plantilla,
                                                @RequestParam(required = false) String params,
                                                HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);
        List<String> ps = params == null ? new ArrayList<>() : Arrays.asList(params.split("\\|"));
        return waService.enviarPlantilla(uid, numero, plantilla, "es", ps);
    }

    @PostMapping("/plantilla/crear")
    public String crearPlantilla(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("nombre", form.get("nombre"));
        data.put("nombre_meta", form.get("nombre_meta"));
        data.put("categoria", form.getOrDefault("categoria", "UTILITY"));
        data.put("cuerpo", form.get("cuerpo"));
        data.put("estado_aprobacion", "PENDING");
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("whatsapp_plantillas", data);
        return "redirect:/whatsapp";
    }

    /** Webhook público para recibir mensajes de Meta. */
    @GetMapping("/webhook")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> verify(@RequestParam("hub.mode") String mode,
                                                                   @RequestParam("hub.verify_token") String token,
                                                                   @RequestParam("hub.challenge") String challenge) {
        List<Map<String, Object>> cfgs = supabase.selectFilter("whatsapp_config", "webhook_verify_token=eq." + token);
        if (cfgs.isEmpty() || !"subscribe".equals(mode)) {
            return org.springframework.http.ResponseEntity.status(403).build();
        }
        return org.springframework.http.ResponseEntity.ok(challenge);
    }

    @PostMapping("/webhook")
    @ResponseBody
    public Map<String, Object> webhook(@RequestBody Map<String, Object> body) {
        waService.procesarWebhook(body);
        return Map.of("ok", true);
    }
}
