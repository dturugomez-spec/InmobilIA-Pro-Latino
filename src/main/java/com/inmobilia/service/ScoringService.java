package com.inmobilia.service;

import org.springframework.stereotype.Service;

/**
 * Scoring de inquilinos (equivalente a _score_candidate del Python).
 * Calcula una puntuacion 0-100 de solvencia en base a ingresos, trabajo, contrato, etc.
 */
@Service
public class ScoringService {

    public record ScoreOut(int score, String calificacion, String color) {}

    public ScoreOut scoreCandidate(String name, int age, String job, String contractType,
                                    double income, int yearsJob, double rentAmount,
                                    boolean hasGuarantor, boolean hasInsurance, int priorEvictions) {
        int pts = 50;
        // Ratio ingreso/alquiler
        double ratio = rentAmount > 0 ? income / rentAmount : 0;
        if (ratio >= 4)       pts += 25;
        else if (ratio >= 3)  pts += 18;
        else if (ratio >= 2.5) pts += 10;
        else if (ratio < 2)   pts -= 15;
        // Tipo de contrato
        if ("Indefinido".equalsIgnoreCase(contractType)) pts += 12;
        else if ("Funcionario".equalsIgnoreCase(contractType)) pts += 15;
        else if ("Temporal".equalsIgnoreCase(contractType)) pts -= 5;
        else if ("Autonomo".equalsIgnoreCase(contractType)) pts += 2;
        else if ("Desempleado".equalsIgnoreCase(contractType)) pts -= 25;
        // Antiguedad en el trabajo
        if (yearsJob >= 5)      pts += 10;
        else if (yearsJob >= 2) pts += 5;
        else if (yearsJob < 1)  pts -= 8;
        // Garantias adicionales
        if (hasGuarantor) pts += 6;
        if (hasInsurance) pts += 6;
        // Penalizaciones
        pts -= priorEvictions * 20;
        // Edad
        if (age >= 30 && age <= 55) pts += 4;

        int finalScore = Math.max(0, Math.min(100, pts));
        String cal;
        String color;
        if      (finalScore >= 80) { cal = "Excelente"; color = "#05CD99"; }
        else if (finalScore >= 65) { cal = "Bueno";     color = "#4361ee"; }
        else if (finalScore >= 50) { cal = "Aceptable"; color = "#FFB547"; }
        else                       { cal = "Riesgo";    color = "#EE5D50"; }
        return new ScoreOut(finalScore, cal, color);
    }
}
