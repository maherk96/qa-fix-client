package com.qa.quick.fix.cfg;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CommonSettings {

    @JsonProperty("ConnectionType")
    private String connectionType;

    @JsonProperty("ReconnectInterval")
    private String reconnectInterval;

    @JsonProperty("FileStorePath")
    private String fileStorePath;

    @JsonProperty("SLF4JLogHeartbeats")
    private String slf4jLogHeartbeats;

    @JsonProperty("StartDay")
    private String startDay;

    @JsonProperty("StartTime")
    private String startTime;

    @JsonProperty("EndDay")
    private String endDay;

    @JsonProperty("EndTime")
    private String endTime;

    @JsonProperty("UseDataDictionary")
    private String useDataDictionary;

    @JsonProperty("TransportDataDictionary")
    private String transportDataDictionary;

    @JsonProperty("AppDataDictionary")
    private String appDataDictionary;

    @JsonProperty("DataDictionary")
    private String dataDictionary;

    @JsonProperty("HttpAcceptPort")
    private String httpAcceptPort;

    @JsonProperty("ValidateLengthAndChecksum")
    private String validateLengthAndChecksum;

    @JsonProperty("ValidateFieldsOutOfOrder")
    private String validateFieldsOutOfOrder;

    @JsonProperty("ValidateFieldsHaveValues")
    private String validateFieldsHaveValues;

    @JsonProperty("ValidateUserDefinedFields")
    private String validateUserDefinedFields;

    @JsonProperty("ValidateUnorderedGroupFields")
    private String validateUnorderedGroupFields;

    @JsonProperty("CheckCompID")
    private String checkCompID;

    @JsonProperty("ResetOnLogout")
    private String resetOnLogout;

    @JsonProperty("ResetOnLogon")
    private String resetOnLogon;

    @JsonProperty("ResetOnDisconnect")
    private String resetOnDisconnect;

    @JsonProperty("DefaultApplVerID")
    private String defaultApplVerID;

    @JsonProperty("BeginString")
    private String beginString;

    @JsonProperty("HeartBtInt")
    private String heartBtInt;

    @JsonProperty("TargetCompID")
    private String targetCompID;

    @JsonProperty("SLF4JLogEventCategory")
    private String slf4jLogEventCategory;

    @JsonProperty("SLF4JLogIncomingMessageCategory")
    private String slf4jLogIncomingMessageCategory;

    @JsonProperty("SLF4JLogOutgoingMessageCategory")
    private String slf4jLogOutgoingMessageCategory;

    @JsonProperty("TargetSubID")
    private String targetSubID;
}