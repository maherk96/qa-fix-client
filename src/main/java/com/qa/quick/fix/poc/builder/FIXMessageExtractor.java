package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FIXMessageExtractor {
    
    private Document doc;
    private Map<String, Element> fieldMap = new HashMap<>();
    private Map<String, Element> componentMap = new HashMap<>();
    private Map<String, Element> messageMap = new HashMap<>();
    
    /**
     * Load and parse FIX XML specification from file
     */
    public void loadSpecification(String xmlFilePath) throws Exception {
        loadSpecification(new File(xmlFilePath));
    }
    
    /**
     * Load and parse FIX XML specification from File object
     */
    public void loadSpecification(File xmlFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        doc = builder.parse(xmlFile);
        doc.getDocumentElement().normalize();
        
        buildIndexes();
    }
    
    /**
     * Load and parse FIX XML specification from InputStream
     */
    public void loadSpecification(InputStream xmlStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        doc = builder.parse(xmlStream);
        doc.getDocumentElement().normalize();
        
        buildIndexes();
    }
    
    /**
     * Build indexes for fast lookup of fields, components, and messages
     */
    private void buildIndexes() {
        // Index all field definitions from <fields> section
        Element fieldsRoot = (Element) doc.getElementsByTagName("fields").item(0);
        if (fieldsRoot != null) {
            NodeList fieldNodes = fieldsRoot.getChildNodes();
            for (int i = 0; i < fieldNodes.getLength(); i++) {
                Node node = fieldNodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "field".equals(node.getNodeName())) {
                    Element field = (Element) node;
                    String fieldName = field.getAttribute("name");
                    if (fieldName != null && !fieldName.isEmpty()) {
                        fieldMap.put(fieldName, field);
                    }
                }
            }
        }
        
        // Index all component definitions
        Element componentsRoot = (Element) doc.getElementsByTagName("components").item(0);
        if (componentsRoot != null) {
            NodeList componentNodes = componentsRoot.getChildNodes();
            for (int i = 0; i < componentNodes.getLength(); i++) {
                Node node = componentNodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "component".equals(node.getNodeName())) {
                    Element component = (Element) node;
                    String componentName = component.getAttribute("name");
                    if (componentName != null && !componentName.isEmpty()) {
                        componentMap.put(componentName, component);
                    }
                }
            }
        }
        
        // Index all message definitions
        Element messagesRoot = (Element) doc.getElementsByTagName("messages").item(0);
        if (messagesRoot != null) {
            NodeList messageNodes = messagesRoot.getChildNodes();
            for (int i = 0; i < messageNodes.getLength(); i++) {
                Node node = messageNodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "message".equals(node.getNodeName())) {
                    Element message = (Element) node;
                    String messageName = message.getAttribute("name");
                    if (messageName != null && !messageName.isEmpty()) {
                        messageMap.put(messageName, message);
                    }
                }
            }
        }
    }
    
    /**
     * Extract message definition for a given message name
     */
    public MessageDefinition extractMessage(String messageName) throws Exception {
        if (doc == null) {
            throw new IllegalStateException("No specification loaded. Call loadSpecification() first.");
        }
        
        Element messageElement = messageMap.get(messageName);
        if (messageElement == null) {
            throw new IllegalArgumentException("Message type '" + messageName + "' not found in specification");
        }
        
        MessageDefinition msgDef = new MessageDefinition();
        msgDef.setMessageName(messageName);
        msgDef.setMessageType(messageElement.getAttribute("msgtype"));
        
        // Process header fields
        processHeader(msgDef);
        
        // Process message body
        processMessageBody(messageElement, msgDef);
        
        // Process trailer fields
        processTrailer(msgDef);
        
        return msgDef;
    }
    
    /**
     * Convert MessageDefinition to JSON string
     */
    public String toJson(MessageDefinition msgDef) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(msgDef);
    }
    
    /**
     * Process header fields - FIXED to properly resolve field definitions
     */
    private void processHeader(MessageDefinition msgDef) {
        NodeList headerNodes = doc.getElementsByTagName("header");
        if (headerNodes.getLength() > 0) {
            Element header = (Element) headerNodes.item(0);
            NodeList headerFieldRefs = header.getChildNodes();
            
            for (int i = 0; i < headerFieldRefs.getLength(); i++) {
                Node node = headerFieldRefs.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "field".equals(node.getNodeName())) {
                    Element fieldRef = (Element) node;
                    String fieldName = fieldRef.getAttribute("name");
                    boolean required = "Y".equals(fieldRef.getAttribute("required"));
                    
                    Element fieldDef = fieldMap.get(fieldName);
                    if (fieldDef != null) {
                        FieldDefinition field = createFieldDefinition(fieldDef, required);
                        msgDef.getHeaderFields().add(field);
                    }
                }
            }
        }
    }
    
    /**
     * Process trailer fields - FIXED to properly resolve field definitions
     */
    private void processTrailer(MessageDefinition msgDef) {
        NodeList trailerNodes = doc.getElementsByTagName("trailer");
        if (trailerNodes.getLength() > 0) {
            Element trailer = (Element) trailerNodes.item(0);
            NodeList trailerFieldRefs = trailer.getChildNodes();
            
            for (int i = 0; i < trailerFieldRefs.getLength(); i++) {
                Node node = trailerFieldRefs.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE && "field".equals(node.getNodeName())) {
                    Element fieldRef = (Element) node;
                    String fieldName = fieldRef.getAttribute("name");
                    boolean required = "Y".equals(fieldRef.getAttribute("required"));
                    
                    Element fieldDef = fieldMap.get(fieldName);
                    if (fieldDef != null) {
                        FieldDefinition field = createFieldDefinition(fieldDef, required);
                        msgDef.getTrailerFields().add(field);
                    }
                }
            }
        }
    }
    
    /**
     * Process message body (fields, groups, components)
     */
    private void processMessageBody(Element messageElement, MessageDefinition msgDef) {
        NodeList children = messageElement.getChildNodes();
        
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            
            Element element = (Element) child;
            String tagName = element.getTagName();
            
            if ("field".equals(tagName)) {
                processFieldReference(element, msgDef);
            } else if ("group".equals(tagName)) {
                GroupDefinition group = processGroup(element);
                msgDef.getGroups().add(group);
            } else if ("component".equals(tagName)) {
                ComponentDefinition component = processComponent(element);
                msgDef.getComponents().add(component);
            }
        }
    }
    
    /**
     * Process a field reference in a message
     */
    private void processFieldReference(Element fieldRef, MessageDefinition msgDef) {
        String fieldName = fieldRef.getAttribute("name");
        boolean required = "Y".equals(fieldRef.getAttribute("required"));
        
        Element fieldDef = fieldMap.get(fieldName);
        if (fieldDef != null) {
            FieldDefinition field = createFieldDefinition(fieldDef, required);
            
            if (required) {
                msgDef.getRequiredFields().add(field);
            } else {
                msgDef.getOptionalFields().add(field);
            }
        }
    }
    
    /**
     * Process a repeating group - FIXED to handle nested groups properly
     */
    private GroupDefinition processGroup(Element groupElement) {
        GroupDefinition group = new GroupDefinition();
        String groupName = groupElement.getAttribute("name");
        boolean required = "Y".equals(groupElement.getAttribute("required"));
        
        group.setName(groupName);
        group.setRequired(required);
        
        // Get the numInGroup tag number
        Element numInGroupField = fieldMap.get(groupName);
        if (numInGroupField != null) {
            String tagNumber = numInGroupField.getAttribute("number");
            if (tagNumber != null && !tagNumber.isEmpty()) {
                group.setNumInGroupTag(Integer.parseInt(tagNumber));
            }
        }
        
        // Process group fields and nested groups
        NodeList children = groupElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            
            Element element = (Element) child;
            String tagName = element.getTagName();
            
            if ("field".equals(tagName)) {
                String fieldName = element.getAttribute("name");
                boolean fieldRequired = "Y".equals(element.getAttribute("required"));
                
                Element fieldDef = fieldMap.get(fieldName);
                if (fieldDef != null) {
                    FieldDefinition field = createFieldDefinition(fieldDef, fieldRequired);
                    group.getFields().add(field);
                }
            } else if ("group".equals(tagName)) {
                // Recursively handle nested groups
                GroupDefinition nestedGroup = processGroup(element);
                group.getNestedGroups().add(nestedGroup);
            }
        }
        
        return group;
    }
    
    /**
     * Process a component reference - FIXED to fully expand all fields and groups
     */
    private ComponentDefinition processComponent(Element componentRef) {
        ComponentDefinition component = new ComponentDefinition();
        String componentName = componentRef.getAttribute("name");
        boolean required = "Y".equals(componentRef.getAttribute("required"));
        
        component.setName(componentName);
        component.setRequired(required);
        
        // Find the component definition
        Element componentDef = componentMap.get(componentName);
        if (componentDef != null) {
            expandComponent(componentDef, component);
        }
        
        return component;
    }
    
    /**
     * Recursively expand a component definition - FIXED
     */
    private void expandComponent(Element componentDef, ComponentDefinition component) {
        NodeList children = componentDef.getChildNodes();
        
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            
            Element element = (Element) child;
            String tagName = element.getTagName();
            
            if ("field".equals(tagName)) {
                String fieldName = element.getAttribute("name");
                boolean fieldRequired = "Y".equals(element.getAttribute("required"));
                
                Element fieldDef = fieldMap.get(fieldName);
                if (fieldDef != null) {
                    FieldDefinition field = createFieldDefinition(fieldDef, fieldRequired);
                    component.getFields().add(field);
                }
            } else if ("group".equals(tagName)) {
                // Recursively process groups within components
                GroupDefinition group = processGroup(element);
                component.getGroups().add(group);
            } else if ("component".equals(tagName)) {
                // Handle nested components (recursively expand them)
                String nestedComponentName = element.getAttribute("name");
                Element nestedComponentDef = componentMap.get(nestedComponentName);
                if (nestedComponentDef != null) {
                    expandComponent(nestedComponentDef, component);
                }
            }
        }
    }
    
    /**
     * Create a FieldDefinition from a field element - FIXED to capture all attributes
     */
    private FieldDefinition createFieldDefinition(Element fieldElement, boolean required) {
        FieldDefinition field = new FieldDefinition();
        
        field.setName(fieldElement.getAttribute("name"));
        
        String number = fieldElement.getAttribute("number");
        if (number != null && !number.isEmpty()) {
            field.setNumber(Integer.parseInt(number));
        }
        
        String type = fieldElement.getAttribute("type");
        field.setType(type != null && !type.isEmpty() ? type : null);
        
        field.setRequired(required);
        
        String desc = fieldElement.getAttribute("desc");
        field.setDescription(desc != null && !desc.isEmpty() ? desc : null);
        
        // Check for default value attribute
        String defaultValue = fieldElement.getAttribute("default");
        if (defaultValue != null && !defaultValue.isEmpty()) {
            field.setDefaultValue(defaultValue);
        }
        
        // Process valid values (enums) - preserve order from XML
        NodeList valueNodes = fieldElement.getChildNodes();
        List<ValidValue> validValues = new ArrayList<>();
        
        for (int i = 0; i < valueNodes.getLength(); i++) {
            Node node = valueNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "value".equals(node.getNodeName())) {
                Element valueElement = (Element) node;
                String enumValue = valueElement.getAttribute("enum");
                String description = valueElement.getAttribute("description");
                
                if (enumValue != null && !enumValue.isEmpty()) {
                    validValues.add(new ValidValue(enumValue, description));
                }
            }
        }
        
        if (!validValues.isEmpty()) {
            field.setValidValues(validValues);
        }
        
        return field;
    }
    
    /**
     * Main method for testing
     */
    public static void main(String[] args) throws Exception {
        FIXMessageExtractor extractor = new FIXMessageExtractor();
        extractor.loadSpecification("FIX44.xml");
        MessageDefinition msgDef = extractor.extractMessage("QuoteRequest");
        String json = extractor.toJson(msgDef);
        System.out.println(json);
    }
}