package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DtoJsonInclusionTest {

    @Test
    void testMessageDefinitionOmitsEmptyCollections() throws Exception {
        MessageDefinition msg = new MessageDefinition();
        msg.setMessageName("EmptyTest");
        msg.setMessageType("ET");

        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(msg);

        // Should contain the basic fields
        assertTrue(json.contains("\"messageName\""));
        assertTrue(json.contains("\"messageType\""));

        // Should omit empty arrays due to NON_EMPTY
        assertFalse(json.contains("requiredFields"));
        assertFalse(json.contains("optionalFields"));
        assertFalse(json.contains("groups"));
        assertFalse(json.contains("components"));
        assertFalse(json.contains("headerFields"));
        assertFalse(json.contains("trailerFields"));
    }
}

