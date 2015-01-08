package org.sagebionetworks.bridge.dynamodb;

import javax.annotation.Nonnull;
import javax.annotation.Resource;
import java.util.List;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBSaveExpression;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.ExpectedAttributeValue;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.dao.UploadSchemaDao;
import org.sagebionetworks.bridge.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.upload.UploadSchema;

/** DynamoDB implementation of the {@link org.sagebionetworks.bridge.dao.UploadSchemaDao} */
@Component
public class DynamoUploadSchemaDao implements UploadSchemaDao {
    /**
     * DynamoDB save expression for conditional puts if and only if the row doesn't already exist. This save expression
     * is executed on the row that would be written to. This idiom only checks for the existence of the key, which
     * won't exist if the row doesn't exist.
     */
    private static final DynamoDBSaveExpression DOES_NOT_EXIST_EXPRESSION = new DynamoDBSaveExpression()
            .withExpectedEntry("key", new ExpectedAttributeValue(false));

    private DynamoDBMapper mapper;

    /**
     * This is the DynamoDB mapper that reads from and writes to our DynamoDB table. This is normally configured by
     * Spring.
     */
    @Resource(name = "UploadSchemaDdbMapper")
    public void setDdbMapper(DynamoDBMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public @Nonnull UploadSchema createOrUpdateUploadSchema(@Nonnull String studyId, @Nonnull String schemaId,
            @Nonnull UploadSchema uploadSchema) {
        // Get the current version of the uploadSchema, if it exists
        DynamoUploadSchema oldUploadSchema = getUploadSchemaNoThrow(studyId, schemaId);
        int oldRev;
        if (oldUploadSchema != null) {
            oldRev = oldUploadSchema.getRevision();
        } else {
            oldRev = 0;
        }

        // Request should match old rev. If it does, auto-increment and write.
        // Strictly speaking, the save expression will also catch this condition, but checking this early saves us
        // a DDB round-trip.
        if (oldRev != uploadSchema.getRevision()) {
            throw new ConcurrentModificationException(uploadSchema);
        }

        // Use Jackson to build a DynamoUploadSchema object, so we can update the rev.
        // Currently, all UploadSchemas are DynamoUploadSchemas, so we don't need to validate this class cast.
        DynamoUploadSchema ddbUploadSchema = (DynamoUploadSchema) uploadSchema;
        ddbUploadSchema.setRevision(oldRev + 1);

        try {
            mapper.save(uploadSchema, DOES_NOT_EXIST_EXPRESSION);
        } catch (ConditionalCheckFailedException ex) {
            throw new ConcurrentModificationException(uploadSchema);
        }
        return uploadSchema;
    }

    /** {@inheritDoc} */
    @Override
    public @Nonnull UploadSchema getUploadSchema(@Nonnull String studyId, @Nonnull String schemaId) {
        UploadSchema uploadSchema = getUploadSchemaNoThrow(studyId, schemaId);
        if (uploadSchema == null) {
            throw new EntityNotFoundException(UploadSchema.class, String.format(
                    "Upload schema not found for study %s, schema ID %s", studyId, schemaId));
        }
        return uploadSchema;
    }

    /**
     * Private helper function, which gets a schema from DDB. This is used by the get (which validates afterwards) and
     * the put (which needs to check for concurrent modification exceptions). The return value of this helper method
     * may be null.
     */
    private DynamoUploadSchema getUploadSchemaNoThrow(@Nonnull String studyId, @Nonnull String schemaId) {
        DynamoUploadSchema key = new DynamoUploadSchema();
        key.setStudyId(studyId);
        key.setSchemaId(schemaId);

        // Get the latest revision. This is accomplished by scanning the range key backwards.
        DynamoDBQueryExpression<DynamoUploadSchema> ddbQuery = new DynamoDBQueryExpression<DynamoUploadSchema>()
                .withHashKeyValues(key).withScanIndexForward(false).withLimit(1);
        List<DynamoUploadSchema> schemaList = mapper.query(DynamoUploadSchema.class, ddbQuery);
        if (schemaList.isEmpty()) {
            return null;
        } else {
            return schemaList.get(0);
        }
    }
}
