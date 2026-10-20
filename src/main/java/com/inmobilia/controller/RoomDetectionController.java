package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Detecta el tipo de habitación a partir de la descripción de la imagen
 * (usando OpenAI Vision o GPT-4o). Devuelve etiquetas + sugerencias.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/reconocer-habitaciones")
public class RoomDetectionController {

    private final AIService ai;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "room_detection";
    }

    /** El cliente describe la imagen via OCR/CLIP y la IA clasifica. */
    @PostMapping("/clasificar")
    @ResponseBody
    public Map<String, Object> clasificar(@RequestBody Map<String, String> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        String descripcionVisual = body.get("descripcion");
        if (descripcionVisual == null || descripcionVisual.isBlank()) {
            return Map.of("ok", false, "error", "Sin descripción");
        }

        String prompt = "Descripción de la foto: \"" + descripcionVisual + "\"\n\n" +
                "Eres un experto inmobiliario. Devuelve SOLO JSON válido con esta estructura:\n" +
                "{\n" +
                "  \"tipo_habitacion\": \"salon|dormitorio|cocina|bano|terraza|garaje|trastero|jardin|otro\",\n" +
                "  \"caracteristicas_detectadas\": [\"ventana grande\", \"suelo de parquet\", \"...\"],\n" +
                "  \"estado\": \"reformada|para_reformar|en_buen_estado|nueva\",\n" +
                "  \"sugerencias\": [\"mejorar iluminación\", \"poner mobiliario\", \"...\"]\n" +
                "}";

        try {
            String res = ai.call(null, prompt, "Eres clasificador inmobiliario. Devuelve JSON sin texto adicional.", 400, uid);
            return Map.of("ok", true, "resultado", res);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
