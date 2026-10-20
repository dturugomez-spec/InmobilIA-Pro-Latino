package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Genera contratos de arrendamiento imprimibles a PDF (vista HTML que el usuario imprime).
 */
@Controller
@RequiredArgsConstructor
public class ContractPdfController {

    private final SupabaseService supabase;

    @GetMapping("/contratos/{id}/pdf")
    public String contractPdf(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);

        var rows = supabase.findBy("contratos", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/crud/contratos";
        Map<String, Object> contrato = rows.get(0);

        // Buscar inquilino y propiedad relacionados
        Map<String, Object> inquilino = findFirst(supabase.query("inquilinos", uid),
                "name", String.valueOf(contrato.getOrDefault("tenant_name", "")));
        Map<String, Object> prop = findFirst(supabase.query("propiedades", uid),
                "name", String.valueOf(contrato.getOrDefault("property_name", "")));

        // Datos del emisor (admin)
        var users = supabase.findBy("users", Map.of("id", uid));
        Map<String, Object> emisor = users.isEmpty() ? Map.of() : users.get(0);

        model.addAttribute("page", "Contrato de arrendamiento");
        model.addAttribute("contrato", contrato);
        model.addAttribute("inquilino", inquilino != null ? inquilino : Map.of());
        model.addAttribute("prop", prop != null ? prop : Map.of());
        model.addAttribute("emisor", emisor);
        model.addAttribute("hoy", LocalDate.now().format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", new Locale("es","ES"))));
        return "contract_pdf";
    }

    private Map<String, Object> findFirst(List<Map<String, Object>> rows, String field, String value) {
        if (value == null || value.isBlank()) return null;
        return rows.stream()
                .filter(r -> value.equalsIgnoreCase(String.valueOf(r.getOrDefault(field, ""))))
                .findFirst().orElse(null);
    }
}
