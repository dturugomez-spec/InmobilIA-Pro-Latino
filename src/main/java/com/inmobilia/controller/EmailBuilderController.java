package com.inmobilia.controller;

import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/email-builder")
public class EmailBuilderController {

    @GetMapping
    public String builder(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        return "email_builder";
    }
}
