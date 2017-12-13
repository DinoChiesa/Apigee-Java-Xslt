// Copyright 2015-2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.google.apigee.edgecallouts.xslt;

import java.io.InputStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

import javax.xml.transform.TransformerFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.ErrorListener;

// Apache Commons stuff
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.pool.BaseKeyedPoolableObjectFactory;
import org.apache.commons.validator.routines.UrlValidator;

public class PooledTransformerFactory
    extends BaseKeyedPoolableObjectFactory<String, Transformer> {

    public PooledTransformerFactory() {
    }

    private TransformerFactory getTransformerFactory(String engine){
        TransformerFactory tf = TransformerFactory.newInstance(engine, null);
        //tf.setAttribute(FeatureKeys.MESSAGE_EMITTER_CLASS, MyMessageEmitter.class)
        return tf;
    }

    private static InputStream getResourceAsStream(String resourceName)
      throws IOException {
        // forcibly prepend a slash
        if (!resourceName.startsWith("/")) {
            resourceName = "/" + resourceName;
        }
        if (!resourceName.startsWith("/resources")) {
            resourceName = "/resources" + resourceName;
        }
        InputStream in = PooledTransformerFactory.class.getResourceAsStream(resourceName);

        if (in == null) {
            throw new IOException("resource \"" + resourceName + "\" not found");
        }

        return in;
    }

    private Source convertXsltToSource(String xslt) throws IOException {
        // check for the kind of xslt. URI, filename, or string
        Source source = null;
        UrlValidator urlv = new UrlValidator();

        if (urlv.isValid(xslt)) {
            // Is URL, therefore instantiate StreamSource directly from URI
            // varName = prefix + "_xslturl";
            // msgCtxt.setVariable(varName, xslt);
            source = new StreamSource(xslt);
        }
        else if (xslt.endsWith(".xsl") || xslt.endsWith(".xslt")) {
            // assume this is a stream resource in the JAR
            source = new StreamSource(getResourceAsStream(xslt));
        }
        else if (xslt.startsWith("<") && xslt.endsWith("stylesheet>")) {
            // assume this is a string containing an XSLT
            InputStream in = IOUtils.toInputStream(xslt, "UTF-8");
            source = new StreamSource(in);
        }
        else {
            throw new IllegalStateException("configuration error: invalid xslt");
        }
        return source;
    }

    /**
     * This creates a Transformer if not already present in the pool.
     */
    @Override
    public Transformer makeObject(String key) throws Exception {
        Transformer t = null;
        String[] parts = StringUtils.split((String)key,"-",2);
        String engine = parts[0];
        String xslt = parts[1];
        TransformerFactory tf = getTransformerFactory(engine);
        SimpleErrorListener errorListener = new SimpleErrorListener();
        // This handles errors that occur when creating the transformer. Eg, XSL malformed.
        tf.setErrorListener(errorListener);
        try {
            Source xsltSource = convertXsltToSource(xslt);
            t = tf.newTransformer(xsltSource);
            t.setURIResolver(new DataURIResolver(t.getURIResolver()));
            // if (t instanceof net.sf.saxon.jaxp.TransformerImpl) {
            //     net.sf.saxon.Controller c = t.getUnderlyingController();
            //     // c.setMessageEmitter(Receiver r);
            // }
        }
        catch (javax.xml.transform.TransformerConfigurationException tce1) {
            if (errorListener.getXsltError()!=null) {
                throw new PoolException(tce1.getMessage(),
                                        errorListener.getXsltError(),
                                        tce1);
            }
            else {
                throw tce1;
            }
        }
        return t;
    }

    /**
     * Destroy an instance no longer needed by the pool.
     */
    @Override
    public void destroyObject(String /*Object*/ key, Transformer /*Object*/ t) {
        /* NOOP */
    }

    /**
     * Reinitialize an instance to be returned by the pool to a caller
     */
    @Override
    public void activateObject(String /*Object*/ key, Transformer /*Object*/ t) throws Exception {
        /* NOOP */
    }

    /**
     * Uninitialize an instance to be returned to the idle object pool.
     */
    @Override
    public void passivateObject(String /*Object*/ key, Transformer /*Object*/ t) throws Exception {
        t.reset();
        ((SimpleErrorListener)t.getErrorListener()).reset();
    }

    /**
     * This class simply catches and stores the most recent error, for
     * later retrieval. Last write wins.
     */
    final class SimpleErrorListener implements ErrorListener {
        private String xsltError;
        public void error(TransformerException exception) {
            xsltError = exception.toString();
        }
        public void fatalError(TransformerException exception) {
            xsltError = exception.toString();
        }
        public void warning(TransformerException exception) {
            /* gulp */
        }
        public String getXsltError() {
            return xsltError;
        }
        public void reset() {
            xsltError = null;
        }
    }

}
