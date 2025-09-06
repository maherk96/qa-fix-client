package com.qa.quick.fix.poc.config;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Connection environment definition (EMEA, ALGO_UAT, etc.)
 */
@Data
@NoArgsConstructor
public  class ConnectionEnvironment {
    @JsonProperty("trade")
    private ConnectionDetails trade;
    
    @JsonProperty("quote")
    private ConnectionDetails quote;  // Optional for trade-only clients
}
