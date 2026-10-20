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
public class FutureProofScore {
    private String id;
    private String user_id;
    private String property_name;
    private Double power_readiness;
    private Double robotics_readiness;
    private Double score_total;
    private String rating;      // Legacy | In Progress | Ready | Advanced | Elite
    private String created_at;
}
