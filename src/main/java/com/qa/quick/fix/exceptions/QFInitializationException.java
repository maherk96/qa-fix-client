package com.qa.quick.fix.exceptions;

/**
 * Exception thrown when there is an error during the initialization process.
 */
public class QFInitializationException extends RuntimeException {

    /**
     * Constructs a new QFInitializationException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param e       the cause of the exception
     */
    public QFInitializationException(String message, Throwable e) {
        super(message, e);
    }

    /**
     * Constructs a new QFInitializationException with the specified detail message.
     *
     * @param message the detail message
     */
    public QFInitializationException(String message) {
        super(message);
    }
}