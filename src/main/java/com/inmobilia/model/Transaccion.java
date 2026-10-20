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
public class Transaccion {
    private String id;
    private String user_id;
    private String type;         // Ingreso | Gasto
    private String category;
    private Double amount;
    private String date;
    private String property_name;
    private String description;
    private String invoice_number;
    private Double vat_pct;
    private Boolean deductible;
    private String created_at;
}
