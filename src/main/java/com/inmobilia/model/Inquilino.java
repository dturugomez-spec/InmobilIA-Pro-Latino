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
public class Inquilino {
    private String id;
    private String user_id;
    private String name;
    private String email;
    private String phone;
    private String id_number;
    private String nationality;
    private String property_name;
    private Double rent_amount;
    private Double deposit;
    private String lease_start;
    private String lease_end;
    private String status;          // Activo | Moroso | Inactivo | Saliente
    private String guarantor_name;
    private String guarantor_phone;
    private Boolean has_insurance;
    private Integer score;
    private String notes;
    private String created_at;
    private String updated_at;
}
