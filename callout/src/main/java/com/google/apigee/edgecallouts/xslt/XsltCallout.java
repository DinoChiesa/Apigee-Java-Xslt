// XsltCallout.java
//
// A callout for Apigee Edge that performs an XSLT. This callout uses a "keyed
// pool" of javax.xml.transform.Transformer objects to optimize the creation of
// such objects during concurrent requests. The key is the concatenation of the
// XSLT engine (eg, saxon, xalan), and the XSLT stylesheet name or url (eg,
// transformResponse.xsl) .
//
//
// Friday, 28 August 2015, 16:26
//
// Example configuration
//
// <JavaCallout name='Java-Xslt'>
//   <Properties>
//     <!-- specify the XSLT itself in one of these ways -->
//     <Property name='xslt'>file://xslt-filename.xsl</Property> <!-- resource in jar -->
//     <Property name='xslt'>http://hostname/url-returning-an-xslt</Property>
//     <Property name='xslt'>immediate-string-containing-xslt</Property>
//     <Property name='xslt'>{variable-containing-one-of-the-above}</Property>
//
//     <!-- specify engine, default is saxon ->
//     <Property name='engine'>saxon</Property>
//     <Property name='engine'>xalan</Property>
//
//     <!-- source for the transform.  If of type Message, then use x.content -->
//     <Property name='input'>name-of-variable-containing-message-or-string</Property>
//
//     <!-- where to put the transformed data. If none, put in message.content -->
//     <Property name='output'>name-of-variable-to-hold-output</Property>
//
//     <!-- arbitrary params to pass to the XSLT -->
//     <Property name='param_x'>string value of param</Property>
//     <Property name='param_y'>{variable-containing-value-of-param}</Property>
//     <Property name='param_z'>file://something.xsd</Property> <!-- resource in jar -->
//   </Properties>
//   <ClassName>com.dinochiesa.xslt.XsltCallout</ClassName>
//   <ResourceURL>java://edgecallout-xslt.jar</ResourceURL>
// </JavaCallout>
//
//
// ----------------------------------------------------------
//
// This software is licensed under the Apache Source license 2.0.
// See the accompanying LICENSE file.
//
// Copyright (c) 2015, 2016 by Dino Chiesa and Apigee Corporation, 2017 Google Inc.
//

package com.google.apigee.edgecallouts.xslt;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.Transformer;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;


import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.IOIntensive;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;
import com.apigee.flow.message.Message;

// Apache Commons stuff
import org.apache.commons.lang.StringUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.pool.impl.StackKeyedObjectPool;
import org.apache.commons.pool.KeyedObjectPool;

// Google's Guava collections tools
import com.google.common.collect.Collections2;
import com.google.common.collect.Maps;
import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.CacheLoader;

@IOIntensive
public class XsltCallout implements Execution {
    // The default cap on the number of "sleeping" instances in the pool.
    private static final String varPrefix = "xslt_";
    protected static final int MAX_IDLE_TRANSFORMERS = 20;
    private static final String variableReferencePatternString = "(.*?)\\{([^\\{\\}]+?)\\}(.*?)";
    private final static Pattern variableReferencePattern = Pattern.compile(variableReferencePatternString);
    private static final String urlReferencePatternString = "^(https?://)(.+)$";
    private final static Pattern urlReferencePattern = Pattern.compile(urlReferencePatternString);
    private LoadingCache<String, String> fileResourceCache;
    private LoadingCache<String, String> urlResourceCache;
    private KeyedObjectPool transformerPool;
    private Map<String,String> properties; // read-only

    public XsltCallout (Map properties) {
        // convert the untyped Map to a generic map
        Map<String,String> m = new HashMap<String,String>();
        Iterator iterator =  properties.keySet().iterator();
        while(iterator.hasNext()){
            Object key = iterator.next();
            Object value = properties.get(key);
            if ((key instanceof String) && (value instanceof String)) {
                m.put((String) key, (String) value);
            }
        }
        this.properties = m;

        /**
         * transformers is a keyed pool of Transformer objects. The key is the
         * xslt engine joined by a dash with the XSLT sheet. Example:
         * "saxon-transformResponse.xsl". The pool is not limited in size; it
         * expands to meet concurrent demand. It contracts to the
         * MAX_IDLE_TRANSFORMERS in low contention conditions. The idea is that
         * if we have 25 concurrent requests, all of them can have their own
         * Transformer object; but those objects get returned to a pool for
         * re-use so we don't have to create one for each request.
         *
         * Currently, Apigee Edge uses commons-pool-1.5.4.jar, which exposes
         * non-generic types like StackKeyedObjectPool. In v1.6 of
         * commons-pool, the types are generic.
         */
        this.transformerPool =
            new StackKeyedObjectPool<String, Transformer>(new PooledTransformerFactory(), MAX_IDLE_TRANSFORMERS);

        fileResourceCache = CacheBuilder.newBuilder()
            .concurrencyLevel(4)
            .maximumSize(1048000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<String, String>() {
                    public String load(String key) throws IOException {
                        InputStream in = null;
                        String s = "";
                        try {
                            in = getResourceAsStream(key);
                            byte[] fileBytes = new byte[in.available()];
                            in.read(fileBytes);
                            in.close();
                            s = new String(fileBytes, StandardCharsets.UTF_8);
                        }
                        catch (java.lang.Exception exc1) {
                            // gulp
                        }
                        finally {
                            if (in!=null) { in.close(); }
                        }
                        return s.trim();
                    }
                });

        urlResourceCache = CacheBuilder.newBuilder()
            .concurrencyLevel(4)
            .maximumSize(1048000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<String, String>() {
                    public String load(String key) throws IOException {
                        InputStream in = null;
                        String s = "";
                        try {
                            URL url = new URL(key);
                            in = url.openStream ();
                            s = new String(IOUtils.toByteArray(in), StandardCharsets.UTF_8);
                        }
                        catch (java.lang.Exception exc1) {
                            // gulp
                        }
                        finally {
                            if (in!=null) { in.close(); }
                        }
                        return s.trim();
                    }
                });
    }

    private static String varName(String s) {
        return varPrefix + s;
    }

    private String getOutputVariable() {
        String outputVar = (String) this.properties.get("output");
        if (outputVar == null || outputVar.equals("")) {
            return "message.content";
        }
        return outputVar;
    }

    private String getInputProperty() {
        String inputProp = (String) this.properties.get("input");
        if (inputProp == null || inputProp.equals("")) {
            return "message";
        }
        return inputProp;
    }

    private boolean getDebug() {
        String value = (String) this.properties.get("debug");
        if (value == null) return false;
        if (value.trim().toLowerCase().equals("true")) return true;
        return false;
    }

    private Source getTransformInput(MessageContext msgCtxt)
        throws IOException {
        String inputProp = getInputProperty();
        Source source = null;
        Object in = msgCtxt.getVariable(inputProp);
        if (in == null) {
            throw new IllegalStateException("input is not specified");
        }
        if (in instanceof com.apigee.flow.message.Message) {
            Message msg = (Message) in;
            source = new StreamSource(msg.getContentAsStream());
        }
        else {
            // assume it resolves to an xml string
            String s = (String) in;
            s = s.trim();
            if (!s.startsWith("<")) {
                throw new IllegalStateException("input does not appear to be XML");
            }
            InputStream s2 = IOUtils.toInputStream((String)s, "UTF-8");
            source = new StreamSource(s2);
        }
        return source;
    }

    private String getXslt(MessageContext msgCtxt)
        throws IllegalStateException, IOException, ExecutionException {
        String xslt = (String) this.properties.get("xslt");
        Source source = null;
        if (xslt == null) {
            throw new IllegalStateException("configuration error: no xslt property");
        }
        if (xslt.equals("")) {
            throw new IllegalStateException("configuration error: xslt property is empty");
        }
        xslt = resolvePropertyValue(xslt, msgCtxt);
        if (xslt == null || xslt.equals("")) {
            throw new IllegalStateException("configuration error: xslt resolves to null or empty");
        }
        xslt = xslt.trim();
        xslt = maybeResolveUrlReference(xslt);
        return xslt;
    }

    private String getEngine(MessageContext msgCtxt)
        throws IllegalStateException {
        String engine = (String) this.properties.get("engine");
        if (engine == null || engine.equals("")) {
            engine = "saxon";   // default to saxon
        }
        engine = resolvePropertyValue(engine, msgCtxt);
        if (engine == null || engine.equals("")) {
            throw new IllegalStateException("configuration error: engine resolves to null or empty.");
        }

        if (engine.toLowerCase().equals("xalan")) {
            engine = "org.apache.xalan.processor.TransformerFactoryImpl";
        }
        else if (engine.toLowerCase().equals("saxon")) {
            engine = "net.sf.saxon.TransformerFactoryImpl";
        }
        else if (engine.indexOf(".") > -1) {
            // do nothing - it's apparently a classname
            // We'll try to load and use it.
        }
        else {
            throw new IllegalStateException("configuration error: unknown XSLT engine: " + engine);
        }
        return engine;
    }

    // If the value of a property contains a pair of curlies,
    // eg, {apiproxy.name}, then "resolve" the value by de-referencing
    // any context variable whose name appears between the curlies.
    private String resolvePropertyValue(String spec, MessageContext msgCtxt) {
        Matcher matcher = variableReferencePattern.matcher(spec);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "");
            sb.append(matcher.group(1));
            sb.append((String) msgCtxt.getVariable(matcher.group(2)));
            sb.append(matcher.group(3));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static InputStream getResourceAsStream(String resourceName) throws IOException {
        // forcibly prepend a slash
        if (!resourceName.startsWith("/")) {
            resourceName = "/" + resourceName;
        }
        InputStream in = XsltCallout.class.getResourceAsStream(resourceName);
        if (in == null) {
            throw new IOException("resource \"" + resourceName + "\" not found");
        }
        return in;
    }

    private String maybeResolveUrlReference(String ref) throws ExecutionException {
        if (ref.startsWith("file://")) {
            return fileResourceCache.get(ref.substring(7,ref.length()));
        }
        Matcher m = urlReferencePattern.matcher(ref);
        if (m.find()) {
            return urlResourceCache.get(ref);
        }
        return ref;
    }

    // Return all properties that begin with param_
    // These will be passed to the XSLT as parameters.
    private Map<String, String> paramProperties() {
        Predicate<Map.Entry<String, String>> p1 =
            new Predicate<Map.Entry<String, String>>() {
            @Override
            public boolean apply(Map.Entry<String, String> entry) {
                return entry.getKey().startsWith("param_");
            }
        };
        Map<String, String> paramProps = Maps.filterEntries(properties, p1);
        return paramProps;
    }

    public ExecutionResult execute(MessageContext msgCtxt, ExecutionContext exeCtxt) {
        ExecutionResult calloutResult = ExecutionResult.ABORT;
        Boolean isValid = false;
        String cacheKey = null;
        boolean debug = getDebug();
        Transformer transformer = null;
        try {
            String xslt = getXslt(msgCtxt);
            String xsltEngine = getEngine(msgCtxt);
            cacheKey = xsltEngine + "-" + xslt;
            transformer = (Transformer) transformerPool.borrowObject(cacheKey);
            CustomXsltErrorListener listener = new CustomXsltErrorListener(msgCtxt, debug);
            transformer.setErrorListener(listener);
            Source input = getTransformInput(msgCtxt);

            Map<String,String> params = paramProperties();
            // pass all specified parameters to the transform
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String key = entry.getKey();
                String[] parts = StringUtils.split(key,"_");
                // sanity check - is this a param?
                if (parts.length == 2 && parts[0].equals("param")) {
                    String value = entry.getValue();
                    value = resolvePropertyValue(value, msgCtxt);
                    value = maybeResolveUrlReference(value);
                    String pName =  parts[1];
                    transformer.setParameter(pName, value);
                }
            }

            StreamResult xformOutput = new StreamResult(new java.io.StringWriter());
            transformer.transform(input, xformOutput);

            if (listener.getErrorCount() > 0) {
                throw new Exception("Encountered " + listener.getErrorCount() + " errors while transforming");
            }

            // set the result into a context variable
            String xformResult = xformOutput.getWriter().toString().trim();
            String outputVariable = getOutputVariable();
            msgCtxt.setVariable(outputVariable, xformResult);
            calloutResult = ExecutionResult.SUCCESS;
        }
        catch (Exception e) {
            if (debug) e.printStackTrace(); // to MP stdout
            String error = error = e.toString();
            msgCtxt.setVariable(varName("exception"), error);
            int ch = error.lastIndexOf(':');
            if (ch >= 0) {
                msgCtxt.setVariable(varName("error"), error.substring(ch+2).trim());
            }
            else {
                msgCtxt.setVariable(varName("error"), error);
            }
            if (e instanceof PoolException) {
                msgCtxt.setVariable(varName("additionalInformation"),
                                    ((PoolException)e).getAdditionalInformation());
            }
        }
        finally {
            try {
                if (cacheKey != null && transformer != null)
                    transformerPool.returnObject(cacheKey,transformer);
            }
            catch (java.lang.Exception ignored) {
            }
        }

        return calloutResult;
    }
}
