package com.qa.quick.fix.poc.poc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

import java.util.Map;
import java.util.Random;

/**
 * Orchestrates the creation of FIX messages from JSON request templates
 * Uses modular components for template resolution, random value generation, and field mapping
 */
public class JsonToFixMapper {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonToFixMapper.class);
    
    private final TemplateResolver templateResolver;
    private final FixFieldMapper fixFieldMapper;

    /**
     * Default constructor for production use
     */
    public JsonToFixMapper() {
        this.templateResolver = new TemplateResolver();
        this.fixFieldMapper = new FixFieldMapper();
        logger.debug("JsonToFixMapper initialized with default components");
    }

    /**
     * Constructor with injectable Random for deterministic testing
     */
    public JsonToFixMapper(Random random) {
        this.templateResolver = new TemplateResolver(random);
        this.fixFieldMapper = new FixFieldMapper();
        logger.debug("JsonToFixMapper initialized with custom Random for testing");
    }

    /**
     * Create FIX message from Request using GlobalConfig for variable resolution
     */
    public Message createFixMessage(FixLoadTaskDefinition.Request request, FixLoadTaskDefinition.GlobalConfig globalConfig) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        
        if (request.getMessageType() == null) {
            throw new IllegalArgumentException("Message type cannot be null");
        }

        logger.debug("Creating FIX message of type: {}", request.getMessageType());

        // Validate that all required variables exist before processing
        Map<String, Object> vars = globalConfig != null ? globalConfig.getVars() : null;
        if (request.getFields() != null) {
            templateResolver.validateRequiredVariables(request.getFields(), vars);
        }

        // Create the appropriate message type
        Message message = createMessageByType(request.getMessageType());
        
        // Resolve templates and set fields
        if (request.getFields() != null && !request.getFields().isEmpty()) {
            Map<String, Object> resolvedFields = templateResolver.resolveAllValues(request.getFields(), vars);
            fixFieldMapper.setAllFields(message, resolvedFields);
            
            logger.debug("Successfully created {} with {} fields", 
                request.getMessageType(), resolvedFields.size());
        } else {
            logger.warn("No fields specified for message type: {}", request.getMessageType());
        }

        return message;
    }

    /**
     * Create message instance based on message type
     */
    private Message createMessageByType(FixMessageType messageType) {
        switch (messageType) {
            case NEW_ORDER_SINGLE:
                return new NewOrderSingle();
            
            case EXECUTION_REPORT:
                return new ExecutionReport();
            
            default:
                throw new IllegalArgumentException("Unsupported message type: " + messageType);
        }
    }

    /**
     * Register a custom field mapper
     */
    public void registerCustomField(String tag, java.util.function.BiConsumer<Message, Object> mapper) {
        fixFieldMapper.registerField(tag, mapper);
        logger.debug("Registered custom field mapper for tag: {}", tag);
    }

    /**
     * Demonstration method showing complete usage
     */
    public static void demonstrateUsage() {
        logger.info("=== JsonToFixMapper Demonstration ===");

        // Create mapper
        JsonToFixMapper mapper = new JsonToFixMapper();

        // Setup GlobalConfig with variables
        FixLoadTaskDefinition.GlobalConfig globalConfig = new FixLoadTaskDefinition.GlobalConfig();
        globalConfig.setConfigPath("fix-client-config.json");
        globalConfig.setEnvironmentName("ALGO_UAT");
        globalConfig.setClientStreamNames(java.util.Arrays.asList("CLIENT_001", "CLIENT_002"));

        // Setup vars
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("symbols", java.util.Arrays.asList("AAPL", "GOOGL", "MSFT", "TSLA", "AMZN"));
        vars.put("sides", java.util.Arrays.asList("1", "2"));
        vars.put("orderTypes", java.util.Arrays.asList("1", "2", "3", "4"));
        vars.put("timeInForce", java.util.Arrays.asList("0", "1", "3", "4"));
        vars.put("accounts", java.util.Arrays.asList("ACC001", "ACC002", "ACC003", "ACC004"));

        Map<String, Object> priceRange = new java.util.HashMap<>();
        priceRange.put("min", 50.0);
        priceRange.put("max", 500.0);
        vars.put("priceRange", priceRange);

        Map<String, Object> quantityRange = new java.util.HashMap<>();
        quantityRange.put("min", 100);
        quantityRange.put("max", 10000);
        vars.put("quantityRange", quantityRange);

        globalConfig.setVars(vars);

        // Create NewOrderSingle request
        FixLoadTaskDefinition.Request orderRequest = new FixLoadTaskDefinition.Request();
        orderRequest.setMessageType(FixMessageType.NEW_ORDER_SINGLE);

        Map<String, String> orderFields = new java.util.HashMap<>();
        orderFields.put(FixTag.CLORD_ID.getTag(), "{{randomOrderId}}");
        orderFields.put(FixTag.SYMBOL.getTag(), "{{randomFrom:symbols}}");
        orderFields.put(FixTag.SIDE.getTag(), "{{randomFrom:sides}}");
        orderFields.put(FixTag.PRICE.getTag(), "{{randomPrice:priceRange}}");
        orderFields.put(FixTag.ORDER_QTY.getTag(), "{{randomQuantity:quantityRange}}");
        orderFields.put(FixTag.ORD_TYPE.getTag(), "2"); // Direct value: Limit
        orderFields.put(FixTag.TIME_IN_FORCE.getTag(), "{{randomFrom:timeInForce}}");
        orderFields.put(FixTag.ACCOUNT.getTag(), "{{randomFrom:accounts}}");
        orderFields.put(FixTag.TRANSACT_TIME.getTag(), "{{currentTime}}");

        orderRequest.setFields(orderFields);

        try {
            // Create message
            Message fixMessage = mapper.createFixMessage(orderRequest, globalConfig);
            logger.info("Generated FIX Message: {}", fixMessage.toString());
            
            // Print key fields for verification
            try {
                logger.info("  ClOrdID: {}", fixMessage.getString(quickfix.field.ClOrdID.FIELD));
                logger.info("  Symbol: {}", fixMessage.getString(quickfix.field.Symbol.FIELD));
                logger.info("  Side: {}", fixMessage.getString(quickfix.field.Side.FIELD));
                logger.info("  Price: {}", fixMessage.getString(quickfix.field.Price.FIELD));
                logger.info("  OrderQty: {}", fixMessage.getString(quickfix.field.OrderQty.FIELD));
            } catch (Exception e) {
                logger.warn("Could not extract field details: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.error("Demonstration failed: {}", e.getMessage(), e);
        }

        logger.info("=== Demonstration Complete ===");
    }

    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) {
        demonstrateUsage();
    }
}