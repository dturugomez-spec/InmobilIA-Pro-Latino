package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** El chatbot público no puede vaciar los créditos de IA de una inmobiliaria. */
class ChatbotLimitesTest {

    private final SupabaseService supabase = mock(SupabaseService.class);
    private final AIService ai = mock(AIService.class);
    private final ChatbotService bot = new ChatbotService(supabase, ai, mock(NotificationService.class),
            mock(WorkflowEngine.class), new RateLimiter());

    private void conversacion(String id, String org) {
        when(supabase.selectFilter(eq("chatbot_conversaciones"), startsWith("id=eq." + id)))
                .thenReturn(List.of(Map.of("id", id, "user_id", org, "mensajes_total", 0)));
    }

    @Test
    void unaConversacion_noPasaDe25Mensajes() {
        conversacion("c1", "org-1");
        when(supabase.selectFilter(anyString(), anyString())).thenAnswer(inv ->
                inv.getArgument(1, String.class).startsWith("id=eq.c1")
                        ? List.of(Map.of("id", "c1", "user_id", "org-1", "mensajes_total", 0)) : List.of());
        when(ai.call(any(), anyString(), anyString(), anyInt(), anyString())).thenReturn("Hola, ¿en qué te ayudo?");

        for (int i = 0; i < ChatbotService.MAX_MENSAJES_CONVERSACION; i++) {
            assertEquals("Hola, ¿en qué te ayudo?", bot.procesar("c1", "hola").get("respuesta"));
        }
        Map<String, Object> pasado = bot.procesar("c1", "hola");
        assertEquals(ChatbotService.RESPUESTA_LIMITE_CONVERSACION, pasado.get("respuesta"));
        assertEquals(true, pasado.get("pedir_email"));
        verify(ai, times(ChatbotService.MAX_MENSAJES_CONVERSACION)).call(any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void unaInmobiliaria_noPasaDe150RespuestasDeIaPorHora() {
        when(supabase.selectFilter(anyString(), anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(1, String.class);
            if (inv.getArgument(0, String.class).equals("chatbot_conversaciones") && q.startsWith("id=eq.")) {
                String id = q.substring(6).split("&")[0];
                return List.of(Map.of("id", id, "user_id", "org-2", "mensajes_total", 0));
            }
            return List.of();
        });
        when(ai.call(any(), anyString(), anyString(), anyInt(), anyString())).thenReturn("respuesta IA");

        int conIa = 0;
        for (int i = 0; i < 200; i++) {   // 200 conversaciones distintas, un mensaje cada una
            if ("respuesta IA".equals(bot.procesar("conv-" + i, "hola").get("respuesta"))) conIa++;
        }
        assertEquals(ChatbotService.MAX_IA_POR_HORA_INMOBILIARIA, conIa);
        verify(ai, times(ChatbotService.MAX_IA_POR_HORA_INMOBILIARIA)).call(any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void unNumeroEnormeNoRompeElChat() {
        conversacion("c3", "org-3");
        when(supabase.selectFilter(anyString(), anyString())).thenAnswer(inv -> {
            String tabla = inv.getArgument(0, String.class);
            if ("chatbot_config".equals(tabla)) return List.of(Map.of("permitir_buscar_propiedades", true)); // pasa por el precio
            return inv.getArgument(1, String.class).startsWith("id=eq.c3")
                    ? List.of(Map.of("id", "c3", "user_id", "org-3", "mensajes_total", 0)) : List.of();
        });
        when(ai.call(any(), anyString(), anyString(), anyInt(), anyString())).thenReturn("ok");
        assertDoesNotThrow(() -> bot.procesar("c3", "busco piso por 123456789012345 euros"));
    }
}
