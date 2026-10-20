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
public class Mantenimiento {
    private String id;
    private String user_id;
    private String title;
    private String description;
    private String property_name;
    private String priority;
    private String status;
    private String assigned_to;
    private Double cost;
    private String invoice_url;
    private String scheduled_date;
    private String completed_date;
    private String created_at;
    private String updated_at;
}
