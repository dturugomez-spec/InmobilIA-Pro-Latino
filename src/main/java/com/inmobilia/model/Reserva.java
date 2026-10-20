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
public class Reserva {
    private String id;
    private String user_id;
    private String client_name;
    private String client_phone;
    private String client_email;
    private String property_name;
    private String date;
    private String time;
    private String type;
    private String status;
    private String notes;
    private String agent_name;
    private String feedback;
    private String created_at;
}
