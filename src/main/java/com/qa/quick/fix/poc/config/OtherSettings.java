package com.qa.quick.fix.poc.config;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Additional client settings - supports both client types
 */
@Data
@NoArgsConstructor
public  class OtherSettings {
    @JsonProperty("clientStreamName")
    private String clientStreamName;
    
    @JsonProperty("username")
    private String username;
    
    @JsonProperty("password")
    private String password;
    
    @JsonProperty("account")
    private String account;  // For quote+trade clients
    
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
}