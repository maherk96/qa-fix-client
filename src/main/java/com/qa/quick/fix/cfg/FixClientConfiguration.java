package com.qa.quick.fix.cfg;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@NoArgsConstructor
public class FixClientConfiguration {

    @JsonProperty("common")
    private CommonSettings common;

    @JsonProperty("connections")
    private Map<String, ConnectionEnvironment> connections;

    @JsonProperty("clients")
    private Map<String, ClientDefinition> clients;
}