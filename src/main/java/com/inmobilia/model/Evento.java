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
public class Evento {
    private String id;
    private String user_id;
    private String title;
    private String date;
    private String time;
    private String type;
    private String description;
    private String property_name;
    private String attendees;
    private String location;
    private Integer reminder_days;
    private String created_at;
}
