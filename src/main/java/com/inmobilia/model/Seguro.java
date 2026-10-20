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
public class Seguro {
    private String id;
    private String user_id;
    private String type;
    private String company;
    private String policy_number;
    private String property_name;
    private Double annual_cost;
    private Double coverage;
    private String start_date;
    private String expiry_date;
    private Boolean auto_renew;
    private String contact_phone;
    private String notes;
    private String created_at;
}
