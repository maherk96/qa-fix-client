package com.qa.quick.fix.poc.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FIXMessageExtractorTest {

    @Test
    void testExtractByName_QuoteRequest() throws Exception {
        Path dict = Path.of("FIX44.xml");
        assertTrue(Files.exists(dict), "Dictionary file FIX44.xml should exist at project root");

        FIXMessageExtractor extractor = new FIXMessageExtractor();
        extractor.loadSpecification(dict.toFile());

        MessageDefinition msg = extractor.extractMessage("QuoteRequest");
        assertEquals("QuoteRequest", msg.getMessageName());
        assertEquals("R", msg.getMessageType());

        // Header contains required message header fields
        assertTrue(msg.getHeaderFields().stream().anyMatch(f -> f.getName().equals("BeginString") && f.isRequired()));
        assertTrue(msg.getHeaderFields().stream().anyMatch(f -> f.getName().equals("MsgType") && f.isRequired()));

        // Body required field
        assertTrue(msg.getRequiredFields().stream().anyMatch(f -> f.getName().equals("QuoteReqID")));

        // Group present with correct numInGroup tag
        GroupDefinition noRelatedSym = msg.getGroups().stream()
                .filter(g -> g.getName().equals("NoRelatedSym"))
                .findFirst()
                .orElseThrow();
        assertEquals(146, noRelatedSym.getNumInGroupTag());

        // Component Parties is present and contains nested group NoPartyIDs
        ComponentDefinition parties = msg.getComponents().stream()
                .filter(c -> c.getName().equals("Parties"))
                .findFirst()
                .orElseThrow();
        assertTrue(parties.getGroups().stream().anyMatch(g -> g.getName().equals("NoPartyIDs")));

        // Trailer contains checksum
        assertTrue(msg.getTrailerFields().stream().anyMatch(f -> f.getName().equals("CheckSum")));

        // JSON serialization works
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(msg);
        assertTrue(json.contains("\"messageType\" : \"R\""));
    }

    @Test
    void testExtractByType_R() throws Exception {
        FIXMessageExtractor extractor = new FIXMessageExtractor();
        extractor.loadSpecification(Path.of("FIX44.xml").toFile());

        MessageDefinition msg = extractor.extractMessageByType("R");
        assertEquals("QuoteRequest", msg.getMessageName());
        assertEquals("R", msg.getMessageType());
    }

    @Test
    void testUnknownMessageNameThrows() throws Exception {
        FIXMessageExtractor extractor = new FIXMessageExtractor();
        extractor.loadSpecification(Path.of("FIX44.xml").toFile());

        assertThrows(FixSpecException.class, () -> extractor.extractMessage("DoesNotExist"));
    }

    @Test
    void testDefaultValueOverrideFromReference() throws Exception {
        String xml = "" +
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<fix major=\"4\" type=\"FIX\" servicepack=\"0\" minor=\"4\">" +
                "  <header>" +
                "    <field name=\"MsgType\" required=\"Y\"/>" +
                "  </header>" +
                "  <messages>" +
                "    <message name=\"TestMessage\" msgcat=\"app\" msgtype=\"ZZ\">" +
                "      <field name=\"Foo\" required=\"Y\" default=\"refOverride\"/>" +
                "    </message>" +
                "  </messages>" +
                "  <fields>" +
                "    <field number=\"35\" name=\"MsgType\" type=\"STRING\"/>" +
                "    <field number=\"9999\" name=\"Foo\" type=\"STRING\" default=\"baseDefault\"/>" +
                "  </fields>" +
                "  <trailer><field name=\"CheckSum\" required=\"Y\"/></trailer>" +
                "</fix>";

        FIXMessageExtractor extractor = new FIXMessageExtractor();
        extractor.loadSpecification(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        MessageDefinition msg = extractor.extractMessage("TestMessage");

        FieldDefinition foo = msg.getRequiredFields().stream().filter(f -> f.getName().equals("Foo")).findFirst().orElseThrow();
        assertEquals("refOverride", foo.getDefaultValue(), "Reference default should override base field default");
    }
}

