package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 🎯 Captación proactiva / Farming por zona con IA
 *
 * Redacta los mensajes de captación (carta, WhatsApp, email) para una zona y un
 * ángulo concreto, e inserta automáticamente el enlace de valoración de la
 * agencia. Cierra el embudo: Farming → Valoración → Portal del propietario.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaptacionService {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final BrandingService branding;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    /** Etiquetas legibles de los ángulos de captación. */
    public static final Map<String, String> ANGULOS = new LinkedHashMap<>();
    static {
        ANGULOS.put("valoracion_gratis", "Valoración gratis de su vivienda");
        ANGULOS.put("tengo_comprador", "Tengo comprador para su zona");
        ANGULOS.put("vendi_cerca", "Acabo de vender cerca");
        ANGULOS.put("mercado_alto", "El mercado está en buen momento");
        ANGULOS.put("herederos", "Asesoramiento en herencia/venta");
        ANGULOS.put("alquiler_a_venta", "Le interesa vender en lugar de alquilar");
    }

    /** Genera los 3 mensajes (carta, whatsapp, email) para una campaña. */
    public Map<String, String> generarMensajes(String uid, String zona, String tipoObjetivo, String angulo, String extra) {
        Map<String, Object> b = branding.branding(uid);
        String empresa = str(b.getOrDefault("nombre", "nuestra agencia"));
        String agente = str(b.getOrDefault("contacto", ""));
        String telefono = str(b.getOrDefault("telefono", ""));
        String linkVal = enlaceValoracion(uid);
        String anguloTxt = ANGULOS.getOrDefault(angulo, angulo);

        String contexto = String.format(Locale.ROOT,
            "Eres un agente inmobiliario experto en captación (farming) en " + com.inmobilia.util.PaisActual.get().nombre() + ".\n" +
            "Agencia: %s. Agente: %s. Teléfono: %s.\n" +
            "Zona objetivo: %s. Tipo de propietario: %s. Ángulo del mensaje: %s.\n" +
            "%s\n" +
            "Enlace de valoración gratuita de la agencia: %s\n\n" +
            "Reglas: tono cercano, profesional y honesto; SIN promesas de resultados; español de Latinoamérica (" + com.inmobilia.util.PaisActual.get().nombre() + "), sin vosotros; " +
            "incluye SIEMPRE el enlace de valoración como llamada a la acción; firma con el nombre del agente y la agencia.",
            empresa, agente.isBlank() ? "(el agente)" : agente,
            telefono.isBlank() ? "(teléfono de la agencia)" : telefono,
            blankTo(zona, "la zona"), legibleTipo(tipoObjetivo), anguloTxt,
            extra == null || extra.isBlank() ? "" : "Contexto adicional: " + extra,
            linkVal);

        Map<String, String> out = new LinkedHashMap<>();
        out.put("carta", gen(contexto, "Escribe una CARTA postal de captación (120-160 palabras), con encabezado, cuerpo y despedida.", uid, linkVal));
        out.put("whatsapp", gen(contexto, "Escribe un mensaje de WhatsApp/SMS breve (máx 60 palabras), directo y cercano, con un emoji como mucho.", uid, linkVal));
        out.put("email", gen(contexto, "Escribe un EMAIL de captación con asunto en la primera línea (prefijo 'Asunto: ') y cuerpo de 80-120 palabras.", uid, linkVal));
        return out;
    }

    private String gen(String contexto, String instruccion, String uid, String linkVal) {
        try {
            String r = aiService.call(null, contexto + "\n\nTarea: " + instruccion,
                    "Eres un copywriter inmobiliario experto en captación.", 320, uid);
            if (r == null || r.isBlank()) return fallback(linkVal);
            // Garantiza que el enlace aparece aunque la IA lo omita
            if (!r.contains(linkVal) && linkVal != null && !linkVal.isBlank()) {
                r = r + "\n\nValora tu vivienda gratis aquí: " + linkVal;
            }
            return r.trim();
        } catch (Exception e) {
            return fallback(linkVal);
        }
    }

    private String fallback(String linkVal) {
        return "Hola, soy tu agente de confianza en la zona. Si estás pensando en vender, " +
               "puedo darte una valoración gratuita y sin compromiso de tu vivienda.\n\n" +
               "Valórala aquí: " + (linkVal == null ? "" : linkVal);
    }

    /** Enlace público de valoración de la agencia (lo crea si no existe). */
    public String enlaceValoracion(String uid) {
        Map<String, Object> u = agencia(uid);
        Object t = u.get("valoracion_token");
        String token;
        if (t != null && !str(t).isBlank() && !"null".equals(str(t))) {
            token = str(t);
        } else {
            token = "val" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            try { supabase.update("users", uid, Map.of("valoracion_token", token), uid); } catch (Exception ignored) {}
        }
        return BASE_URL + "/v/" + token;
    }

    private Map<String, Object> agencia(String uid) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        return u.isEmpty() ? new HashMap<>() : u.get(0);
    }

    private static String legibleTipo(String t) {
        if (t == null) return "propietarios";
        return switch (t) {
            case "propietarios_piso" -> "propietarios de pisos";
            case "chalets" -> "propietarios de chalets/casas";
            case "locales" -> "propietarios de locales comerciales";
            case "herederos" -> "herederos de una vivienda";
            default -> t.replace("_", " ");
        };
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String blankTo(String s, String def) { return s == null || s.isBlank() ? def : s; }
}
