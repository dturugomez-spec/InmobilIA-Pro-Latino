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
public class Tarea {
    private String id;
    private String user_id;
    private String title;
    private String description;
    private String priority;    // Alta | Media | Baja | Urgente
    private String assigned_to;
    private String due_date;
    private String status;      // Pendiente | En Progreso | Completada | Cancelada
    private String property_name;
    private String created_at;
    private String updated_at;
}
