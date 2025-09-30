package com.qa.quick.fix.poc.poc;

import java.util.Map;

/**
 * Contract for resolving template expressions and validating required variables.
 */
public interface ITemplateResolver {
    Object resolveValue(String fieldValue, Map<String, Object> vars);

    Map<String, Object> resolveAllValues(Map<String, String> fields, Map<String, Object> vars);

    boolean containsTemplates(String value);

    void validateRequiredVariables(Map<String, String> fields, Map<String, Object> vars);
}

