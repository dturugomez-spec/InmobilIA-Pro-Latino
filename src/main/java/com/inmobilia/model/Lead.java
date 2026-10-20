package com.inmobilia.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Lead {
    private String id;
    private String user_id;
    private String nombre;
    private String email;
    private String telefono;
    private String origen;
    private String interes;
    private Double presupuesto;
    private String zona;
    private String tipo_activo;
    private String etapa;     // Captacion | Contactado | Visita | Oferta | Firmado | Perdido
    private Integer score;
    private String property_id;
    private String notas;
    private String last_contact;
    private String next_followup;
    private String created_at;
}
