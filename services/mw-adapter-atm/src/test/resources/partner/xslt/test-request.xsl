<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:fn="http://www.w3.org/2005/xpath-functions" exclude-result-prefixes="fn">
  <!-- A classpath partner stylesheet for SoapRouteLoaderTest (ticket 85). -->
  <xsl:template match="/fn:map"><b:Pay xmlns:b="urn:biller"><xsl:value-of select="fn:string[@key='a']"/></b:Pay></xsl:template>
</xsl:stylesheet>
