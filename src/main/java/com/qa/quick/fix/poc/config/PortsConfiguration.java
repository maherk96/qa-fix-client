package com.qa.quick.fix.poc.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@NoArgsConstructor
public class PortsConfiguration {
    
    @JsonProperty("clients")
    private List<ClientPortInfo> clients;
}