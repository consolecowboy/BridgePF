package org.sagebionetworks.bridge.json;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.sagebionetworks.bridge.models.surveys.Survey;

import com.fasterxml.jackson.databind.JsonNode;

public class BridgeObjectMapperTest {

    @Test
    public void addsTypeField() {
        final class NotAnnotated {
            @SuppressWarnings("unused") public String field;
        }
        
        @BridgeTypeName("AnnotationName")
        final class Annotated {
            @SuppressWarnings("unused") public String field;
        }
        
        BridgeObjectMapper mapper = new BridgeObjectMapper();
        
        JsonNode node = mapper.valueToTree(new NotAnnotated());
        assertEquals("Type is NotAnnotated", "NotAnnotated", node.get("type").asText());
        
        node = mapper.valueToTree(new Annotated());
        assertEquals("Type is AnnotationName", "AnnotationName", node.get("type").asText());
    }
    
    @Test
    public void doesNotOverrideExistingTypeField() {
        @BridgeTypeName("WrongName")
        final class NotAnnotated {
            private final String type;
            public NotAnnotated(String type) {
                this.type = type;
            }
            @SuppressWarnings("unused") public String getType() {
                return type;
            }
        }
        
        BridgeObjectMapper mapper = new BridgeObjectMapper();
        
        JsonNode node = mapper.valueToTree(new NotAnnotated("ThisIsTheName"));
        assertEquals("Type is ThisIsTheName", "ThisIsTheName", node.get("type").asText());
    }
        
    /**
     * It should be possible to send a null for any field, including fields that are deserialized into 
     * primitive longs, but this causes an exception in Jackson during deserialization. Instead, use the 
     * default field value of 0L and then validate whether or not this value is valid (in the case of 
     * our system, we ignore all such values sent from clients in favor of setting the value on the server, 
     * but that doesn't mean the client won't send them, set to null even).
     * @throws Exception
     */
    @Test
    public void canDeserializeTimestampNullsForPrimitiveLongFields() throws Exception {
        String json = "{\"createdOn\":null,\"modifiedOn\":null}";
        
        Survey survey = new BridgeObjectMapper().readValue(json, Survey.class);
        assertEquals(0, survey.getCreatedOn());
        assertEquals(0, survey.getModifiedOn());
        
        // No field works as well
        json = "{}";
        survey = new BridgeObjectMapper().readValue(json, Survey.class);
        assertEquals(0, survey.getCreatedOn());
        assertEquals(0, survey.getModifiedOn());
        
        // If you were to supply zeroes, you do get a deserialization error, as you would any other 
        // value not recognizable as a date.
    }
}
