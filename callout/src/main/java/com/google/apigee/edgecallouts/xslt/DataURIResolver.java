package com.google.apigee.edgecallouts.xslt;

import javax.xml.transform.Source;
import javax.xml.transform.URIResolver;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;
import org.apache.commons.io.IOUtils;
import java.io.InputStream;
import java.io.IOException;

public class DataURIResolver implements URIResolver {
    private URIResolver _orig;
    public DataURIResolver(URIResolver orig) {
        _orig = orig;
    }

    @Override
    public Source resolve(String href, String base) throws TransformerException {
        if (base.equals("") && href.startsWith("data:text/xml,")) {
            // immediate XML string
            try {
                String xmlString = href.substring(14);
                InputStream in = IOUtils.toInputStream(xmlString, "UTF-8");
                return new StreamSource(in);
            }
            catch (IOException ioexc1) {
                throw new TransformerException("while converting", ioexc1);
            }
        }

        return _orig.resolve(href,base);
    }
}
