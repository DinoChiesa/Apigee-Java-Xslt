# Java callout for XSLT

This directory contains the Java source code required to compile a Java
callout for Apigee that does XSLT. There's a built-in policy that
does XSLT; this callout is different in that it is a bit more flexible.

* the person configuring the policy can specify the XSLT sheet in a context variable.
  This is nice because it means the XSLT can be dynamically determined at runtime.
* Likewise, the input and output can be specified in a context variable.
* It is possible to specify an XSLT source available at an HTTP endpoint
* It is possible to specify parameters for the XSLT that are retrieved at an HTTP endpoint
* It is possible to specify saxon or xalan as the XSLT engine.
* You can use the data: URI scheme to instantiate a document in the XSL.


## Disclaimer

This example is not an official Google product, nor is it part of an official Google product.

## Using this policy

You need to build the source code in order to use the policy in Apigee, in order to download all the dependencies.

The instructions to do so are at the bottom of this README.

After you build it,

1. copy the jar file, available in target/apigee-custom-xslt-20220708.jar , to
   your apiproxy/resources/java directory. Also copy all the required
   dependencies. You can do this offline, or using the graphical Proxy Editor in
   the Apigee Admin Portal.

2. include a Java callout policy in your
   apiproxy/resources/policies directory. It should look
   like this:
   ```xml
    <JavaCallout name='Java-Xslt'>
      <Properties>
        <Property name='xslt'>file://xslt-name-here.xsl</Property>
           ....
      </Properties>
      <ClassName>com.google.apigee.callouts.xslt.XsltCallout</ClassName>
      <ResourceURL>java://apigee-custom-xslt-20220708.jar</ResourceURL>
    </JavaCallout>
   ```

5. use the Edge UI, or a command-line tool like [importAndDeploy.js](https://github.com/DinoChiesa/apigee-edge-js/blob/master/examples/importAndDeploy.js) or similar to
   import the proxy into an Edge organization, and then deploy the proxy .

6. use a client to generate and send http requests to tickle the proxy.



## Notes

There is one callout class, com.google.apigee.callouts.xslt.XsltCallout ,
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
  <ClassName>com.google.apigee.callouts.xslt.XsltCallout</ClassName>
  <ResourceURL>java://apigee-custom-xslt-20220708.jar</ResourceURL>
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
com/google/apigee/callouts/
com/google/apigee/callouts/xslt/
com/google/apigee/callouts/xslt/XsltCallout.class
resources/
resources/filename.xsl
```

You can have as many XSLs in the resources directory as you like.

If a URL, the URL must return a valid XSL. The URL should be accessible
from the message processor. The contents of the URL will be cached, currently for 10 minutes. This cache period is not confgurable, but you could change it in the source and re-compile if you like.


The engine property is optional, and defaults to saxon, which is included in the Apigee runtime. You can also
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
     <Property name='param_y'>file://file-embedded-in-jar.xsd</Property>
     <Property name='param_z'>{variable-containing-one-of-the-above}</Property>
  </Properties>
  <ClassName>com.google.apigee.callouts.xslt.XsltCallout</ClassName>
  <ResourceURL>java://apigee-custom-xslt-20220708.jar</ResourceURL>
</JavaCallout>
```

As you can see, this configuration uses a name prefix convention for
properties that should be passed to the XSL as parameters.  In this
case, params x, y and z will be passed to the XSL. The value of these
params can be determined by a context variable, if you enclose the text
node in curly-braces. If you use parameters, you need to ingest them
into the XSL like so:

```xml
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <!--
    if param_x is set in the XSL policy definition, then
    the XSL can retrieve the value this way:
  -->
  <xsl:param name="x" select="''"/>

  ...

</xsl:stylesheet>
```

## Example 3: instantiating a document from a string parameter

Suppose you would like to instantiate an XML document within the XSL,
from a string parameter.

You would configure the policy like this:

```xml
<JavaCallout name='JavaCallout-Xslt-1'>
  <Properties>
     <Property name='xslt'>{xslturl}</Property>
     <Property name='engine'>saxon</Property>
     <Property name='input'>response</Property>
     <Property name='output'>response.content</Property>
     <!-- parameter to pass to the XSLT -->
     <Property name='param_myxmldoc'>{variable-containing-xml-string}</Property>
  </Properties>
  <ClassName>com.google.apigee.callouts.xslt.XsltCallout</ClassName>
  <ResourceURL>java://apigee-custom-xslt-20220708.jar</ResourceURL>
</JavaCallout>
```

Then, to use the parameter and instantiate an XML document from it, you would use the document() function, like this:

```xml
<xsl:stylesheet version="2.0"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <xsl:param name="myxmldoc" select="''"/>
  <xsl:variable name="xmldoc" select="document(concat('data:text/xml,',$myxmldoc))"/>

  ...

</xsl:stylesheet>
```

As one specific example where you might want to do this, imagine you have an XSD (which itself is an XML document),
which defines a schema, containing elements in a particular order. And you would like to re-order the elements in a given document, to match the order given in the XSD.

In that case, the policy configuration would be like this:

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
  <ClassName>com.google.apigee.callouts.xslt.XsltCallout</ClassName>
  <ResourceURL>java://apigee-custom-xslt-20220708.jar</ResourceURL>
</JavaCallout>
```

And the XSL might be like this:

```xml
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema">
  <xsl:output indent="yes"
              method="xml"
              omit-xml-declaration="yes"
              />
  <xsl:strip-space elements="*"/>

  <xsl:param name="myxsd" select="''"/>
  <xsl:variable name="xsd" select="document(concat('data:text/xml,',$myxsd))"/>

  <xsl:variable name="input">
    <xsl:copy-of select="/"/>
  </xsl:variable>

  <xsl:template match="/*">
    <xsl:variable name="firstContext" select="name()"/>
    <xsl:variable name="xsdElems" select="$xsd/xs:schema/xs:element[@name=$firstContext]/xs:complexType/xs:sequence/xs:element/@name"/>
    <xsl:element name="{$firstContext}">
      <xsl:for-each select="$xsdElems">
        <xsl:variable name="secondContext" select="."/>
        <xsl:element name="{$secondContext}">
          <xsl:value-of select="$input/*/*[@name=$secondContext]/@value"/>
        </xsl:element>
      </xsl:for-each>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
```

These example XSL documents uses the data: URL scheme as described in [RFC2397](https://tools.ietf.org/html/rfc2397).
The custom URIResolver implemented here handles only mime types of text/xml .



## Building

Building from source requires Java 1.8, and Maven.

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


Please do send pull requests!



## License

This material is Copyright (c) 2017-2021, Google LLC.  and is licensed under
the [Apache 2.0 License](LICENSE). This includes the Java code as well
as the API Proxy configuration.


## Support

This callout is open-source software, and is not a supported part of Apigee Edge.
If you need assistance, you can try inquiring on
[The Apigee Community Site](https://community.apigee.com).  There is no service-level
guarantee for responses to inquiries regarding this callout.


## Bugs

* The tests are incomplete.
* There is no sample API Proxy bundle.
