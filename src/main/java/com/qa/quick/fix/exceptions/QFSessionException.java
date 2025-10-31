package com.qa.quick.fix.exceptions;

/**
 * Exception thrown when there is a runtime error related to FIX session state (e.g., sending on a
 * non-logged-on session, or missing session).
 */
public class QFSessionException extends RuntimeException {

  public QFSessionException(String message) {
    super(message);
  }

  public QFSessionException(String message, Throwable cause) {
    super(message, cause);
  }
}
