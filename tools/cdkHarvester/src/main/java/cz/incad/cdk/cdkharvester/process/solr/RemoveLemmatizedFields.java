package cz.incad.cdk.cdkharvester.process.solr;

import cz.incad.kramerius.utils.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.TransformerException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

public class RemoveLemmatizedFields implements ProcessSOLRXML {

    @Override
    public byte[] process(String url, String pid, InputStream is) throws Exception {
        Document document = XMLUtils.parseDocument(is, false);
        Element doc = XMLUtils.findElement(XMLUtils.findElement(document.getDocumentElement(), "result"), "doc");
        List<Element> elements1 = XMLUtils.getElements(doc, new XMLUtils.ElementsFilter() {
            @Override
            public boolean acceptElement(Element element) {
                String name = element.getAttribute("name");
                if (name.contains("_lemmatized")) {
                    return true;
                } else return false;
            }
        });

        for (Element toRemove :  elements1) {  toRemove.getParentNode().removeChild(toRemove); }

        document.normalize();

        byte[] barr2 = renderXML(document);
        return barr2;
    }

    private byte[] renderXML(Document document) throws TransformerException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        XMLUtils.print(document, bos);
        return bos.toByteArray();
    }
}
