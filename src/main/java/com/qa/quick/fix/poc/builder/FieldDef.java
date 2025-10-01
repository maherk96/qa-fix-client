package com.qa.quick.fix.poc.builder;// Dependencies:
// Maven:
// <dependency>
//     <groupId>org.projectlombok</groupId>
//     <artifactId>lombok</artifactId>
//     <version>1.18.30</version>
//     <scope>provided</scope>
// </dependency>

import lombok.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// ==================== Model Classes ====================

@Data
@Builder
@AllArgsConstructor
class FieldDef {
    private final int number;
    private final String name;
    private final String type;
    private final String description;
    private final boolean required;

    @Builder.Default
    private final Map<String, String> enumValues = new HashMap<>();

    public void addEnumValue(String enumValue, String description) {
        enumValues.put(enumValue, description);
    }
}

@Data
@Builder
class GroupDef {
    private final String name;
    private final int numInGroupTag;
    private final boolean required;

    @Builder.Default
    private final List<Object> members = new ArrayList<>(); // Can be FieldDef or nested GroupDef
}

@Data
@Builder
@AllArgsConstructor
class ComponentDef {
    private final String name;
    private final boolean required;

    @Builder.Default
    private final List<Object> members = new ArrayList<>(); // Can be FieldDef or GroupDef

    public void addMember(Object member) {
        members.add(member);
    }
}

@Data
@Builder
@AllArgsConstructor
class MessageDef {
    private final String name;
    private final String msgType;
    private final String category;

    @Builder.Default
    private final List<Object> members = new ArrayList<>(); // Can be FieldDef, ComponentDef, or GroupDef

    public void addMember(Object member) {
        members.add(member);
    }
}

// ==================== FIX Dictionary ====================

@Getter
class FixDictionary {
    private final Map<Integer, FieldDef> fieldsByNumber = new HashMap<>();
    private final Map<String, FieldDef> fieldsByName = new HashMap<>();
    private final Map<String, MessageDef> messagesByType = new HashMap<>();
    private final Map<String, MessageDef> messagesByName = new HashMap<>();
    private final Map<String, ComponentDef> components = new HashMap<>();
    private final List<Object> headerMembers = new ArrayList<>();
    private final List<FieldDef> trailerFields = new ArrayList<>();

    private String beginString = "FIX.4.4";
    private String fixVersion = "4.4";

    public void loadFromXml(String xmlFilePath) throws Exception {
        loadFromXml(new FileInputStream(xmlFilePath));
    }

    public void loadFromXml(InputStream xmlStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlStream);
        doc.getDocumentElement().normalize();

        // Get version info
        Element root = doc.getDocumentElement();
        String major = root.getAttribute("major");
        String minor = root.getAttribute("minor");
        fixVersion = major + "." + minor;
        beginString = "FIX." + fixVersion;

        parseFieldDefinitions(doc);
        parseComponents(doc);
        parseHeaderFields(doc);
        parseMessages(doc);
        parseTrailerFields(doc);
    }

    private void parseFieldDefinitions(Document doc) {
        NodeList fieldNodes = doc.getElementsByTagName("field");
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Element fieldElement = (Element) fieldNodes.item(i);
            if (fieldElement.getParentNode().getNodeName().equals("fields")) {
                FieldDef field = parseFieldDefinition(fieldElement);
                fieldsByNumber.put(field.getNumber(), field);
                fieldsByName.put(field.getName(), field);
            }
        }
    }

    private FieldDef parseFieldDefinition(Element element) {
        int number = Integer.parseInt(element.getAttribute("number"));
        String name = element.getAttribute("name");
        String type = element.getAttribute("type");
        String desc = element.getAttribute("desc");

        FieldDef field = FieldDef.builder()
                .number(number)
                .name(name)
                .type(type)
                .description(desc)
                .required(false)
                .build();

        NodeList valueNodes = element.getElementsByTagName("value");
        for (int i = 0; i < valueNodes.getLength(); i++) {
            Element valueElement = (Element) valueNodes.item(i);
            String enumValue = valueElement.getAttribute("enum");
            String description = valueElement.getAttribute("description");
            field.addEnumValue(enumValue, description);
        }

        return field;
    }

    private void parseComponents(Document doc) {
        NodeList componentNodes = doc.getElementsByTagName("component");
        for (int i = 0; i < componentNodes.getLength(); i++) {
            Element componentElement = (Element) componentNodes.item(i);
            if (componentElement.getParentNode().getNodeName().equals("components")) {
                ComponentDef component = parseComponent(componentElement, false);
                components.put(component.getName(), component);
            }
        }
    }

    private ComponentDef parseComponent(Element element, boolean required) {
        String name = element.getAttribute("name");
        ComponentDef component = ComponentDef.builder()
                .name(name)
                .required(required)
                .build();

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) children.item(i);
                parseMessageMember(child, component.getMembers());
            }
        }

        return component;
    }

    private void parseHeaderFields(Document doc) {
        NodeList headerNodes = doc.getElementsByTagName("header");
        if (headerNodes.getLength() > 0) {
            Element headerElement = (Element) headerNodes.item(0);
            NodeList children = headerElement.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    Element child = (Element) children.item(i);
                    parseMessageMember(child, headerMembers);
                }
            }
        }
    }

    private void parseTrailerFields(Document doc) {
        NodeList trailerNodes = doc.getElementsByTagName("trailer");
        if (trailerNodes.getLength() > 0) {
            Element trailerElement = (Element) trailerNodes.item(0);
            NodeList children = trailerElement.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    Element child = (Element) children.item(i);
                    String fieldName = child.getAttribute("name");
                    boolean req = "Y".equals(child.getAttribute("required"));
                    FieldDef fieldDef = fieldsByName.get(fieldName);
                    if (fieldDef != null) {
                        trailerFields.add(createFieldInstance(fieldDef, req));
                    }
                }
            }
        }
    }

    private void parseMessages(Document doc) {
        NodeList messageNodes = doc.getElementsByTagName("message");
        for (int i = 0; i < messageNodes.getLength(); i++) {
            Element messageElement = (Element) messageNodes.item(i);
            MessageDef message = parseMessage(messageElement);
            messagesByType.put(message.getMsgType(), message);
            messagesByName.put(message.getName(), message);
        }
    }

    private MessageDef parseMessage(Element element) {
        String name = element.getAttribute("name");
        String msgType = element.getAttribute("msgtype");
        String category = element.getAttribute("msgcat");

        MessageDef message = MessageDef.builder()
                .name(name)
                .msgType(msgType)
                .category(category)
                .build();

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) children.item(i);
                parseMessageMember(child, message.getMembers());
            }
        }

        return message;
    }

    private void parseMessageMember(Element element, List<Object> members) {
        String nodeName = element.getNodeName();
        boolean required = "Y".equals(element.getAttribute("required"));

        switch (nodeName) {
            case "field":
                String fieldName = element.getAttribute("name");
                FieldDef fieldDef = fieldsByName.get(fieldName);
                if (fieldDef != null) {
                    members.add(createFieldInstance(fieldDef, required));
                }
                break;

            case "component":
                String componentName = element.getAttribute("name");
                ComponentDef componentDef = components.get(componentName);
                if (componentDef != null) {
                    // Create a copy with the required flag from this context
                    ComponentDef instanceComponent = ComponentDef.builder()
                            .name(componentDef.getName())
                            .required(required)
                            .members(new ArrayList<>(componentDef.getMembers()))
                            .build();
                    members.add(instanceComponent);
                }
                break;

            case "group":
                String groupName = element.getAttribute("name");
                FieldDef numInGroupField = fieldsByName.get(groupName);
                if (numInGroupField != null) {
                    GroupDef group = GroupDef.builder()
                            .name(groupName)
                            .numInGroupTag(numInGroupField.getNumber())
                            .required(required)
                            .build();

                    NodeList groupChildren = element.getChildNodes();
                    for (int i = 0; i < groupChildren.getLength(); i++) {
                        if (groupChildren.item(i).getNodeType() == Node.ELEMENT_NODE) {
                            Element groupChild = (Element) groupChildren.item(i);
                            parseMessageMember(groupChild, group.getMembers());
                        }
                    }
                    members.add(group);
                }
                break;
        }
    }

    private FieldDef createFieldInstance(FieldDef original, boolean required) {
        return FieldDef.builder()
                .number(original.getNumber())
                .name(original.getName())
                .type(original.getType())
                .description(original.getDescription())
                .required(required)
                .enumValues(original.getEnumValues())
                .build();
    }

    public FieldDef getFieldByNumber(int number) {
        return fieldsByNumber.get(number);
    }

    public FieldDef getFieldByName(String name) {
        return fieldsByName.get(name);
    }

    public MessageDef getMessageByType(String msgType) {
        return messagesByType.get(msgType);
    }

    public MessageDef getMessageByName(String name) {
        return messagesByName.get(name);
    }

    public ComponentDef getComponent(String name) {
        return components.get(name);
    }

    public Collection<MessageDef> getAllMessages() {
        return messagesByName.values();
    }

    public Collection<FieldDef> getAllFields() {
        return fieldsByName.values();
    }
}

// ==================== FIX Message ====================

@Getter
class FixMessage {
    private final FixDictionary dictionary;
    private final MessageDef definition;
    private final Map<Integer, String> fields = new LinkedHashMap<>();
    private final Map<String, List<Map<Integer, String>>> groups = new HashMap<>();

    public FixMessage(FixDictionary dictionary, String messageType) {
        this.dictionary = dictionary;
        this.definition = dictionary.getMessageByName(messageType);

        if (definition == null) {
            throw new IllegalArgumentException("Unknown message type: " + messageType);
        }
    }

    public FixMessage setField(String fieldName, String value) {
        FieldDef fieldDef = dictionary.getFieldByName(fieldName);
        if (fieldDef == null) {
            throw new IllegalArgumentException("Unknown field: " + fieldName);
        }
        fields.put(fieldDef.getNumber(), value);
        return this;
    }

    public FixMessage setField(int fieldNumber, String value) {
        fields.put(fieldNumber, value);
        return this;
    }

    public FixMessage addGroupInstance(String groupName, Map<String, String> groupFields) {
        groups.computeIfAbsent(groupName, k -> new ArrayList<>());

        Map<Integer, String> numericFields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : groupFields.entrySet()) {
            FieldDef fieldDef = dictionary.getFieldByName(entry.getKey());
            if (fieldDef != null) {
                numericFields.put(fieldDef.getNumber(), entry.getValue());
            }
        }
        groups.get(groupName).add(numericFields);
        return this;
    }

    public String getField(String fieldName) {
        FieldDef fieldDef = dictionary.getFieldByName(fieldName);
        return fieldDef != null ? fields.get(fieldDef.getNumber()) : null;
    }

    public String getField(int fieldNumber) {
        return fields.get(fieldNumber);
    }

    public String toFixString() {
        StringBuilder body = new StringBuilder();

        // Add MsgType
        body.append("35=").append(definition.getMsgType()).append("\u0001");

        // Add body fields and groups
        addMembersToString(body, definition.getMembers());

        // Build header
        StringBuilder header = new StringBuilder();
        header.append("8=").append(dictionary.getBeginString()).append("\u0001");

        // Calculate body length (excluding BeginString, BodyLength, and CheckSum)
        int bodyLength = body.length();

        // Add header fields
        for (Object member : dictionary.getHeaderMembers()) {
            if (member instanceof FieldDef) {
                FieldDef field = (FieldDef) member;
                if (field.getNumber() != 8 && field.getNumber() != 9) { // Skip BeginString and BodyLength
                    String value = fields.get(field.getNumber());
                    if (value != null) {
                        String fieldStr = field.getNumber() + "=" + value + "\u0001";
                        header.append(fieldStr);
                        bodyLength += fieldStr.length();
                    }
                }
            }
        }

        // Add BodyLength
        String bodyLengthStr = "9=" + bodyLength + "\u0001";
        header.append(bodyLengthStr);

        // Combine header and body
        String message = header.toString() + body.toString();

        // Calculate checksum
        int checksum = calculateChecksum(message);
        String checksumStr = String.format("10=%03d\u0001", checksum);

        return message + checksumStr;
    }

    private void addMembersToString(StringBuilder sb, List<Object> members) {
        for (Object member : members) {
            if (member instanceof FieldDef) {
                FieldDef field = (FieldDef) member;
                String value = fields.get(field.getNumber());
                if (value != null) {
                    sb.append(field.getNumber()).append("=").append(value).append("\u0001");
                }
            } else if (member instanceof ComponentDef) {
                ComponentDef component = (ComponentDef) member;
                addMembersToString(sb, component.getMembers());
            } else if (member instanceof GroupDef) {
                GroupDef group = (GroupDef) member;
                List<Map<Integer, String>> groupInstances = groups.get(group.getName());
                if (groupInstances != null && !groupInstances.isEmpty()) {
                    // Add the count field
                    sb.append(group.getNumInGroupTag()).append("=").append(groupInstances.size()).append("\u0001");

                    // Add each group instance
                    for (Map<Integer, String> instance : groupInstances) {
                        for (Object groupMember : group.getMembers()) {
                            if (groupMember instanceof FieldDef) {
                                FieldDef field = (FieldDef) groupMember;
                                String value = instance.get(field.getNumber());
                                if (value != null) {
                                    sb.append(field.getNumber()).append("=").append(value).append("\u0001");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private int calculateChecksum(String message) {
        int sum = 0;
        for (char c : message.toCharArray()) {
            sum += c;
        }
        return sum % 256;
    }

    @Data
    @Builder
    public static class ValidationResult {
        private final boolean valid;

        @Builder.Default
        private final List<String> errors = new ArrayList<>();

        @Builder.Default
        private final List<String> warnings = new ArrayList<>();
    }

    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate header
        validateMembers(dictionary.getHeaderMembers(), fields, errors, warnings, "Header");

        // Validate body
        validateMembers(definition.getMembers(), fields, errors, warnings, "Body");

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    private void validateMembers(List<Object> members, Map<Integer, String> fieldMap,
                                 List<String> errors, List<String> warnings, String context) {
        for (Object member : members) {
            if (member instanceof FieldDef) {
                FieldDef field = (FieldDef) member;
                String value = fieldMap.get(field.getNumber());

                if (field.isRequired() && value == null) {
                    errors.add(context + ": Missing required field: " + field.getName() +
                            " (" + field.getNumber() + ")");
                }

                if (value != null && !field.getEnumValues().isEmpty()) {
                    if (!field.getEnumValues().containsKey(value)) {
                        warnings.add(context + ": Invalid enum value for " + field.getName() +
                                ": '" + value + "'. Valid values: " + field.getEnumValues().keySet());
                    }
                }
            } else if (member instanceof ComponentDef) {
                ComponentDef component = (ComponentDef) member;
                validateMembers(component.getMembers(), fieldMap, errors, warnings,
                        context + "/" + component.getName());
            } else if (member instanceof GroupDef) {
                GroupDef group = (GroupDef) member;
                List<Map<Integer, String>> groupInstances = groups.get(group.getName());

                if (group.isRequired() && (groupInstances == null || groupInstances.isEmpty())) {
                    errors.add(context + ": Missing required group: " + group.getName());
                }

                if (groupInstances != null) {
                    for (int i = 0; i < groupInstances.size(); i++) {
                        validateMembers(group.getMembers(), groupInstances.get(i), errors, warnings,
                                context + "/" + group.getName() + "[" + i + "]");
                    }
                }
            }
        }
    }
}

// ==================== Message Template ====================

@Data
@Builder
class MessageTemplate {
    private final String messageName;
    private final String messageType;

    @Builder.Default
    private final List<FieldTemplate> requiredFields = new ArrayList<>();

    @Builder.Default
    private final List<FieldTemplate> optionalFields = new ArrayList<>();

    @Builder.Default
    private final List<GroupTemplate> groups = new ArrayList<>();

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"messageName\": \"").append(messageName).append("\",\n");
        json.append("  \"messageType\": \"").append(messageType).append("\",\n");
        json.append("  \"requiredFields\": [\n");

        for (int i = 0; i < requiredFields.size(); i++) {
            json.append("    ").append(requiredFields.get(i).toJson());
            if (i < requiredFields.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ],\n");
        json.append("  \"optionalFields\": [\n");

        for (int i = 0; i < optionalFields.size(); i++) {
            json.append("    ").append(optionalFields.get(i).toJson());
            if (i < optionalFields.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ]");

        if (!groups.isEmpty()) {
            json.append(",\n  \"groups\": [\n");
            for (int i = 0; i < groups.size(); i++) {
                json.append("    ").append(groups.get(i).toJson());
                if (i < groups.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  ]");
        }

        json.append("\n}");
        return json.toString();
    }
}

@Data
@Builder
class FieldTemplate {
    private final String name;
    private final int number;
    private final String type;
    private final boolean required;
    private final String description;

    @Builder.Default
    private final Map<String, String> validValues = new HashMap<>();

    private final String defaultValue;

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"name\": \"").append(name).append("\", ");
        json.append("\"number\": ").append(number).append(", ");
        json.append("\"type\": \"").append(type).append("\", ");
        json.append("\"required\": ").append(required).append(", ");
        json.append("\"description\": \"").append(description != null ? description : "").append("\"");

        if (defaultValue != null) {
            json.append(", \"defaultValue\": \"").append(defaultValue).append("\"");
        }

        if (!validValues.isEmpty()) {
            json.append(", \"validValues\": {");
            int i = 0;
            for (Map.Entry<String, String> entry : validValues.entrySet()) {
                json.append("\"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append("\"");
                if (++i < validValues.size()) json.append(", ");
            }
            json.append("}");
        }

        json.append("}");
        return json.toString();
    }
}

@Data
@Builder
class GroupTemplate {
    private final String name;
    private final int numInGroupTag;
    private final boolean required;

    @Builder.Default
    private final List<FieldTemplate> fields = new ArrayList<>();

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("      \"name\": \"").append(name).append("\",\n");
        json.append("      \"numInGroupTag\": ").append(numInGroupTag).append(",\n");
        json.append("      \"required\": ").append(required).append(",\n");
        json.append("      \"fields\": [\n");

        for (int i = 0; i < fields.size(); i++) {
            json.append("        ").append(fields.get(i).toJson());
            if (i < fields.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("      ]\n");
        json.append("    }");
        return json.toString();
    }
}

// ==================== Message Builder ====================

class FixMessageBuilder {
    private final FixDictionary dictionary;
    private final FixMessage message;
    private final SimpleDateFormat utcFormat;
    private int msgSeqNum = 1;

    public FixMessageBuilder(FixDictionary dictionary, String messageType) {
        this.dictionary = dictionary;
        this.message = new FixMessage(dictionary, messageType);
        this.utcFormat = new SimpleDateFormat("yyyyMMdd-HH:mm:ss");
        this.utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Initialize message with sensible defaults for all required fields
     */
    public FixMessageBuilder withDefaults(String senderCompId, String targetCompId) {
        // Set header defaults
        message.setField("SenderCompID", senderCompId);
        message.setField("TargetCompID", targetCompId);
        message.setField("MsgSeqNum", String.valueOf(msgSeqNum++));
        message.setField("SendingTime", utcFormat.format(new Date()));

        // Set message-specific defaults
        setMessageDefaults();

        return this;
    }

    private void setMessageDefaults() {
        String msgName = message.getDefinition().getName();

        switch (msgName) {
            case "NewOrderSingle":
                setIfNotSet("ClOrdID", "ORD" + System.currentTimeMillis());
                setIfNotSet("Account", "DEFAULT_ACCOUNT");
                setIfNotSet("HandlInst", "1"); // Automated execution
                setIfNotSet("Symbol", "SYMBOL");
                setIfNotSet("Side", "1"); // Buy
                setIfNotSet("TransactTime", utcFormat.format(new Date()));
                setIfNotSet("OrderQty", "100");
                setIfNotSet("OrdType", "2"); // Limit
                setIfNotSet("TimeInForce", "0"); // Day
                setIfNotSet("QuoteID", "QUOTE" + System.currentTimeMillis());
                break;

            case "QuoteRequest":
                setIfNotSet("QuoteReqID", "QREQ" + System.currentTimeMillis());
                break;

            case "QuoteCancel":
                setIfNotSet("QuoteID", "QUOTE" + System.currentTimeMillis());
                setIfNotSet("QuoteCancelType", "4"); // Cancel All
                break;
        }
    }

    private void setIfNotSet(String fieldName, String value) {
        if (message.getField(fieldName) == null) {
            message.setField(fieldName, value);
        }
    }

    /**
     * Get a template showing all required and optional fields
     */
    public MessageTemplate getTemplate() {
        MessageTemplate.MessageTemplateBuilder builder = MessageTemplate.builder()
                .messageName(message.getDefinition().getName())
                .messageType(message.getDefinition().getMsgType());

        // Add header fields
        for (Object member : dictionary.getHeaderMembers()) {
            if (member instanceof FieldDef) {
                FieldDef field = (FieldDef) member;
                FieldTemplate template = createFieldTemplate(field);
                if (field.isRequired()) {
                    builder.requiredFields(new ArrayList<>());
                }
            }
        }

        // Add message fields
        addMembersToTemplate(message.getDefinition().getMembers(), builder);

        return builder.build();
    }

    private void addMembersToTemplate(List<Object> members, MessageTemplate.MessageTemplateBuilder builder) {
        for (Object member : members) {
            if (member instanceof FieldDef) {
                FieldDef field = (FieldDef) member;
                FieldTemplate template = createFieldTemplate(field);

                if (field.isRequired()) {
                    if (builder.build().getRequiredFields().isEmpty()) {
                        builder.requiredFields(new ArrayList<>());
                    }
                    builder.build().getRequiredFields().add(template);
                } else {
                    if (builder.build().getOptionalFields().isEmpty()) {
                        builder.optionalFields(new ArrayList<>());
                    }
                    builder.build().getOptionalFields().add(template);
                }
            } else if (member instanceof ComponentDef) {
                ComponentDef component = (ComponentDef) member;
                addMembersToTemplate(component.getMembers(), builder);
            } else if (member instanceof GroupDef) {
                GroupDef group = (GroupDef) member;
                GroupTemplate groupTemplate = createGroupTemplate(group);
                if (builder.build().getGroups().isEmpty()) {
                    builder.groups(new ArrayList<>());
                }
                builder.build().getGroups().add(groupTemplate);
            }
        }
    }

    private FieldTemplate createFieldTemplate(FieldDef field) {
        String defaultValue = getDefaultForField(field);

        return FieldTemplate.builder()
                .name(field.getName())
                .number(field.getNumber())
                .type(field.getType())
                .required(field.isRequired())
                .description(field.getDescription())
                .validValues(field.getEnumValues())
                .defaultValue(defaultValue)
                .build();
    }

    private GroupTemplate createGroupTemplate(GroupDef group) {
        GroupTemplate.GroupTemplateBuilder builder = GroupTemplate.builder()
                .name(group.getName())
                .numInGroupTag(group.getNumInGroupTag())
                .required(group.isRequired());

        List<FieldTemplate> fieldTemplates = new ArrayList<>();
        for (Object member : group.getMembers()) {
            if (member instanceof FieldDef) {
                fieldTemplates.add(createFieldTemplate((FieldDef) member));
            }
        }
        builder.fields(fieldTemplates);

        return builder.build();
    }

    private String getDefaultForField(FieldDef field) {
        switch (field.getType()) {
            case "UTCTIMESTAMP":
                return utcFormat.format(new Date());
            case "SEQNUM":
                return "1";
            case "QTY":
                return "100";
            case "PRICE":
                return "0.00";
            case "STRING":
                if (field.getName().contains("ID")) {
                    return field.getName().toUpperCase() + "_" + System.currentTimeMillis();
                }
                return "DEFAULT_" + field.getName().toUpperCase();
            case "CHAR":
                if (!field.getEnumValues().isEmpty()) {
                    return field.getEnumValues().keySet().iterator().next();
                }
                return "1";
            case "INT":
                if (!field.getEnumValues().isEmpty()) {
                    return field.getEnumValues().keySet().iterator().next();
                }
                return "1";
            default:
                return null;
        }
    }

    /**
     * Get JSON template of required fields
     */
    public String getRequiredFieldsJson() {
        return getTemplate().toJson();
    }

    /**
     * Get list of missing required fields
     */
    public List<String> getMissingRequiredFields() {
        List<String> missing = new ArrayList<>();
        FixMessage.ValidationResult validation = message.validate();

        for (String error : validation.getErrors()) {
            if (error.contains("Missing required")) {
                missing.add(error);
            }
        }

        return missing;
    }

    public FixMessageBuilder set(String fieldName, String value) {
        message.setField(fieldName, value);
        return this;
    }

    public FixMessageBuilder set(int fieldNumber, String value) {
        message.setField(fieldNumber, value);
        return this;
    }

    public FixMessageBuilder setCurrentTime(String fieldName) {
        message.setField(fieldName, utcFormat.format(new Date()));
        return this;
    }

    public FixMessageBuilder addGroup(String groupName, Map<String, String> groupFields) {
        message.addGroupInstance(groupName, groupFields);
        return this;
    }

    public FixMessage build() {
        FixMessage.ValidationResult validation = message.validate();
        if (!validation.isValid()) {
            throw new IllegalStateException("Invalid message:\n" +
                    String.join("\n", validation.getErrors()));
        }
        return message;
    }

    public FixMessage buildUnchecked() {
        return message;
    }
}

// ==================== Usage Examples ====================

class FixLibraryDemo {
    public static void main(String[] args) throws Exception {
        // Load dictionary from the provided XML
        FixDictionary dictionary = new FixDictionary();
        // Assuming the XML content is saved in a file or passed as a stream
        dictionary.loadFromXml("src/main/resources/fix44_spec.xml");

        FixMessageBuilder builder = new FixMessageBuilder(dictionary, "QuoteRequest");
        String json = builder.getRequiredFieldsJson();
        System.out.println(json);
    }

    private static void getMessageTemplate(FixDictionary dictionary) {
        FixMessageBuilder builder = new FixMessageBuilder(dictionary, "NewOrderSingle");

        // Get JSON template showing all required and optional fields
        String template = builder.getRequiredFieldsJson();
        System.out.println("Message Template JSON:");
        System.out.println(template);
    }

    private static void createNewOrderWithDefaults(FixDictionary dictionary) {
        // Create message with auto-populated defaults for required fields
        FixMessage order = new FixMessageBuilder(dictionary, "NewOrderSingle")
                .withDefaults("SENDER_FIRM", "TARGET_FIRM")
                // Only override the fields you care about
                .set("Symbol", "AAPL")
                .set("Side", "1")  // Buy
                .set("Price", "175.50")
                .set("OrderQty", "500")
                .buildUnchecked();

        System.out.println("FIX Message with Auto-Defaults (readable):");
        System.out.println(order.toFixString().replace("\u0001", "|"));

        System.out.println("\nField Values:");
        System.out.println("  Symbol: " + order.getField("Symbol"));
        System.out.println("  ClOrdID (auto-generated): " + order.getField("ClOrdID"));
        System.out.println("  SendingTime (auto-generated): " + order.getField("SendingTime"));

        System.out.println("\nValidation:");
        FixMessage.ValidationResult validation = order.validate();
        System.out.println("Valid: " + validation.isValid());
    }

    private static void checkMissingFields(FixDictionary dictionary) {
        // Create message without setting all required fields
        FixMessageBuilder builder = new FixMessageBuilder(dictionary, "NewOrderSingle")
                .set("SenderCompID", "SENDER")
                .set("Symbol", "MSFT");

        // Check what's missing before building
        List<String> missing = builder.getMissingRequiredFields();

        System.out.println("Missing Required Fields:");
        if (missing.isEmpty()) {
            System.out.println("  None - message is complete!");
        } else {
            missing.forEach(m -> System.out.println("  - " + m));
        }

        // Get the template to see what needs to be filled
        MessageTemplate template = builder.getTemplate();
        System.out.println("\nRequired Fields from Template:");
        template.getRequiredFields().forEach(f ->
                System.out.println("  - " + f.getName() + " (" + f.getNumber() + "): " +
                        f.getType() + (f.getDefaultValue() != null ? " [default: " + f.getDefaultValue() + "]" : ""))
        );
    }

    private static void createNewOrderSingle(FixDictionary dictionary) {
        FixMessage order = new FixMessageBuilder(dictionary, "NewOrderSingle")
                .set("SenderCompID", "SENDER")
                .set("TargetCompID", "TARGET")
                .set("MsgSeqNum", "1")
                .setCurrentTime("SendingTime")
                .set("ClOrdID", "ORDER123456")
                .set("Account", "ACC123")
                .set("HandlInst", "1")  // Automated execution
                .set("Symbol", "AAPL")
                .set("Side", "1")  // Buy
                .setCurrentTime("TransactTime")
                .set("OrderQty", "100")
                .set("OrdType", "2")  // Limit
                .set("TimeInForce", "0")  // Day
                .set("Price", "150.50")
                .set("Currency", "USD")
                .set("QuoteID", "QUOTE789")
                .buildUnchecked();

        System.out.println("FIX Message (readable):");
        System.out.println(order.toFixString().replace("\u0001", "|"));

        System.out.println("\nValidation:");
        FixMessage.ValidationResult validation = order.validate();
        System.out.println("Valid: " + validation.isValid());
        if (!validation.getErrors().isEmpty()) {
            validation.getErrors().forEach(e -> System.out.println("  Error: " + e));
        }
        if (!validation.getWarnings().isEmpty()) {
            validation.getWarnings().forEach(w -> System.out.println("  Warning: " + w));
        }
    }

    private static void createQuoteRequest(FixDictionary dictionary) {
        FixMessage quoteReq = new FixMessageBuilder(dictionary, "QuoteRequest")
                .withDefaults("BUYER", "SELLER")
                // Add symbols group
                .addGroup("NoRelatedSym", Map.of(
                        "Symbol", "EURUSD",
                        "Currency", "USD",
                        "SettlDate", "20250102"
                ))
                .addGroup("NoRelatedSym", Map.of(
                        "Symbol", "GBPUSD",
                        "Currency", "USD",
                        "SettlDate", "20250102"
                ))
                // Add parties group
                .addGroup("NoPartyIDs", Map.of(
                        "PartyID", "FIRM123",
                        "PartyRole", "1"  // Executing Firm
                ))
                .buildUnchecked();

        System.out.println("FIX Message (readable):");
        System.out.println(quoteReq.toFixString().replace("\u0001", "|"));

        System.out.println("\nValidation:");
        FixMessage.ValidationResult validation = quoteReq.validate();
        System.out.println("Valid: " + validation.isValid());
        if (!validation.getErrors().isEmpty()) {
            validation.getErrors().forEach(e -> System.out.println("  Error: " + e));
        }
    }
}