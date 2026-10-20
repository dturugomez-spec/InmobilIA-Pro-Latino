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
public class Contrato {
    private String id;
    private String user_id;
    private String property_name;
    private String tenant_name;
    private String start_date;
    private String end_date;
    private Double rent;
    private Double deposit;
    private Boolean ipc_revision;
    private String status;    // Activo | Pendiente | Finalizado | Rescindido
    private String terms;
    private String file_url;
    private String signed_date;
    private String created_at;
    private String updated_at;
}
