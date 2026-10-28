package cn.childtv.core
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
/** Android's XML parser need not implement Xerces-specific setFeature flags. */
internal fun parseLocalXml(xml:String,namespaceAware:Boolean=false):org.w3c.dom.Document {
 require(xml.length<=4*1024*1024 && !xml.contains("<!DOCTYPE",true) && !xml.contains("<!ENTITY",true)){"XML 禁止声明 DTD 或实体"}
 val factory=DocumentBuilderFactory.newInstance();factory.isNamespaceAware=namespaceAware;factory.isExpandEntityReferences=false
 val builder=factory.newDocumentBuilder();builder.setEntityResolver{_,_->throw SAXException("禁止外部实体")}
 return builder.parse(InputSource(StringReader(xml)))
}
