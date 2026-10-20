package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequiredArgsConstructor
@RequestMapping("/ocr-dni")
public class OcrDniController {

    private final AIService ai;
    private static final Pattern DNI_PATTERN = Pattern.compile("\\b(\\d{8})\\s?([A-HJ-NP-TV-Z])\\b");
    private static final Pattern NIE_PATTERN = Pattern.compile("\\b([XYZ])\\s?(\\d{7})\\s?([A-HJ-NP-TV-Z])\\b");

    @GetMapping
    public String panel(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "ocr_dni";
    }

    /** Recibe texto OCR (extraído cliente-side con Tesseract.js) y extrae campos. */
    @PostMapping("/extraer")
    @ResponseBody
    public Map<String, Object> extraer(@RequestBody Map<String, String> body) {
        String texto = body.getOrDefault("texto", "");
        if (texto.isEmpty()) return Map.of("ok", false, "error", "Sin texto");

        Map<String, Object> result = new LinkedHashMap<>();

        // DNI
        Matcher dni = DNI_PATTERN.matcher(texto.toUpperCase());
        if (dni.find()) {
            String num = dni.group(1);
            String letra = dni.group(2);
            result.put("dni", num + letra);
            result.put("tipo_doc", "DNI");
            result.put("valido", validarDniLetra(num, letra));
        } else {
            Matcher nie = NIE_PATTERN.matcher(texto.toUpperCase());
            if (nie.find()) {
                result.put("dni", nie.group(1) + nie.group(2) + nie.group(3));
                result.put("tipo_doc", "NIE");
            }
        }

        // Nombre/apellidos (heurística: líneas en mayúsculas)
        String[] lines = texto.split("\n");
        List<String> mayusLines = new ArrayList<>();
        for (String l : lines) {
            String t = l.trim();
            if (t.length() > 4 && t.equals(t.toUpperCase()) && t.matches("[A-ZÁÉÍÓÚÑ\\s]+")) {
                mayusLines.add(t);
            }
        }
        if (mayusLines.size() >= 1) result.put("apellidos", mayusLines.get(0));
        if (mayusLines.size() >= 2) result.put("nombre", mayusLines.get(1));

        // Fecha nacimiento (dd MM yyyy)
        Matcher fechaM = Pattern.compile("(\\d{2})[\\s.](\\d{2})[\\s.](\\d{4})").matcher(texto);
        if (fechaM.find()) {
            result.put("fecha_nacimiento", fechaM.group(3) + "-" + fechaM.group(2) + "-" + fechaM.group(1));
        }

        // Sexo
        if (texto.matches("(?i).*\\bM\\b.*")) result.put("sexo", "M");
        if (texto.matches("(?i).*\\bF\\b.*")) result.put("sexo", "F");

        // Nacionalidad
        if (texto.toUpperCase().contains("ESP")) result.put("nacionalidad", "ESPAÑOLA");

        result.put("ok", true);
        return result;
    }

    /** Algoritmo letra DNI español. */
    private boolean validarDniLetra(String numero, String letra) {
        try {
            int n = Integer.parseInt(numero) % 23;
            char[] LETRAS = "TRWAGMYFPDXBNJZSQVHLCKE".toCharArray();
            return LETRAS[n] == letra.charAt(0);
        } catch (Exception e) { return false; }
    }
}
