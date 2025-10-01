package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ComponentDefinition {
    private String name;
    private boolean required;
    private List<FieldDefinition> fields = new ArrayList<>();
    private List<GroupDefinition> groups = new ArrayList<>();
    // Preserve nested component structure instead of flattening
    private List<ComponentDefinition> components = new ArrayList<>();

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public List<FieldDefinition> getFields() { return fields; }
    public void setFields(List<FieldDefinition> fields) { this.fields = fields; }

    public List<GroupDefinition> getGroups() { return groups; }
    public void setGroups(List<GroupDefinition> groups) { this.groups = groups; }

    public List<ComponentDefinition> getComponents() { return components; }
    public void setComponents(List<ComponentDefinition> components) { this.components = components; }
}
