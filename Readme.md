# Java callout for Xslt

This directory contains the Java source code required to compile a Java
callout for Apigee Edge that does XSLT. There's a built-in policy that
does XSLT; this callout is different in that it is a bit more flexible.

* the person configuring the policy can specify the XSLT sheet in a context variable.
  This is nice because it means the XSLT can be dynamically determined at runtime.
* Likewise, the input and output can be specified in a context variable. 
* It is possible to specify an XSLT source available at an HTTP endpoint
* It is possible to specify parameters for the XSLT that are retrieved at an HTTP endpoint
* It is possible to specify saxon or xalan as the XSLT engine.
* You can use the data: URI scheme to instantiate a document in the XSL.


## Using this policy

You do not need to build the source code in order to use the policy in Apigee Edge. 
All you need is the built JAR, and the appropriate configuration for the policy. 
If you want to build it, feel free.  The instructions are at the bottom of this readme. 


1. copy the jar file, available in  target/edge-custom-xslt-1.0.5.jar , if you have built the jar, or in [the repo](bundle/apiproxy/resources/java/edge-custom-xslt-1.0.5.jar) if you have not, to your apiproxy/resources/java directory. Also copy all the required dependencies. (See below) You can do this offline, or using the graphical Proxy Editor in the Apigee Edge Admin Portal. 

2. include a Java callout policy in your
   apiproxy/resources/policies directory. It should look
   like this:
   ```xml
    <JavaCallout name='Java-Xslt'>
      <Properties>
        <Property name='xslt'>file://xslt-name-here.xsl</Property>
           ....
      </Properties>
      <ClassName>com.google.apigee.edgecallouts.xslt.XsltCallout</ClassName>
      <ResourceURL>java://edge-custom-xslt-1.0.5.jar</ResourceURL>
    </JavaCallout>
   ```
   
5. use the Edge UI, or a command-line tool like pushapi (See
   https://github.com/carloseberhardt/apiploy) or similar to
   import the proxy into an Edge organization, and then deploy the proxy . 

6. use a client to generate and send http requests to tickle the proxy. 



## Notes

There is one callout class, com.google.apigee.edgecallouts.xslt.XsltCallout ,
which performs an XSL Transform . 

You must configure the callout with Property elements in the policy
configuration.

Examples follow. 

To use this callout, you will need an API Proxy, of course. 

The callout pools its javax.xml.transform.Transformer objects, for
better performance at high concurrency.


## Example 1: Perform a simple transform

```xml
<JavaCallout name='JavaCallout-Xslt-1'>
  <Properties>
     <Property name='xslt'>{xslturl}</Property>
     <Property name='engine'>saxon</Property>
     <Property name='input'>response</Property>
     <Property name='output'>response.content</Property>
  </Properties>
  <ClassName>com.google.apigee.edgecallouts.xslt.XsltCallout</ClassName>
  <ResourceURL>java://edge-custom-xslt-1.0.5.jar</ResourceURL>
</JavaCallout>
```

The xslt property specifies the sheet that defines the transform.  This
can be one of 4 forms:

* a file reference, like file://filename.xsl 
* a url beginning with http:// or https:// 
* a string that begins with < and ends with stylesheet>. In other words, you can directly embed the XSL into the configuration for the policy.
* a variable enclosed in curly-braces that resolves to one of the above. 

If a filename, the file must be present as a resource in the JAR file.
This requires you to re-package the jar file. The structure of the jar
must be like so:

```
meta-inf/ 
meta-inf/manifest.mf 
com/ 
com/google/ 
com/google/apigee/
com/google/apigee/edgecallouts/
com/google/apigee/edgecallouts/xslt/
com/google/apigee/edgecallouts/xslt/XsltCallout.class
resources/ 
resources/filename.xsl
```

You can have as many XSLs in the resources directory as you like. 

If a URL, the URL must return a valid XSL. The URL should be accessible
from the message processor. The contents of the URL will be cached.


The engine property is optional, and defaults to saxon, which is included in the Apigee Edge runtime. You can also
specify xalan here. If you do that you will need to supply the xalan jars. 

The input property specifies where to find the content to be
transformed. This must be a variable name.  Do not use curly-braces. If
this variable resolves to a Message, then the transform will apply to
Message.content.

The output property is the variable to contain the transformed
output. This property is optional. If not present, it uses
"message.content".


## Example 2: a Parameterized transform

You can pass parameters to the XSL, like so: 

```xml
<JavaCallout name='JavaCallout-Xslt-1'>
  <Properties>
     <Property name='xslt'>{xslturl}</Property>
     <Property name='engine'>saxon</Property>
     <Property name='input'>response</Property>
     <Property name='output'>response.content</Property>
     <!-- arbitrary params to pass to the XSLT -->
     <Property name='param_x'>string value of param</Property>
     <Property name='param_y'>file://file-embeded-in-jar.xsd</Property>
     <Property name='param_z'>{variable-containing-one-of-the-above}</Property>
  </Properties>
  <ClassName>com.google.apigee.edgecallouts.xslt.XsltCallout</ClassName>
  <ResourceURL>java://edge-custom-xslt-1.0.5.jar</ResourceURL>
</JavaCallout>
```

As you can see, this configuration uses a name prefix convention for
properties that should be passed to the XSL as parameters.  In this
case, params x and y will be passed to the XSL. The value of these
params can be determined by a context variable, if you enclose the text
node in curly-braces. If you use parameters, you need to ingest them
into the XSL like so:

```xml
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <!-- converted is set in the XSL policy definition -->
  <xsl:param name="x" select="''"/>

  ...

</xsl:stylesheet>
```

## Example 3: instantiating a document from a string parameter

Suppose you would like to instantiate an XML document within the XSL,
from a string parameter. You would configure the policy like this:

```xml
<JavaCallout name='JavaCallout-Xslt-1'>
  <Properties>
     <Property name='xslt'>{xslturl}</Property>
     <Property name='engine'>saxon</Property>
     <Property name='input'>response</Property>
     <Property name='output'>response.content</Property>
     <!-- parameter to pass to the XSLT -->
     <Property name='param_myxsd'>{variable-containing-xsd-string}</Property>
  </Properties>
  <ClassName>com.google.apigee.edgecallouts.xslt.XsltCallout</ClassName>
  <ResourceURL>java://edge-custom-xslt-1.0.5.jar</ResourceURL>
</JavaCallout>
```

Then, to use the parameter and instantiate an XML document from it, you would use the document() function, like this:

```xml
<xsl:stylesheet version="2.0"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <xsl:param name="myxsd" select="''"/>
  <xsl:variable name="xsd" select="document(concat('data:text/xml,',$myxsd))"/>

  ...

</xsl:stylesheet>
```

This XSL uses the data: URL scheme as described in [RFC2397](https://tools.ietf.org/html/rfc2397).
The custom URIResolver implemented here handles only mime types of text/xml .



## Building

Building from source requires Java 1.7, and Maven. 

1. unpack (if you can read this, you've already done that).

2. Before building _the first time_, configure the build on your machine by loading the Apigee jars into your local cache:
  ```
  ./buildsetup.sh
  ```

3. Build with maven.  
  ```
  mvn clean package
  ```
  This will build the jar and also run all the tests.


Pull requests are welcomed!


## Build Dependencies

- Apigee Edge expressions v1.0
- Apigee Edge message-flow v1.0
- Apache commons lang 2.6
- Apache commons validator 1.4.1
- Apache commons io 2.0.1
- Apache commons pool 1.6


## License

This material is Copyright 2017, Google Inc.
and is licensed under the [Apache 2.0 License](LICENSE). This includes the Java code as well as the API Proxy configuration. 



## Bugs

* The tests are incomplete.
* There is no sample API Proxy bundle.
