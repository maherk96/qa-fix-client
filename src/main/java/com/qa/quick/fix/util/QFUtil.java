package com.qa.quick.fix.util;

import quickfix.Dictionary;

/**
 * Utility class for QuickFIX-related operations. Provides helper methods to work with QuickFIX
 * dictionaries.
 */
public final class QFUtil {

  private QFUtil() {
    // Prevent instantiation
  }

  /**
   * Helper method to set a string value in the dictionary if it is not null.
   *
   * @param dict The dictionary to set the value in.
   * @param key The key for the value.
   * @param value The value to set.
   */
  public static void setIfNotNull(Dictionary dict, String key, String value) {
    if (value != null) {
      dict.setString(key, value);
    }
  }
}
