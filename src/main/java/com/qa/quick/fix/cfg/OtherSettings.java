package com.qa.quick.fix.cfg;

/** Additional client settings - supports both client types */
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OtherSettings {

  @JsonProperty("clientStreamName")
  private String clientStreamName;

  @JsonProperty("username")
  private String username;

  @JsonProperty("password")
  private String password;

  @JsonProperty("account")
  private String account;

  @JsonProperty("senderSubID")
  private String senderSubID;

  @JsonProperty("targetSubID")
  private String targetSubID;

  @JsonProperty("senderLocationID")
  private String senderLocationID;

  @JsonProperty("targetLocationID")
  private String targetLocationID;

  @JsonProperty("encryptMethod")
  private String encryptMethod;

  @JsonProperty("defaultApplVerID")
  private String defaultApplVerID;

  @JsonProperty("defaultCstmApplVerID")
  private String defaultCstmApplVerID;

  @JsonProperty("originatingTraderPartyID")
  private String originatingTraderPartyID;

  @JsonProperty("sessionIDPartyID")
  private String sessionIDPartyID;

  @JsonProperty("k1")
  private String k1;

  @JsonProperty("senderLocationPartyID")
  private String senderLocationPartyID;

  @JsonProperty("mtfPartyID")
  private String mtfPartyID;

  public Optional<String> getUsername() {
    return isValidValue(username) ? Optional.of(username) : Optional.empty();
  }

  public Optional<String> getPassword() {
    return isValidValue(password) ? Optional.of(password) : Optional.empty();
  }

  public Optional<String> getDefaultCstmApplVerID() {
    return isValidValue(defaultCstmApplVerID)
        ? Optional.of(defaultCstmApplVerID)
        : Optional.empty();
  }

  public Optional<String> getDefaultApplVerID() {
    return isValidValue(defaultApplVerID) ? Optional.of(defaultApplVerID) : Optional.empty();
  }

  public Optional<String> getSenderSubID() {
    return isValidValue(senderSubID) ? Optional.of(senderSubID) : Optional.empty();
  }

  public Optional<String> getTargetSubID() {
    return isValidValue(targetSubID) ? Optional.of(targetSubID) : Optional.empty();
  }

  private boolean isValidValue(String value) {
    return value != null && !value.trim().isEmpty() && !"MISSING".equalsIgnoreCase(value.trim());
  }
}
