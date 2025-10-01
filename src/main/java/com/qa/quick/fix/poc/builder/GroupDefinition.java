package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupDefinition {
    private String name;
    private Integer numInGroupTag;
    private boolean required;
    private List<FieldDefinition> fields = new ArrayList<>();
    private List<GroupDefinition> nestedGroups = new ArrayList<>();

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getNumInGroupTag() { return numInGroupTag; }
    public void setNumInGroupTag(Integer numInGroupTag) { this.numInGroupTag = numInGroupTag; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public List<FieldDefinition> getFields() { return fields; }
    public void setFields(List<FieldDefinition> fields) { this.fields = fields; }

    public List<GroupDefinition> getNestedGroups() { return nestedGroups; }
    public void setNestedGroups(List<GroupDefinition> nestedGroups) { this.nestedGroups = nestedGroups; }
}