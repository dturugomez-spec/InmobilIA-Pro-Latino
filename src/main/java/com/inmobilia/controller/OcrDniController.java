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
    /** CURP mexicana (18 caracteres). */
    private static final Pattern CURP = Pattern.compile("\\b([A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d)\\b");
    /** RUN / RUT chileno: 12.345.678-K */
    private static final Pattern RUN = Pattern.compile("\\b(\\d{1,2}\\.?\\d{3}\\.?\\d{3})-([\\dK])\\b");
    /** Número de documento genérico (DNI, cédula, DPI, DUI…): 6 a 13 cifras, con o sin puntos/guiones. */
    private static final Pattern NUMERO_DOC = Pattern.compile("\\b(\\d{1,3}(?:[.\\s-]?\\d{3}){1,3}(?:[.\\s-]?\\d{1,4})?)\\b");

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

        // Documento de identidad (CURP, RUN, DNI, cédula…)
        String up = texto.toUpperCase();
        Matcher curp = CURP.matcher(up);
        Matcher run = RUN.matcher(up);
        if (curp.find()) {
            result.put("dni", curp.group(1));
            result.put("tipo_doc", "CURP");
        } else if (run.find()) {
            String cuerpo = run.group(1).replace(".", "");
            result.put("dni", cuerpo + "-" + run.group(2));
            result.put("tipo_doc", "RUN");
            result.put("valido", digitoRut(cuerpo) == run.group(2).charAt(0));
        } else {
            Matcher num = NUMERO_DOC.matcher(up);
            while (num.find()) {
                String limpio = num.group(1).replaceAll("[.\\s-]", "");
                if (limpio.length() >= 6 && limpio.length() <= 13) {
                    result.put("dni", limpio);
                    result.put("tipo_doc", com.inmobilia.util.PaisActual.get().etiquetaIdPersonal());
                    break;
                }
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


        result.put("ok", true);
        return result;
    }

    /** Dígito verificador del RUT/RUN chileno (módulo 11). */
    private static char digitoRut(String cuerpo) {
        int suma = 0, mult = 2;
        for (int i = cuerpo.length() - 1; i >= 0; i--) {
            suma += (cuerpo.charAt(i) - '0') * mult;
            mult = mult == 7 ? 2 : mult + 1;
        }
        int dv = 11 - (suma % 11);
        return dv == 11 ? '0' : dv == 10 ? 'K' : (char) ('0' + dv);
    }
}
