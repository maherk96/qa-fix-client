package com.qa.quick.fix.poc.poc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class FixLoadTaskDefinition {

    @JsonProperty("testSpec")
    private FixTestSpec testSpec;


    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FixTestSpec {
        @JsonProperty("id")
        private String id;

        @JsonProperty("globalConfig")
        private GlobalConfig globalConfig;

        @JsonProperty("scenarios")
        private List<Scenario> scenarios;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlobalConfig {
        @JsonProperty("configPath")
        private String configPath;

        @JsonProperty("portsConfigPath")
        private String portsConfigPath;

        @JsonProperty("environmentName")
        private String environmentName;

        @JsonProperty("clientStreamNames")
        private List<String> clientStreamNames;

        @JsonProperty("vars")
        private Map<String, Object> vars;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scenario {
        @JsonProperty("name")
        private String name;

        @JsonProperty("steps")
        private List<Request> requests;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Request {
        @JsonProperty("messageType")
        private FixMessageType messageType;

        @JsonProperty("fields")
        private Map<String, String> fields;

        @JsonProperty("expected")
        private ExpectedResponse expected;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExpectedResponse {
        @JsonProperty("messageType")
        private String messageType;

        @JsonProperty("fields")
        private Map<String, String> fields;

        @JsonProperty("maxTimeMs")
        private Integer maxTimeMs;
    }
}