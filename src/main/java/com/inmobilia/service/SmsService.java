package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * SMS gateway. Soporta Twilio API REST y se puede ampliar a Esendex/Lleida.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsService {

    private final SupabaseService supabase;
    private final RestTemplate rest = new RestTemplate();

    public Map<String, Object> enviar(String userId, String destino, String mensaje) {
        List<Map<String, Object>> cfgs = supabase.selectFilter("sms_config", "user_id=eq." + userId + "&activo=eq.true");
        if (cfgs.isEmpty()) return Map.of("ok", false, "error", "SMS no configurado");
        Map<String, Object> cfg = cfgs.get(0);

        Map<String, Object> registro = new LinkedHashMap<>();
        registro.put("user_id", userId);
        registro.put("destinatario", destino);
        registro.put("mensaje", mensaje);

        try {
            String proveedor = String.valueOf(cfg.getOrDefault("proveedor", "twilio"));
            if ("twilio".equals(proveedor)) {
                String sid = String.valueOf(cfg.get("account_sid"));
                String token = String.valueOf(cfg.get("auth_token"));
                String from = String.valueOf(cfg.get("numero_emisor"));

                String url = "https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json";
                HttpHeaders h = new HttpHeaders();
                String auth = Base64.getEncoder().encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8));
                h.set("Authorization", "Basic " + auth);
                h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

                MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                body.add("To", normalizar(destino));
                body.add("From", from);
                body.add("Body", mensaje);

                Map<?, ?> resp = rest.postForObject(url, new HttpEntity<>(body, h), Map.class);
                registro.put("estado", "enviado");
                registro.put("proveedor_id", resp != null ? String.valueOf(resp.get("sid")) : null);
                registro.put("coste", 0.04);
                supabase.insert("sms_mensajes", registro);
                return Map.of("ok", true, "id", resp != null ? resp.get("sid") : "");
            }
            return Map.of("ok", false, "error", "Proveedor no soportado");
        } catch (Exception e) {
            registro.put("estado", "fallido");
            registro.put("error_mensaje", e.getMessage());
            supabase.insert("sms_mensajes", registro);
            return Map.of("ok", false, "error", e.getMessage());
        } finally {
            registro.put("enviado_at", OffsetDateTime.now().toString());
        }
    }

    private String normalizar(String tel) {
        String clean = tel.replaceAll("[\\s\\-()]", "");
        if (clean.startsWith("00")) clean = "+" + clean.substring(2);
        if (!clean.startsWith("+")) {
            // Número nacional sin prefijo: se le pone el del país de la inmobiliaria
            String prefijo = com.inmobilia.util.PaisActual.get().prefijo();
            clean = clean.startsWith(prefijo.substring(1)) && clean.length() > 10 ? "+" + clean : prefijo + clean;
        }
        return clean;
    }
}
