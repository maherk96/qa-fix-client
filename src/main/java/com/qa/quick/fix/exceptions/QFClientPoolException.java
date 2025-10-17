package com.qa.quick.fix.exceptions;

/**
 * Exception thrown when there is an error related to the QFClient pool operations.
 */
public class QFClientPoolException extends Exception {

    /**
     * Constructs a new QFClientPoolException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param e       the cause of the exception
     */
    public QFClientPoolException(String message, Throwable e) {
        super(message, e);
    }

    /**
     * Constructs a new QFClientPoolException with the specified detail message.
     *
     * @param message the detail message
     */
    public QFClientPoolException(String message) {
        super(message);
    }
}
