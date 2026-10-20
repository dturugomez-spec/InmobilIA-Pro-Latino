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
public class Propiedad {
    private String id;
    private String user_id;
    private String name;
    private String address;
    private String city;
    private String postal_code;
    private String type;        // Residencial | Comercial | Industrial | Oficinas | Local | Nave | Garaje | Terreno
    private String status;      // Ocupado | Libre | En Reforma | Reservado
    private Double bedrooms;
    private Double bathrooms;
    private Double area_m2;
    private String floor;
    private Integer year_built;
    private Double purchase_price;
    private String purchase_date;
    private Double current_value;
    private Double rental_income;
    private Double monthly_expenses;
    private Double mortgage_payment;
    private String notes;
    private String created_at;
    private String updated_at;
}
