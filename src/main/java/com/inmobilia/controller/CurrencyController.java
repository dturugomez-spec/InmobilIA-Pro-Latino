package com.inmobilia.controller;

import com.inmobilia.service.CurrencyService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/divisas")
public class CurrencyController {

    private final CurrencyService currencyService;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        model.addAttribute("divisas", currencyService.divisas());
        model.addAttribute("tipos", currencyService.tiposActuales());
        return "currency_panel";
    }

    @PostMapping("/convertir")
    @ResponseBody
    public Map<String, Object> convertir(@RequestParam double importe,
                                          @RequestParam String origen,
                                          @RequestParam String destino) {
        double r = currencyService.convertir(importe, origen, destino);
        return Map.of("ok", true, "resultado", r);
    }
}
