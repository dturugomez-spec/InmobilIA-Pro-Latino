package com.inmobilia.service;

import org.springframework.stereotype.Service;

/**
 * Future-Proof Score: Power Readiness + Robotics Readiness.
 * Port de _compute_power_readiness y _compute_robotics_readiness.
 */
@Service
public class FutureProofScoreService {

    public record ScoreResult(double powerReadiness, double roboticsReadiness,
                              double total, String rating) {}

    /**
     * Power Readiness 0-100: basada en potencia, ampliabilidad, trifasica, pre-instalacion VE.
     */
    public double powerReadiness(double potenciaKw, boolean ampliacion, boolean trifasica,
                                  boolean preVE, boolean placas, boolean bateria) {
        double s = 0;
        if (potenciaKw >= 15)      s += 30;
        else if (potenciaKw >= 10) s += 22;
        else if (potenciaKw >= 7)  s += 15;
        else if (potenciaKw >= 5)  s += 8;
        if (ampliacion) s += 10;
        if (trifasica)  s += 15;
        if (preVE)      s += 20;
        if (placas)     s += 15;
        if (bateria)    s += 10;
        return Math.min(100.0, s);
    }

    /**
     * Robotics Readiness 0-100: carga de suelo, altura libre, ancho de puertas, red, accesos.
     */
    public double roboticsReadiness(double cargaSuelo, double alturaLibre, double anchoPuerta,
                                     boolean wifiMesh, boolean ascensor, boolean sinEscalones) {
        double s = 0;
        if (cargaSuelo >= 400)      s += 25;
        else if (cargaSuelo >= 250) s += 18;
        else if (cargaSuelo >= 150) s += 10;
        if (alturaLibre >= 2.5)     s += 15;
        else if (alturaLibre >= 2.2) s += 8;
        if (anchoPuerta >= 0.90)    s += 15;
        else if (anchoPuerta >= 0.80) s += 8;
        if (wifiMesh)      s += 15;
        if (ascensor)      s += 15;
        if (sinEscalones)  s += 15;
        return Math.min(100.0, s);
    }

    public ScoreResult combine(double power, double robotics) {
        double total = (power + robotics) / 2.0;
        String rating;
        if      (total >= 85) rating = "Elite";
        else if (total >= 70) rating = "Advanced";
        else if (total >= 55) rating = "Ready";
        else if (total >= 35) rating = "In Progress";
        else                  rating = "Legacy";
        return new ScoreResult(power, robotics, total, rating);
    }
}
