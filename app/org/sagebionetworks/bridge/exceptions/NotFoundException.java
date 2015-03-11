package org.sagebionetworks.bridge.exceptions;

import org.apache.commons.httpclient.HttpStatus;

/** Generic 404 NOT FOUND exception for things that aren't BridgeEntities. */
@SuppressWarnings("serial")
public class NotFoundException extends BridgeServiceException {
    public NotFoundException(String message) {
        super(message, HttpStatus.SC_NOT_FOUND);
    }

    public NotFoundException(Throwable throwable) {
        super(throwable, HttpStatus.SC_NOT_FOUND);
    }
}
