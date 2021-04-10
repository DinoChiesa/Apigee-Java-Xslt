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
package com.google.apigee.callouts.xslt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

public class CustomTransformerFactory {

  private CustomTransformerFactory() {}

  /** This creates a Transformer if not already present in the pool. */
  public static Transformer createTransformer(String key) throws Exception {
    Transformer t = null;
    String[] parts = key.split("-", 2);
    String engine = parts[0];
    String xslt = parts[1];
    TransformerFactory tf = TransformerFactory.newInstance(engine, null);
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
    } catch (javax.xml.transform.TransformerConfigurationException tce1) {
      if (errorListener.getXsltError() != null) {
        throw new TransformerCreationException(
            tce1.getMessage(), errorListener.getXsltError(), tce1);
      } else {
        throw tce1;
      }
    }
    return t;
  }

  private static InputStream getResourceAsStream(String resourceName) throws IOException {
    // forcibly prepend a slash
    if (!resourceName.startsWith("/")) {
      resourceName = "/" + resourceName;
    }
    if (!resourceName.startsWith("/resources")) {
      resourceName = "/resources" + resourceName;
    }
    InputStream in = CustomTransformerFactory.class.getResourceAsStream(resourceName);

    if (in == null) {
      throw new IOException("resource \"" + resourceName + "\" not found");
    }

    return in;
  }

  private static boolean isValidURL(String url) {
    try {
      URL u = new URL(url);
      u.toURI();
    } catch (MalformedURLException e) {
      return false;
    } catch (URISyntaxException e) {
      return false;
    }
    return true;
  }

  private static Source convertXsltToSource(String xslt) throws IOException {
    // check for the kind of xslt. URI, filename, or string
    Source source = null;

    if (isValidURL(xslt)) {
      // It is a URL, therefore instantiate StreamSource directly from URI
      // varName = prefix + "_xslturl";
      // msgCtxt.setVariable(varName, xslt);
      source = new StreamSource(xslt);
    } else if (xslt.endsWith(".xsl") || xslt.endsWith(".xslt")) {
      // assume this is a stream resource in the JAR
      source = new StreamSource(getResourceAsStream(xslt));
    } else if (xslt.startsWith("<") && xslt.endsWith("stylesheet>")) {
      // assume this is a string containing an XSLT
      InputStream in = new ByteArrayInputStream(xslt.getBytes(StandardCharsets.UTF_8));
      source = new StreamSource(in);
    } else {
      throw new IllegalStateException("configuration error: invalid xslt");
    }
    return source;
  }

  /**
   * This class simply catches and stores the most recent error, for later retrieval. Last write
   * wins.
   */
  static final class SimpleErrorListener implements ErrorListener {
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
