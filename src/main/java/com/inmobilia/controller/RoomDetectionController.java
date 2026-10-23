package com.inmobilia.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inmobilia.service.AIService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Detecta el tipo de estancia, su estado y qué mejorar a partir de la FOTO (la IA la ve de
 * verdad) o, si no hay foto, de una descripción escrita.
 * Antes la foto solo se previsualizaba: la IA únicamente leía el texto.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/reconocer-habitaciones")
public class RoomDetectionController {

    /** ~6 MB en base64: el navegador reduce la foto a 1280 px antes de enviarla. */
    static final int MAX_IMAGEN = 8_000_000;

    private final AIService ai;
    private final ObjectMapper mapper;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "room_detection";
    }

    @PostMapping("/clasificar")
    @ResponseBody
    public Map<String, Object> clasificar(@RequestBody Map<String, String> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false, "error", "Sesión caducada");

        String descripcion = Optional.ofNullable(body.get("descripcion")).orElse("").trim();
        String imagen = Optional.ofNullable(body.get("imagen")).orElse("").trim();
        if (!imagen.isEmpty() && !imagenValida(imagen)) {
            return Map.of("ok", false, "error", "La foto debe ser JPG, PNG o WEBP y de menos de 6 MB");
        }
        if (imagen.isEmpty() && descripcion.isBlank()) {
            return Map.of("ok", false, "error", "Sube una foto o describe la estancia");
        }

        String prompt = (imagen.isEmpty()
                ? "Descripción de la estancia: \"" + descripcion + "\"\n\n"
                : "Analiza la foto de esta estancia de un inmueble en venta o alquiler."
                  + (descripcion.isBlank() ? "" : " Nota del agente: \"" + descripcion + "\".") + "\n\n")
                + "Devuelve SOLO JSON válido con esta estructura:\n"
                + "{\n"
                + "  \"tipo_habitacion\": \"salon|dormitorio|cocina|bano|terraza|garaje|trastero|jardin|fachada|otro\",\n"
                + "  \"caracteristicas_detectadas\": [\"ventana grande\", \"suelo de parquet\"],\n"
                + "  \"estado\": \"reformada|para_reformar|en_buen_estado|nueva\",\n"
                + "  \"calidad_foto\": \"buena|mejorable|mala\",\n"
                + "  \"sugerencias\": [\"abrir cortinas para más luz\", \"retirar objetos personales\"]\n"
                + "}\n"
                + "Describe solo lo que se ve; si algo no se distingue, no lo inventes.";

        String sistema = "Eres un fotógrafo y home stager inmobiliario. Devuelves JSON sin texto adicional.";
        String res = imagen.isEmpty()
                ? ai.call(null, prompt, sistema, 500, uid)
                : ai.callConImagen(prompt, sistema, imagen, 500, uid);
        if (res == null) {
            return Map.of("ok", false, "error", "La IA no ha respondido. Revisa que te queden créditos de IA e inténtalo de nuevo.");
        }
        Map<String, Object> json = leerJson(res);
        if (json == null) return Map.of("ok", true, "texto", res);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("resultado", json);
        out.put("conFoto", !imagen.isEmpty());
        return out;
    }

    static boolean imagenValida(String imagen) {
        return imagen.length() <= MAX_IMAGEN
                && imagen.matches("(?s)^data:image/(jpeg|jpg|png|webp);base64,[A-Za-z0-9+/=\\s]+$");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> leerJson(String raw) {
        String s = raw.trim();
        int i = s.indexOf('{'), j = s.lastIndexOf('}');
        if (i < 0 || j <= i) return null;
        try { return mapper.readValue(s.substring(i, j + 1), Map.class); } catch (Exception e) { return null; }
    }
}
