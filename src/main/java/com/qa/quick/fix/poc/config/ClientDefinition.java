package com.qa.quick.fix.poc.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public  class ClientDefinition {
    @JsonProperty("tradeSession")
    private SessionConfig tradeSession;
    
    @JsonProperty("quoteSession")
    private SessionConfig quoteSession;  // Optional for trade-only clients
    
    @JsonProperty("other")
    private OtherSettings other;
}