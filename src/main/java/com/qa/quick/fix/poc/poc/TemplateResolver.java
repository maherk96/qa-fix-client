package com.qa.quick.fix.poc.poc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves template expressions and variables from GlobalConfig
 */
public class TemplateResolver {
    
    private static final Logger logger = LoggerFactory.getLogger(TemplateResolver.class);
    
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final Pattern PARAM_PATTERN = Pattern.compile("([^:]+):([^:]+)(?::default=(.+))?");
    
    private final RandomValueGenerator randomValueGenerator;

    public TemplateResolver() {
        this.randomValueGenerator = new RandomValueGenerator();
    }

    public TemplateResolver(Random random) {
        this.randomValueGenerator = new RandomValueGenerator(random);
    }

    /**
     * Resolve a field value that may contain templates
     */
    public Object resolveValue(String fieldValue, Map<String, Object> vars) {
        if (fieldValue == null || fieldValue.trim().isEmpty()) {
            return "";
        }

        // Check if it's a template (starts with {{ and ends with }})
        if (isTemplate(fieldValue)) {
            return resolveTemplate(fieldValue, vars);
        }

        // Direct value - return as-is
        return fieldValue;
    }

    /**
     * Check if a value is a template expression
     */
    private boolean isTemplate(String value) {
        return value.startsWith("{{") && value.endsWith("}}");
    }

    /**
     * Resolve template expressions
     */
    private Object resolveTemplate(String template, Map<String, Object> vars) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        
        if (!matcher.find()) {
            throw new TemplateResolutionException("Invalid template format: " + template, template);
        }

        String templateContent = matcher.group(1).trim();
        logger.debug("Resolving template: {}", templateContent);

        // Handle built-in templates
        switch (templateContent) {
            case "randomOrderId":
                return generateRandomOrderId();
                
            case "currentTime":
                return LocalDateTime.now();
                
            case "uuid":
                return UUID.randomUUID().toString();
                
            default:
                return resolveParameterizedTemplate(templateContent, vars, template);
        }
    }

    /**
     * Resolve parameterized templates with optional defaults
     */
    private Object resolveParameterizedTemplate(String templateContent, Map<String, Object> vars, String originalTemplate) {
        Matcher paramMatcher = PARAM_PATTERN.matcher(templateContent);
        
        if (!paramMatcher.matches()) {
            throw new TemplateResolutionException("Invalid parameterized template format: " + templateContent, originalTemplate);
        }

        String operation = paramMatcher.group(1);
        String varName = paramMatcher.group(2);
        String defaultValue = paramMatcher.group(3); // Can be null

        logger.debug("Resolving parameterized template - operation: {}, varName: {}, default: {}", 
                    operation, varName, defaultValue);

        switch (operation) {
            case "randomFrom":
                return randomValueGenerator.randomFrom(varName, vars, defaultValue);
                
            case "randomPrice":
                return randomValueGenerator.randomPrice(varName, vars, 
                    defaultValue != null ? java.math.BigDecimal.valueOf(Double.parseDouble(defaultValue)) : java.math.BigDecimal.valueOf(100.0));
                
            case "randomQuantity":
                return randomValueGenerator.randomQuantity(varName, vars, 
                    defaultValue != null ? java.math.BigDecimal.valueOf(Double.parseDouble(defaultValue)) : java.math.BigDecimal.valueOf(100));
                
            case "randomInt":
                return randomValueGenerator.randomInt(varName, vars, 
                    defaultValue != null ? Integer.parseInt(defaultValue) : 1);
                
            case "currentTime":
                // Extended currentTime with format parameter
                return formatCurrentTime(varName); // varName is actually the format in this case
                
            default:
                throw new TemplateResolutionException("Unknown template operation: " + operation, originalTemplate, varName);
        }
    }

    /**
     * Generate random order ID
     */
    private String generateRandomOrderId() {
        return "ORD_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Format current time with specified pattern
     */
    private String formatCurrentTime(String pattern) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            return LocalDateTime.now().format(formatter);
        } catch (Exception e) {
            logger.warn("Invalid date format pattern '{}', using default ISO format", pattern);
            return LocalDateTime.now().toString();
        }
    }

    /**
     * Resolve all templates in a map of field values
     */
    public Map<String, Object> resolveAllValues(Map<String, String> fields, Map<String, Object> vars) {
        Map<String, Object> resolvedFields = new java.util.HashMap<>();
        
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            try {
                Object resolvedValue = resolveValue(entry.getValue(), vars);
                resolvedFields.put(entry.getKey(), resolvedValue);
                logger.debug("Resolved field {}: '{}' -> '{}'", entry.getKey(), entry.getValue(), resolvedValue);
            } catch (TemplateResolutionException e) {
                logger.error("Failed to resolve template for field {}: {}", entry.getKey(), e.getMessage());
                throw e;
            }
        }
        
        return resolvedFields;
    }

    /**
     * Check if a value contains any templates
     */
    public boolean containsTemplates(String value) {
        if (value == null) return false;
        return TEMPLATE_PATTERN.matcher(value).find();
    }

    /**
     * Validate that all required variables exist for the given fields
     */
    public void validateRequiredVariables(Map<String, String> fields, Map<String, Object> vars) {
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String fieldValue = entry.getValue();
            if (containsTemplates(fieldValue)) {
                validateTemplateVariables(fieldValue, vars, entry.getKey());
            }
        }
    }

    /**
     * Validate that variables required by a template exist
     */
    private void validateTemplateVariables(String template, Map<String, Object> vars, String fieldName) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        
        while (matcher.find()) {
            String templateContent = matcher.group(1).trim();
            
            // Skip built-in templates
            if (isBuiltInTemplate(templateContent)) {
                continue;
            }
            
            Matcher paramMatcher = PARAM_PATTERN.matcher(templateContent);
            if (paramMatcher.matches()) {
                String varName = paramMatcher.group(2);
                String defaultValue = paramMatcher.group(3);
                
                // If no default value, the variable must exist
                if (defaultValue == null && (vars == null || !vars.containsKey(varName))) {
                    throw new TemplateResolutionException(
                        String.format("Required variable '%s' not found for field '%s'", varName, fieldName),
                        template, varName);
                }
            }
        }
    }

    /**
     * Check if template is a built-in template that doesn't require variables
     */
    private boolean isBuiltInTemplate(String templateContent) {
        return templateContent.equals("randomOrderId") || 
               templateContent.equals("currentTime") || 
               templateContent.equals("uuid");
    }
}