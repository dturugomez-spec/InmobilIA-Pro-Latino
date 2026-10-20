package com.inmobilia.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Analisis guardados en Supabase: DCF, Yield, Hipoteca, Monte Carlo, Stress, Valoracion, etc.
 * Los campos parametros/resultados se guardan como JSON string.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalisisFinanciero {
    private String id;
    private String user_id;
    private String tipo;
    private String nombre;
    private Object parametros;
    private Object resultados;
    private String notas;
    private String created_at;
    private String updated_at;
}
