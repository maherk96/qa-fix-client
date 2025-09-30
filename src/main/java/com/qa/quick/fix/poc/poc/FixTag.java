package com.qa.quick.fix.poc.poc;

/**
 * Enum for commonly used FIX tags for better readability and type safety
 */
public enum FixTag {
    ACCOUNT("1"),
    CLORD_ID("11"),
    EXEC_ID("17"),
    ORDER_QTY("38"),
    ORD_STATUS("39"),
    ORD_TYPE("40"),
    PRICE("44"),
    SIDE("54"),
    SYMBOL("55"),
    TIME_IN_FORCE("59"),
    TRANSACT_TIME("60"),
    EXEC_TYPE("150");

    private final String tag;

    FixTag(String tag) {
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }

    @Override
    public String toString() {
        return tag;
    }

    /**
     * Find FixTag by tag string
     */
    public static FixTag fromTag(String tag) {
        for (FixTag fixTag : values()) {
            if (fixTag.tag.equals(tag)) {
                return fixTag;
            }
        }
        return null;
    }
}


