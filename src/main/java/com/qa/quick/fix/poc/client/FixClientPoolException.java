package com.qa.quick.fix.poc.client;

/**
 * Exception for pool management errors
 */
public class FixClientPoolException extends Exception {
    public FixClientPoolException(String message) {
        super(message);
    }
    
    public FixClientPoolException(String message, Throwable cause) {
        super(message, cause);
    }
}