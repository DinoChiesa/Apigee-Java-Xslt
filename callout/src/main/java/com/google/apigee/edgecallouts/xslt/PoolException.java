package com.google.apigee.edgecallouts.xslt;

import javax.xml.transform.TransformerException;

public class PoolException extends Exception {
    private String additionalInformation;
    public PoolException(String message, String additionalInformation, TransformerException inner) {
        super(message, inner);
        this.additionalInformation = additionalInformation;
    }
    public String getAdditionalInformation() {
        return additionalInformation;
    }
}
