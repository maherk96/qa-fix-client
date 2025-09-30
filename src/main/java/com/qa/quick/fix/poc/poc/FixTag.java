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
    private static final java.util.Map<String, FixTag> LOOKUP;

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
        return LOOKUP.get(tag);
    }

    public static java.util.Optional<FixTag> findByTag(String tag) {
        return java.util.Optional.ofNullable(LOOKUP.get(tag));
    }

    static {
        java.util.Map<String, FixTag> map = new java.util.HashMap<>();
        for (FixTag t : values()) {
            map.put(t.tag, t);
        }
        LOOKUP = java.util.Collections.unmodifiableMap(map);
    }
}

