package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** El texto de la IA llega en español correcto y sin caracteres que rompan un PDF. */
class AITextoTest {

    @Test
    void conservaTildesEnesYEuros() {
        assertEquals("¿Señor Núñez? Tasación: 250.000 € «año» — piso",
                AIService.limpiar("¿Señor Núñez? Tasación: 250.000 € «año» — piso"));
    }

    @Test
    void quitaEmojisYConvierteFlechas() {
        assertEquals("Visita -> oferta", AIService.limpiar("Visita → oferta 🏠"));
        assertEquals("a\n\nb", AIService.limpiar("a\n\n\n\nb"));
        assertEquals("", AIService.limpiar(null));
    }

    @Test
    void lasInstruccionesYaNoPidenQuitarTildes() {
        assertFalse(AIService.ENCODING_RULES.toLowerCase().contains("sin tildes"));
    }
}
