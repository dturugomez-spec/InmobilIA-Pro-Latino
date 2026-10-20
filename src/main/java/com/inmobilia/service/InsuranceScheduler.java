package com.inmobilia.service;

import com.inmobilia.util.CargoSeguro;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Alertas automáticas de vencimiento de pólizas.
 * Genera notificaciones a 30, 14 y 3 días del vencimiento.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InsuranceScheduler {

    private final SupabaseService supabase;
    private final NotificationService notif;

    /** Diaria 9:30 → buscar pólizas que vencen pronto */
    @Scheduled(cron = "0 30 9 * * *", zone = "Europe/Madrid")
    public void alertVencimientos() {
        log.info("[CRON] alertVencimientos seguros");
        LocalDate hoy = LocalDate.now();

        List<Map<String, Object>> polizas = supabase.selectFilter(
                "seguros", "estado=eq.activa&order=fecha_vencimiento.asc");

        for (Map<String, Object> p : polizas) {
            try {
                String fv = String.valueOf(p.get("fecha_vencimiento"));
                LocalDate fecha = LocalDate.parse(fv);
                long dias = ChronoUnit.DAYS.between(hoy, fecha);

                if (dias == 30 || dias == 14 || dias == 3 || dias == 0) {
                    String uid = String.valueOf(p.get("user_id"));
                    String tipo = String.valueOf(p.get("tipo"));
                    String ase = String.valueOf(p.get("aseguradora"));
                    String titulo;
                    String urgencia;
                    if (dias == 0) {
                        titulo = "⚠️ Vence HOY";
                        urgencia = "alta";
                    } else if (dias == 3) {
                        titulo = "🟠 Vence en 3 días";
                        urgencia = "alta";
                    } else if (dias == 14) {
                        titulo = "🟡 Vence en 2 semanas";
                        urgencia = "media";
                    } else {
                        titulo = "📅 Vence en 30 días";
                        urgencia = "baja";
                    }

                    String mensaje = String.format("Póliza %s de %s — %s", tipo, ase, fv);
                    notif.create(uid, titulo, mensaje, "/seguros/" + p.get("id"), urgencia);
                }

                // Marcar expiradas automáticamente
                if (dias < 0 && !Boolean.TRUE.equals(p.get("renovacion_automatica"))) {
                    supabase.update("seguros", String.valueOf(p.get("id")),
                            Map.of("estado", "expirada"));
                }
            } catch (Exception e) {
                log.warn("Error procesando póliza {}: {}", p.get("id"), e.getMessage());
            }
        }
    }

    /**
     * Diaria 9:35 → avisar de los cargos de seguros (7 días y 1 día antes) y, cuando la fecha ya
     * ha pasado, apuntar el siguiente según la forma de pago. La app no cobra ni paga: es control.
     * Necesita las columnas de la migración V84; sin ellas la consulta no devuelve nada.
     */
    @Scheduled(cron = "0 35 9 * * *", zone = "Europe/Madrid")
    public void alertCargos() {
        LocalDate hoy = LocalDate.now();
        List<Map<String, Object>> polizas = supabase.selectFilter(
                "seguros", "estado=eq.activa&proximo_cargo=not.is.null&order=proximo_cargo.asc");
        for (Map<String, Object> p : polizas) {
            try {
                procesarCargo(p, hoy);
            } catch (Exception e) {
                log.warn("Error con el cargo de la póliza {}: {}", p.get("id"), e.getMessage());
            }
        }
    }

    void procesarCargo(Map<String, Object> p, LocalDate hoy) {
        LocalDate cargo = CargoSeguro.leerFecha(p.get("proximo_cargo"));
        if (cargo == null) return;
        String formaPago = String.valueOf(p.get("forma_pago"));
        String uid = String.valueOf(p.get("user_id"));

        if (cargo.isBefore(hoy)) {
            // Ya pasó: el siguiente, contando desde el que tocaba
            LocalDate siguiente = CargoSeguro.proximoCargo(cargo, formaPago, hoy);
            supabase.update("seguros", String.valueOf(p.get("id")), Map.of("proximo_cargo", siguiente.toString()), uid);
            return;
        }

        long dias = ChronoUnit.DAYS.between(hoy, cargo);
        if (dias != 7 && dias != 1) return;

        Double importe = CargoSeguro.importePorCargo(p.get("prima_anual"), formaPago);
        String cuenta = AvisoCuentaBancariaService.textoCuenta(p.get("cuenta_cargo"));
        String titulo = dias == 1 ? "💳 Cargo de seguro mañana" : "💳 Cargo de seguro en 7 días";
        String mensaje = String.format("%s de %s: %s el %s%s%s",
                p.get("tipo"), p.get("aseguradora"),
                importe != null ? String.format(java.util.Locale.forLanguageTag("es-ES"), "%,.2f €", importe) : "importe sin indicar",
                cargo.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                cuenta != null ? " · cuenta " + cuenta : "",
                Boolean.TRUE.equals(p.get("domiciliado")) ? " (domiciliado)" : " (no domiciliado: hay que pagarlo)");
        notif.create(uid, titulo, mensaje, "/seguros/" + p.get("id"), dias == 1 ? "alta" : "media");
    }
}
