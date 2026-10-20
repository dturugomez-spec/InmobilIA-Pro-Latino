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
public class Oportunidad {
    private String id;
    private String user_id;
    private String property_name;
    private String address;
    private String city;
    private Double price;
    private Double cap_rate;
    private Double gross_yield;
    private Double area_m2;
    private String type;
    private String status;
    private String source;
    private String contact;
    private Double expected_irr;
    private String notes;
    private Integer priority;
    private String created_at;
    private String updated_at;
}
