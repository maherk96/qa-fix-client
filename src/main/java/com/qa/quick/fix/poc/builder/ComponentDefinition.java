// ============================================================================
// Model Classes for JSON Representation
// ============================================================================

package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComponentDefinition {
    private String name;
    private boolean required;
    private List<FieldDefinition> fields = new ArrayList<>();
    private List<GroupDefinition> groups = new ArrayList<>();

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public List<FieldDefinition> getFields() { return fields; }
    public void setFields(List<FieldDefinition> fields) { this.fields = fields; }

    public List<GroupDefinition> getGroups() { return groups; }
    public void setGroups(List<GroupDefinition> groups) { this.groups = groups; }
}
// ============================================================================
// FIX Specification Parser


// ============================================================================
// Maven pom.xml dependencies (add to your pom.xml)
// ============================================================================
/*
<dependencies>
    <!-- Jackson for JSON serialization -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.2</version>
    </dependency>
</dependencies>
*/