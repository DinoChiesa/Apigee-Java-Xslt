package com.google.apigee.edgecallouts.xslt;

import java.io.InputStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;

import javax.xml.transform.TransformerFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

// Apache Commons stuff
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.pool.BaseKeyedPoolableObjectFactory;
import org.apache.commons.validator.routines.UrlValidator;

public class PooledTransformerFactory
    extends BaseKeyedPoolableObjectFactory/*<String, Transformer>*/ {

    private Map<String,TransformerFactory> factoryCache;

    public PooledTransformerFactory() {
        this.factoryCache = new HashMap<String,TransformerFactory>();
    }

    private TransformerFactory getTransformerFactory(String engine /*, MessageContext c, String p */){
        TransformerFactory f = this.factoryCache.get(engine);
        if (f == null) {
            f = TransformerFactory.newInstance(engine, null);
            this.factoryCache.put(engine, f);
        }
        return f;
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

    private Source convertXsltToSource(String xslt /*, MessageContext msgCtxt, String prefix*/)  throws IOException {
        // check for the kind of xslt.  URI, filename, or string
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
    public Transformer makeObject(Object key) throws Exception {
        String[] parts = StringUtils.split((String)key,"-",2);
        String engine = parts[0];
        TransformerFactory factory = getTransformerFactory(engine);
        String xslt = parts[1];
        Source xsltSource = convertXsltToSource(xslt);
        return factory.newTransformer(xsltSource);
    }

    /**
     * Destroy an instance no longer needed by the pool.
     */
    @Override
    public void destroyObject(Object /*String*/ key, Object /*Transformer*/ t) {
        /* NOOP */
    }

    /**
     * Reinitialize an instance to be returned by the pool to a caller
     */
    @Override
    public void activateObject(Object /*String*/ key, Object /*Transformer*/ t) throws Exception {
        /* NOOP */
    }

    /**
     * Uninitialize an instance to be returned to the idle object pool.
     */
    @Override
    public void passivateObject(Object /*String*/ key, Object /*Transformer*/ t) throws Exception {
        ((Transformer)t).reset();
    }
}
