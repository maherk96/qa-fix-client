package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class FieldDefinition {
    private String name;
    private Integer number;
    private String type;
    private boolean required;
    private String description;
    private String defaultValue;
    private List<ValidValue> validValues;

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getNumber() { return number; }
    public void setNumber(Integer number) { this.number = number; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public List<ValidValue> getValidValues() { return validValues; }
    public void setValidValues(List<ValidValue> validValues) { this.validValues = validValues; }
}
