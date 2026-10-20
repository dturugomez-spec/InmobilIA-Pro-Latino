package com.inmobilia.controller;

import com.inmobilia.service.PortfolioAuditService;
import com.inmobilia.service.PortfolioAuditService.AuditReport;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Auditor IA de Cartera - diferenciador unico del software.
 * Ningun competidor del mercado ofrece auditoria priorizada con impacto EUR.
 */
@Controller
@RequestMapping("/herramientas/auditoria")
@RequiredArgsConstructor
public class PortfolioAuditController {

    private final PortfolioAuditService auditor;

    @GetMapping
    public String run(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        AuditReport report = auditor.audit(uid);
        model.addAttribute("page", "Auditor IA de Cartera");
        model.addAttribute("report", report);
        model.addAttribute("findings", report.findings());
        model.addAttribute("areas", report.areas());
        return "herramientas/auditoria";
    }
}
