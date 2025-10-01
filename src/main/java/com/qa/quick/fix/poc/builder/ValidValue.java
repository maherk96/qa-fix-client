package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidValue {
    @JsonProperty("enum")
    private String enumValue;
    private String description;

    public ValidValue() {}

    public ValidValue(String enumValue, String description) {
        this.enumValue = enumValue;
        this.description = description;
    }

    public String getEnumValue() { return enumValue; }
    public void setEnumValue(String enumValue) { this.enumValue = enumValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}