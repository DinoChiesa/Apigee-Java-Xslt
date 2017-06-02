<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                version="1.0">
  <xsl:output method="xml"
              omit-xml-declaration="yes"
              indent="yes"/>

  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

  <xsl:template match="OperationName">
    <xsl:copy>
      <xsl:apply-templates select="ElementX" />
      <xsl:apply-templates select="ElementY" />
      <xsl:apply-templates select="ElementZ" />
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
