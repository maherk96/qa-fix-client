package com.qa.quick.fix.poc.poc;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum FixMessageType {
    NEW_ORDER_SINGLE("D"),
    ORDER_CANCEL_REQUEST("F"),
    ORDER_CANCEL_REPLACE_REQUEST("G"),
    ORDER_STATUS_REQUEST("H"),
    EXECUTION_REPORT("8"),
    HEARTBEAT("0"),
    TEST_REQUEST("1"),
    LOGON("A"),
    LOGOUT("5"),
    RESEND_REQUEST("2"),
    SEQUENCE_RESET("4");

    private final String type;
}