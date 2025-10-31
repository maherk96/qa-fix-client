package com.qa.quick.fix.core.pool;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Data;

@Data
public class StartupResult {
  private final Set<String> successfulClients;
  private final Map<String, Exception> failedClients;
  private final boolean allSuccessful;

  public StartupResult(
      Set<String> successfulClients, Map<String, Exception> failedClients, boolean allSuccessful) {
    this.successfulClients = new HashSet<>(successfulClients);
    this.failedClients = new HashMap<>(failedClients);
    this.allSuccessful = allSuccessful;
  }
}
