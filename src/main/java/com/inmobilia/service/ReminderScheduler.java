package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Cron interno: genera notificaciones automáticas y recordatorios.
 * Spring @Scheduled — sin coste extra.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderScheduler {

    private final SupabaseService supabase;
    private final NotificationService notifications;
    private final EmailService emailService;

    /** Cada día a las 8:00 AM Madrid: revisar vencimientos. */
    @Scheduled(cron = "0 0 8 * * *", zone = "Europe/Madrid")
    public void dailyChecks() {
        log.info(">>> ReminderScheduler dailyChecks");
        try {
            checkContractEndings();
            checkOverduePayments();
            checkScheduledMaintenance();
            checkLeadFollowups();
        } catch (Exception e) {
            log.error("Error en dailyChecks: {}", e.getMessage(), e);
        }
    }

    /** Lunes a las 9:00 AM: digest semanal por email. */
    @Scheduled(cron = "0 0 9 * * MON", zone = "Europe/Madrid")
    public void weeklyDigest() {
        log.info(">>> ReminderScheduler weeklyDigest");
        try {
            var users = supabase.findBy("users", Map.of("active", "true"));
            for (var u : users) {
                String orgId = String.valueOf(u.get("id"));
                if (!orgId.equals(String.valueOf(u.get("organization_id")))) continue; // solo a admins

                int props = supabase.query("propiedades", orgId).size();
                int inquilinos = supabase.query("inquilinos", orgId).size();
                int leadsActivos = (int) supabase.query("leads", orgId).stream()
                        .filter(l -> !"Cerrado".equalsIgnoreCase(String.valueOf(l.get("etapa")))
                                  && !"Perdido".equalsIgnoreCase(String.valueOf(l.get("etapa")))).count();

                String body = "📊 Resumen semanal de tu cartera\n\n" +
                        "• Propiedades: " + props + "\n" +
                        "• Inquilinos: " + inquilinos + "\n" +
                        "• Leads activos: " + leadsActivos + "\n\n" +
                        "Accede a la app para ver el detalle: https://inmobiliapro.es/dashboard\n\n" +
                        "— InmobilIA Pro";

                String email = String.valueOf(u.getOrDefault("email", ""));
                if (!email.isBlank() && email.contains("@")) {
                    try {
                        emailService.send(null, email, "📊 Tu resumen semanal · InmobilIA Pro", body);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.error("Error en weeklyDigest: {}", e.getMessage());
        }
    }

    private void checkContractEndings() {
        try {
            var allUsers = supabase.findBy("users", Map.of());
            for (var u : allUsers) {
                String orgId = String.valueOf(u.get("id"));
                if (!orgId.equals(String.valueOf(u.get("organization_id")))) continue;

                for (var c : supabase.query("contratos", orgId)) {
                    String end = String.valueOf(c.getOrDefault("end_date", ""));
                    if (end.isBlank() || "null".equals(end)) continue;
                    try {
                        LocalDate endDate = LocalDate.parse(end.substring(0, 10));
                        long days = ChronoUnit.DAYS.between(LocalDate.now(), endDate);
                        if (days == 60 || days == 30 || days == 7 || days == 1) {
                            notifications.create(orgId, "warning", "⚠",
                                    "Contrato vence en " + days + " " + (days == 1 ? "día" : "días"),
                                    String.valueOf(c.get("tenant_name")) + " · " + String.valueOf(c.get("property_name")),
                                    "/crud/contratos/" + c.get("id") + "/edit");
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void checkOverduePayments() {
        try {
            int currentMonth = LocalDate.now().getMonthValue();
            int currentYear = LocalDate.now().getYear();
            int day = LocalDate.now().getDayOfMonth();
            if (day < 10) return; // solo a partir del día 10

            var allUsers = supabase.findBy("users", Map.of());
            for (var u : allUsers) {
                String orgId = String.valueOf(u.get("id"));
                if (!orgId.equals(String.valueOf(u.get("organization_id")))) continue;

                var inquilinos = supabase.query("inquilinos", orgId);
                var ingresos = supabase.query("transacciones", orgId).stream()
                        .filter(t -> "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", ""))))
                        .toList();

                for (var inq : inquilinos) {
                    double rent = parseD(inq.get("rent_amount"));
                    if (rent <= 0) continue;
                    String prop = String.valueOf(inq.getOrDefault("property_name", ""));
                    String name = String.valueOf(inq.getOrDefault("name", ""));

                    double pagado = ingresos.stream()
                            .filter(t -> {
                                String d = String.valueOf(t.getOrDefault("date", ""));
                                if (d.length() < 10) return false;
                                if (Integer.parseInt(d.substring(0, 4)) != currentYear) return false;
                                if (Integer.parseInt(d.substring(5, 7)) != currentMonth) return false;
                                return prop.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", "")));
                            })
                            .mapToDouble(t -> parseD(t.get("amount"))).sum();

                    if (pagado < rent * 0.95) {
                        notifications.create(orgId, "error", "🔴",
                                "Renta sin cobrar: " + name,
                                "Pendiente " + String.format(("%.0f " + PaisActual.moneda()), rent - pagado) + " del mes actual",
                                "/payments");
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void checkScheduledMaintenance() {
        try {
            var allUsers = supabase.findBy("users", Map.of());
            for (var u : allUsers) {
                String orgId = String.valueOf(u.get("id"));
                if (!orgId.equals(String.valueOf(u.get("organization_id")))) continue;

                for (var m : supabase.query("mantenimiento", orgId)) {
                    String sch = String.valueOf(m.getOrDefault("scheduled_date", ""));
                    if (sch.isBlank() || "null".equals(sch)) continue;
                    String status = String.valueOf(m.getOrDefault("status", ""));
                    if ("Completado".equalsIgnoreCase(status) || "Cerrado".equalsIgnoreCase(status)) continue;
                    try {
                        LocalDate d = LocalDate.parse(sch.substring(0, 10));
                        long days = ChronoUnit.DAYS.between(LocalDate.now(), d);
                        if (days == 1) {
                            notifications.create(orgId, "info", "🔧",
                                    "Mantenimiento mañana",
                                    String.valueOf(m.get("title")) + " · " + String.valueOf(m.get("property_name")),
                                    "/crud/mantenimiento/" + m.get("id") + "/edit");
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void checkLeadFollowups() {
        try {
            var allUsers = supabase.findBy("users", Map.of());
            for (var u : allUsers) {
                String orgId = String.valueOf(u.get("id"));
                if (!orgId.equals(String.valueOf(u.get("organization_id")))) continue;

                for (var l : supabase.query("leads", orgId)) {
                    String next = String.valueOf(l.getOrDefault("next_followup", ""));
                    if (next.isBlank() || "null".equals(next)) continue;
                    try {
                        LocalDate d = LocalDate.parse(next.substring(0, 10));
                        if (d.equals(LocalDate.now())) {
                            notifications.create(orgId, "info", "💼",
                                    "Seguimiento de lead hoy",
                                    String.valueOf(l.get("nombre")) + " · " + String.valueOf(l.get("interes")),
                                    "/entity/lead/" + l.get("id"));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private static double parseD(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
