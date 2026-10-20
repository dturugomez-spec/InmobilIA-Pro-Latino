package com.inmobilia.controller;

import com.inmobilia.service.ChatbotService;
import com.inmobilia.service.RateLimiter;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.ClientIp;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatbotController {

    private final SupabaseService supabase;
    private final ChatbotService chatbotService;
    private final RateLimiter rateLimiter;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    // ===== ADMIN UI =====

    @GetMapping("/chatbot")
    public String admin(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Crear config si no existe
        List<Map<String, Object>> cfgs = supabase.selectFilter("chatbot_config", "user_id=eq." + uid);
        Map<String, Object> cfg;
        if (cfgs.isEmpty()) {
            Map<String, Object> nueva = new LinkedHashMap<>();
            nueva.put("user_id", uid);
            nueva.put("created_at", OffsetDateTime.now().toString());
            cfg = supabase.insert("chatbot_config", nueva);
        } else {
            cfg = cfgs.get(0);
        }

        // Conversaciones recientes
        List<Map<String, Object>> conversaciones = supabase.selectFilter(
                "chatbot_conversaciones",
                "user_id=eq." + uid + "&order=iniciada_at.desc&limit=20");

        // Stats
        long convertidas = conversaciones.stream().filter(c ->
                "convertida_lead".equals(c.get("estado")) || "convertida_visita".equals(c.get("estado"))).count();
        double tasaConversion = !conversaciones.isEmpty() ? convertidas * 100.0 / conversaciones.size() : 0;

        model.addAttribute("cfg", cfg);
        model.addAttribute("conversaciones", conversaciones);
        model.addAttribute("convertidas", convertidas);
        model.addAttribute("tasaConversion", tasaConversion);
        model.addAttribute("baseUrl", BASE_URL);
        return "chatbot_admin";
    }

    @PostMapping("/chatbot/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activo", "on".equals(form.get("activo")));
        data.put("nombre_bot", form.get("nombre_bot"));
        data.put("color_primario", form.get("color_primario"));
        data.put("mensaje_bienvenida", form.get("mensaje_bienvenida"));
        data.put("posicion", form.getOrDefault("posicion", "bottom-right"));
        data.put("persona_prompt", form.get("persona_prompt"));
        data.put("permitir_buscar_propiedades", "on".equals(form.get("permitir_buscar_propiedades")));
        data.put("permitir_agendar_visita", "on".equals(form.get("permitir_agendar_visita")));
        data.put("permitir_calcular_hipoteca", "on".equals(form.get("permitir_calcular_hipoteca")));
        data.put("auto_abrir_segundos", parseInt(form.get("auto_abrir_segundos"), 0));
        data.put("captar_email_pasos", parseInt(form.get("captar_email_pasos"), 3));
        data.put("updated_at", OffsetDateTime.now().toString());

        List<Map<String, Object>> rows = supabase.selectFilter("chatbot_config", "user_id=eq." + uid);
        if (!rows.isEmpty()) {
            supabase.update("chatbot_config", String.valueOf(rows.get(0).get("id")), data);
        }
        SessionUtils.flash(session, "✅ Configuración guardada");
        return "redirect:/chatbot";
    }

    /** Ver conversación específica. */
    @GetMapping("/chatbot/conversaciones/{id}")
    public String conversacion(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter(
                "chatbot_conversaciones", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/chatbot";

        List<Map<String, Object>> mensajes = supabase.selectFilter(
                "chatbot_mensajes", "conversacion_id=eq." + id + "&order=created_at.asc");

        model.addAttribute("conv", rows.get(0));
        model.addAttribute("mensajes", mensajes);
        return "chatbot_conversation";
    }

    // ===== ENDPOINTS PÚBLICOS PARA EL WIDGET =====

    @CrossOrigin(origins = "*")
    @PostMapping("/chatbot/api/iniciar")
    @ResponseBody
    public Map<String, Object> iniciar(@RequestBody Map<String, String> body, HttpServletRequest req) {
        if (!rateLimiter.tryAcquire("chatbot-ini:" + ClientIp.of(req), 10, Duration.ofHours(1))) {
            return Map.of("ok", false, "error", "Demasiadas conversaciones seguidas. Inténtalo más tarde.");
        }
        String token = body.get("token");
        String sessionId = body.getOrDefault("session_id", UUID.randomUUID().toString());
        return chatbotService.iniciarConversacion(
                token, sessionId,
                body.get("pagina"),
                req.getHeader("User-Agent"),
                req.getRemoteAddr());
    }

    @CrossOrigin(origins = "*")
    @PostMapping("/chatbot/api/mensaje")
    @ResponseBody
    public Map<String, Object> mensaje(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String convId = body.get("conversacion_id");
        String mensaje = body.get("mensaje");
        if (convId == null || mensaje == null || mensaje.isBlank()) return Map.of("ok", false);
        if (!rateLimiter.tryAcquire("chatbot-msg:" + ClientIp.of(req), 20, Duration.ofMinutes(10))) {
            return Map.of("ok", false, "error", "Vas muy rápido. Espera unos minutos y vuelve a escribir.");
        }
        // Mensajes largos = más tokens de IA: se recortan
        if (mensaje.length() > MAX_LONGITUD_MENSAJE) mensaje = mensaje.substring(0, MAX_LONGITUD_MENSAJE);
        return chatbotService.procesar(convId, mensaje);
    }

    static final int MAX_LONGITUD_MENSAJE = 1000;

    @CrossOrigin(origins = "*")
    @PostMapping("/chatbot/api/datos-visitante")
    @ResponseBody
    public Map<String, Object> datosVisitante(@RequestBody Map<String, String> body) {
        String convId = body.get("conversacion_id");
        if (convId == null) return Map.of("ok", false);
        Map<String, Object> upd = new LinkedHashMap<>();
        if (body.get("nombre") != null) upd.put("visitor_nombre", body.get("nombre"));
        if (body.get("email") != null) upd.put("visitor_email", body.get("email"));
        if (body.get("telefono") != null) upd.put("visitor_telefono", body.get("telefono"));
        supabase.update("chatbot_conversaciones", convId, upd);
        return Map.of("ok", true);
    }

    /** Script embebible (JS) que el cliente pone en su web. */
    @GetMapping(value = "/chatbot/widget.js", produces = "application/javascript")
    @ResponseBody
    public ResponseEntity<String> widgetScript() {
        String js = """
                (function() {
                    const script = document.currentScript;
                    const token = script.getAttribute('data-token');
                    if (!token) { console.error('InmobilIA Chatbot: data-token requerido'); return; }
                    const baseUrl = '%s';
                    const sessionId = localStorage.getItem('ipro_chat_session') || (Math.random().toString(36).substring(2));
                    localStorage.setItem('ipro_chat_session', sessionId);

                    let convId = null, cfg = {};

                    // Estilos
                    const css = `
                    .ipro-chat-btn{position:fixed;bottom:20px;right:20px;width:60px;height:60px;border-radius:50%%;background:linear-gradient(135deg,#3b82f6,#1e40af);color:white;border:none;font-size:28px;cursor:pointer;box-shadow:0 4px 20px rgba(0,0,0,0.15);z-index:9999;display:flex;align-items:center;justify-content:center;transition:transform 0.2s}
                    .ipro-chat-btn:hover{transform:scale(1.1)}
                    .ipro-chat-panel{position:fixed;bottom:90px;right:20px;width:380px;max-width:95vw;height:550px;max-height:80vh;background:white;border-radius:16px;box-shadow:0 10px 40px rgba(0,0,0,0.2);z-index:9999;display:none;flex-direction:column;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif}
                    .ipro-chat-panel.open{display:flex}
                    .ipro-chat-header{background:linear-gradient(135deg,#3b82f6,#1e40af);color:white;padding:16px;display:flex;align-items:center;justify-content:space-between}
                    .ipro-chat-header h3{margin:0;font-size:14px;font-weight:600}
                    .ipro-chat-header p{margin:2px 0 0;font-size:11px;opacity:0.8}
                    .ipro-chat-close{background:none;border:none;color:white;font-size:22px;cursor:pointer;opacity:0.8}
                    .ipro-chat-msgs{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:10px;background:#f8fafc}
                    .ipro-msg{padding:10px 14px;border-radius:14px;font-size:13px;line-height:1.4;max-width:80%%;word-wrap:break-word}
                    .ipro-msg-user{align-self:flex-end;background:#3b82f6;color:white;border-bottom-right-radius:4px}
                    .ipro-msg-bot{align-self:flex-start;background:white;color:#1e293b;border:1px solid #e2e8f0;border-bottom-left-radius:4px}
                    .ipro-msg-bot.typing{background:#e2e8f0;color:#64748b;font-style:italic}
                    .ipro-chat-input-zone{padding:12px;border-top:1px solid #e2e8f0;background:white}
                    .ipro-chat-input{width:100%%;padding:10px 14px;border:1px solid #e2e8f0;border-radius:20px;font-size:13px;outline:none;box-sizing:border-box}
                    .ipro-chat-input:focus{border-color:#3b82f6}
                    .ipro-chat-footer{text-align:center;font-size:10px;color:#94a3b8;padding:6px}
                    .ipro-chat-form{padding:12px;background:#f1f5f9;border-top:1px solid #e2e8f0}
                    .ipro-chat-form input{display:block;width:100%%;padding:8px 12px;margin-bottom:6px;border:1px solid #cbd5e1;border-radius:8px;font-size:12px;box-sizing:border-box}
                    .ipro-chat-form button{width:100%%;padding:8px;background:#10b981;color:white;border:none;border-radius:8px;font-size:12px;font-weight:600;cursor:pointer}
                    `;
                    const style = document.createElement('style');
                    style.textContent = css;
                    document.head.appendChild(style);

                    // HTML
                    const btn = document.createElement('button');
                    btn.className = 'ipro-chat-btn';
                    btn.innerHTML = '💬';
                    btn.onclick = togglePanel;
                    document.body.appendChild(btn);

                    const panel = document.createElement('div');
                    panel.className = 'ipro-chat-panel';
                    panel.innerHTML = `
                        <div class="ipro-chat-header">
                            <div><h3 id="ipro-bot-name">Asistente</h3><p>Online</p></div>
                            <button class="ipro-chat-close" onclick="document.querySelector('.ipro-chat-panel').classList.remove('open')">✕</button>
                        </div>
                        <div class="ipro-chat-msgs" id="ipro-msgs"></div>
                        <div class="ipro-chat-form" id="ipro-form" style="display:none">
                            <p style="margin:0 0 6px;font-size:11px;color:#475569">Para continuar, déjanos tus datos:</p>
                            <input type="text" id="ipro-nombre" placeholder="Tu nombre">
                            <input type="email" id="ipro-email" placeholder="Email">
                            <input type="tel" id="ipro-telefono" placeholder="Teléfono">
                            <button onclick="iproSendData()">Enviar</button>
                        </div>
                        <div class="ipro-chat-input-zone">
                            <input type="text" class="ipro-chat-input" id="ipro-input" placeholder="Escribe un mensaje..." onkeydown="if(event.key==='Enter')iproSend()">
                            <div class="ipro-chat-footer">Powered by <strong>InmobilIA Pro</strong></div>
                        </div>
                    `;
                    document.body.appendChild(panel);

                    function togglePanel() {
                        panel.classList.toggle('open');
                        if (panel.classList.contains('open') && !convId) iniciar();
                    }

                    async function iniciar() {
                        const res = await fetch(baseUrl + '/chatbot/api/iniciar', {
                            method: 'POST', headers: {'Content-Type':'application/json'},
                            body: JSON.stringify({token, session_id: sessionId, pagina: location.href})
                        });
                        const data = await res.json();
                        if (!data.ok) return;
                        convId = data.conversacion_id;
                        cfg = data;
                        document.getElementById('ipro-bot-name').textContent = data.nombre_bot || 'Asistente';
                        if (data.color_primario) {
                            document.querySelectorAll('.ipro-chat-btn, .ipro-chat-header').forEach(el => {
                                el.style.background = `linear-gradient(135deg, ${data.color_primario}, ${data.color_primario}dd)`;
                            });
                        }
                        addMsg('bot', data.mensaje_bienvenida || '👋 ¡Hola!');
                    }

                    function addMsg(rol, txt) {
                        const div = document.createElement('div');
                        div.className = 'ipro-msg ipro-msg-' + rol;
                        div.textContent = txt;
                        const msgs = document.getElementById('ipro-msgs');
                        msgs.appendChild(div);
                        msgs.scrollTop = msgs.scrollHeight;
                    }

                    window.iproSend = async function() {
                        const input = document.getElementById('ipro-input');
                        const msg = input.value.trim();
                        if (!msg || !convId) return;
                        input.value = '';
                        addMsg('user', msg);
                        addMsg('bot', '⌛ Escribiendo...');
                        const lastMsg = document.querySelector('.ipro-msg-bot:last-child');
                        lastMsg.classList.add('typing');

                        const res = await fetch(baseUrl + '/chatbot/api/mensaje', {
                            method: 'POST', headers: {'Content-Type':'application/json'},
                            body: JSON.stringify({conversacion_id: convId, mensaje: msg})
                        });
                        const data = await res.json();
                        lastMsg.remove();
                        if (data.ok) {
                            addMsg('bot', data.respuesta);
                            if (data.pedir_email) {
                                document.getElementById('ipro-form').style.display = 'block';
                            }
                        } else {
                            addMsg('bot', data.error || 'No he podido responder ahora mismo. Déjanos tus datos y te contactamos.');
                            document.getElementById('ipro-form').style.display = 'block';
                        }
                    };

                    window.iproSendData = async function() {
                        const nombre = document.getElementById('ipro-nombre').value;
                        const email = document.getElementById('ipro-email').value;
                        const telefono = document.getElementById('ipro-telefono').value;
                        if (!email && !telefono) return;
                        await fetch(baseUrl + '/chatbot/api/datos-visitante', {
                            method: 'POST', headers: {'Content-Type':'application/json'},
                            body: JSON.stringify({conversacion_id: convId, nombre, email, telefono})
                        });
                        document.getElementById('ipro-form').style.display = 'none';
                        addMsg('bot', '¡Gracias! Un agente te contactará en breve.');
                    };
                })();
                """.formatted(BASE_URL);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .header("Cache-Control", "public, max-age=3600")
                .body(js);
    }

    private static int parseInt(String s, int d) { try { return Integer.parseInt(s); } catch(Exception e) { return d; } }
}
