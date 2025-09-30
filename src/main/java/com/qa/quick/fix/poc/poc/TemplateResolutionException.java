package com.qa.quick.fix.poc.poc;

public class TemplateResolutionException extends RuntimeException {
    
    private final String template;
    private final String variableName;

    public TemplateResolutionException(String message) {
        super(message);
        this.template = null;
        this.variableName = null;
    }

    public TemplateResolutionException(String message, String template) {
        super(message);
        this.template = template;
        this.variableName = null;
    }

    public TemplateResolutionException(String message, String template, String variableName) {
        super(message);
        this.template = template;
        this.variableName = variableName;
    }

    public TemplateResolutionException(String message, Throwable cause) {
        super(message, cause);
        this.template = null;
        this.variableName = null;
    }

    public String getTemplate() {
        return template;
    }

    public String getVariableName() {
        return variableName;
    }
}
