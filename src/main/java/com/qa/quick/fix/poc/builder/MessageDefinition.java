// ============================================================================
// Model Classes for JSON Representation
// ============================================================================

package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageDefinition {
    private String messageName;
    private String messageType;
    private List<FieldDefinition> requiredFields = new ArrayList<>();
    private List<FieldDefinition> optionalFields = new ArrayList<>();
    private List<GroupDefinition> groups = new ArrayList<>();
    private List<ComponentDefinition> components = new ArrayList<>();
    private List<FieldDefinition> headerFields = new ArrayList<>();
    private List<FieldDefinition> trailerFields = new ArrayList<>();

    // Getters and Setters
    public String getMessageName() { return messageName; }
    public void setMessageName(String messageName) { this.messageName = messageName; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public List<FieldDefinition> getRequiredFields() { return requiredFields; }
    public void setRequiredFields(List<FieldDefinition> requiredFields) { this.requiredFields = requiredFields; }

    public List<FieldDefinition> getOptionalFields() { return optionalFields; }
    public void setOptionalFields(List<FieldDefinition> optionalFields) { this.optionalFields = optionalFields; }

    public List<GroupDefinition> getGroups() { return groups; }
    public void setGroups(List<GroupDefinition> groups) { this.groups = groups; }

    public List<ComponentDefinition> getComponents() { return components; }
    public void setComponents(List<ComponentDefinition> components) { this.components = components; }

    public List<FieldDefinition> getHeaderFields() { return headerFields; }
    public void setHeaderFields(List<FieldDefinition> headerFields) { this.headerFields = headerFields; }

    public List<FieldDefinition> getTrailerFields() { return trailerFields; }
    public void setTrailerFields(List<FieldDefinition> trailerFields) { this.trailerFields = trailerFields; }
}






// ============================================================================
// FIX Specification Parser (FIXED VERSION)
// ============================================================================


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