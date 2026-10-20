package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Cron diario que avisa de eventos relevantes en contratos:
 * - 60 días antes del fin → considerar renovación
 * - 30 días antes → recordar al inquilino
 * - 7 días antes → urgente
 * - Día del fin → fin del contrato + devolución fianza
 * - 1 año exacto → actualización IPC anual
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractRemindersService {

    private final SupabaseService supabase;
    private final NotificationService notif;
    private final EmailService emailService;

    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Madrid")
    public void cron() {
        log.info("[CRON] Recordatorios contratos...");
        List<Map<String, Object>> contratos = supabase.selectFilter("contratos", "estado=eq.activo");
        LocalDate hoy = LocalDate.now();

        for (Map<String, Object> c : contratos) {
            try {
                String userId = String.valueOf(c.get("user_id"));
                LocalDate fin = parseFecha(c.get("fecha_fin"));
                LocalDate inicio = parseFecha(c.get("fecha_inicio"));
                String numero = String.valueOf(c.getOrDefault("numero", "—"));

                if (fin != null) {
                    long dias = ChronoUnit.DAYS.between(hoy, fin);
                    if (dias == 60) {
                        notif.create(userId, "⏰ Contrato " + numero + " - 60 días",
                                "Considera renovación o búsqueda de nuevo inquilino", "/contratos", "media");
                    } else if (dias == 30) {
                        notif.create(userId, "📅 Contrato " + numero + " - 30 días",
                                "Recuerda hablar con el inquilino sobre la renovación", "/contratos", "media");
                    } else if (dias == 7) {
                        notif.create(userId, "🚨 Contrato " + numero + " caduca en 7 días",
                                "Toma decisión urgente", "/contratos", "alta");
                    } else if (dias == 0) {
                        notif.create(userId, "🏁 Contrato " + numero + " finaliza HOY",
                                "Gestionar entrega de llaves y devolución de fianza", "/contratos", "alta");
                    }
                }

                if (inicio != null) {
                    long meses = ChronoUnit.MONTHS.between(inicio, hoy);
                    long dias = ChronoUnit.DAYS.between(inicio.plusMonths(meses), hoy);
                    if (meses >= 12 && meses % 12 == 0 && dias == 0) {
                        notif.create(userId, "📊 Actualización IPC contrato " + numero,
                                "Actualizar renta según IPC anual", "/contratos", "media");
                    }
                }

                // Fianza vencida (3 meses después del fin sin devolver)
                if (fin != null && hoy.isAfter(fin.plusMonths(3))) {
                    if (!Boolean.TRUE.equals(c.get("fianza_devuelta"))) {
                        notif.create(userId, "⚠️ Fianza no devuelta",
                                "Contrato " + numero + ": fianza pendiente de devolución (>3 meses)",
                                "/contratos", "alta");
                    }
                }
            } catch (Exception e) {
                log.warn("Err recordatorio contrato: {}", e.getMessage());
            }
        }
    }

    private LocalDate parseFecha(Object o) {
        if (o == null) return null;
        try { return LocalDate.parse(String.valueOf(o).substring(0, 10)); } catch (Exception e) { return null; }
    }
}
