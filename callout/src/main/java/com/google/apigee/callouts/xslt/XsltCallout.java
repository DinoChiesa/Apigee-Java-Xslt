// XsltCallout.java
//
// A callout for Apigee Edge that performs an XSLT. This callout uses a "keyed
// pool" of javax.xml.transform.Transformer objects to optimize the creation of
// such objects during concurrent requests. The key is the concatenation of the
// XSLT engine (eg, saxon, xalan), and the XSLT stylesheet name or url (eg,
// transformResponse.xsl) .
//
// Copyright 2015-2021 Google LLC.
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
// ----------------------------------------------------------
//
// This software is licensed under the Apache Source license 2.0.
// See the accompanying LICENSE file.
//
//

package com.google.apigee.callouts.xslt;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.IOIntensive;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.Message;
import com.apigee.flow.message.MessageContext;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.apigee.callouts.CalloutBase;
import com.google.apigee.util.CalloutUtil;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

@IOIntensive
public class XsltCallout extends CalloutBase implements Execution {
  // The default cap on the number of "sleeping" instances in the pool.
  private static final String varPrefix = "xslt_";
  protected static final int MAX_CACHE_ENTRIES = 512;
  private static final String urlReferencePatternString = "^(https?://)(.+)$";
  private static final Pattern urlReferencePattern = Pattern.compile(urlReferencePatternString);
  private static final LoadingCache<String, String> fileResourceCache;
  private static final LoadingCache<String, String> urlResourceCache;

  static {
    fileResourceCache =
        Caffeine.newBuilder()
            // .concurrencyLevel(4)
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(
                new CacheLoader<String, String>() {
                  public String load(String key) throws IOException {
                    InputStream in = null;
                    String s = "";
                    try {
                      in = getResourceAsStream(key);
                      byte[] fileBytes = new byte[in.available()];
                      in.read(fileBytes);
                      in.close();
                      s = new String(fileBytes, StandardCharsets.UTF_8);
                    } catch (java.lang.Exception exc1) {
                      // gulp
                    } finally {
                      if (in != null) {
                        in.close();
                      }
                    }
                    return s.trim();
                  }
                });

    urlResourceCache =
        Caffeine.newBuilder()
            // .concurrencyLevel(4)
            .maximumSize(MAX_CACHE_ENTRIES)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(
                new CacheLoader<String, String>() {
                  public String load(String key) throws IOException {
                    String s = "";
                    try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(new URL(key).openStream()))) {

                      s = reader.lines().collect(Collectors.joining("\n"));
                    } catch (java.lang.Exception exc1) {
                      // gulp
                    }
                    return s.trim();
                  }
                });
  }

  public XsltCallout(Map properties) {
    super(properties);
  }

  public String getVarnamePrefix() {
    return varPrefix;
  }

  private String getInputProperty() {
    String inputProp = (String) this.properties.get("input");
    if (inputProp == null || inputProp.equals("")) {
      return "message";
    }
    return inputProp;
  }

  private Source getTransformInput(MessageContext msgCtxt) throws IOException {
    String inputProp = getInputProperty();
    Source source = null;
    Object in = msgCtxt.getVariable(inputProp);
    if (in == null) {
      throw new IllegalStateException("input is not specified");
    }
    if (in instanceof com.apigee.flow.message.Message) {
      Message msg = (Message) in;
      source = new StreamSource(msg.getContentAsStream());
    } else {
      // assume it resolves to an xml string
      String s = (String) in;
      s = s.trim();
      if (!s.startsWith("<")) {
        throw new IllegalStateException("input does not appear to be XML");
      }
      InputStream s2 = new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));

      source = new StreamSource(s2);
    }
    return source;
  }

  private String getXslt(MessageContext msgCtxt) throws Exception {
    String xslt = getSimpleRequiredProperty("xslt", msgCtxt);
    xslt = maybeResolveUrlReference(xslt.trim());
    return xslt;
  }

  private String getEngine(MessageContext msgCtxt) throws IllegalStateException {
    String engine = (String) this.properties.get("engine");
    if (engine == null || engine.equals("")) {
      engine = "saxon"; // default to saxon
    }
    engine = resolvePropertyValue(engine, msgCtxt);
    if (engine == null || engine.equals("")) {
      throw new IllegalStateException("configuration error: engine resolves to null or empty.");
    }

    if (engine.toLowerCase().equals("xalan")) {
      engine = "org.apache.xalan.processor.TransformerFactoryImpl";
    } else if (engine.toLowerCase().equals("saxon")) {
      engine = "net.sf.saxon.TransformerFactoryImpl";
    } else if (engine.indexOf(".") > -1) {
      // do nothing - it's apparently a classname
      // We'll try to load and use it.
    } else {
      throw new IllegalStateException("configuration error: unknown XSLT engine: " + engine);
    }
    return engine;
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
      return fileResourceCache.get(ref.substring(7, ref.length()));
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
    return properties.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("param_"))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
      transformer = CustomTransformerFactory.createTransformer(cacheKey);
      CustomXsltErrorListener listener = new CustomXsltErrorListener(msgCtxt, debug);
      transformer.setErrorListener(listener);
      Source input = getTransformInput(msgCtxt);

      Map<String, String> params = paramProperties();
      // pass all specified parameters to the transform
      for (Map.Entry<String, String> entry : params.entrySet()) {
        String key = entry.getKey();
        String[] parts = key.split("_");
        // sanity check - is this a param?
        if (parts.length == 2 && parts[0].equals("param")) {
          String value = entry.getValue();
          value = resolvePropertyValue(value, msgCtxt);
          value = maybeResolveUrlReference(value);
          String pName = parts[1];
          transformer.setParameter(pName, value);
        }
      }

      StreamResult xformOutput = new StreamResult(new java.io.StringWriter());
      transformer.transform(input, xformOutput);

      if (listener.getErrorCount() > 0) {
        throw new Exception(
            "Encountered " + listener.getErrorCount() + " errors while transforming");
      }

      // set the result into a context variable
      String xformResult = xformOutput.getWriter().toString().trim();
      String outputVariable = getOutputVar(msgCtxt);
      msgCtxt.setVariable(outputVariable, xformResult);
      calloutResult = ExecutionResult.SUCCESS;
    } catch (Exception e) {
      if (debug) System.out.println(CalloutUtil.getStackTraceAsString(e));
      msgCtxt.setVariable(varName("exception"), e.toString());
      msgCtxt.setVariable(varName("error"), e.getMessage());
      if (e instanceof TransformerCreationException) {
        msgCtxt.setVariable(
            varName("additionalInformation"),
            ((TransformerCreationException) e).getAdditionalInformation());
      }
    }

    return calloutResult;
  }
}
