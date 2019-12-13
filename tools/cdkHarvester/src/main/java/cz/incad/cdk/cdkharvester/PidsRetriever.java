/*
 * Copyright (C) 2013 Alberto Hernandez
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package cz.incad.cdk.cdkharvester;

import com.sun.jersey.api.client.WebResource;

import cz.incad.kramerius.utils.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Level;

import javax.ws.rs.core.MediaType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import cz.incad.kramerius.utils.StringUtils;
import org.apache.commons.httpclient.util.URIUtil;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * TODO: Rewrite it
 * @author alberto
 */
public class PidsRetriever {

    static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(PidsRetriever.class.getName());

    public static final SimpleDateFormat STANDARD_SIMPLE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    public static final SimpleDateFormat WO_MILLISECONDS_SIMPLE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    String harvestUrl;
    String userName;
    String pswd;
    String initial_date;
    String actual_date;
    XPathFactory factory = XPathFactory.newInstance();
    XPath xpath;
    XPathExpression expr;
    final String APIURL_PREFIX = "/api/v4.6/cdk/prepare?rows=500&date=";
    Queue<Map.Entry<String, String>> qe;


    private String dateLimit;

    public PidsRetriever(String date, String k4Url, String userName, String pswd, String dateLimit) throws ParseException {
        this.initial_date = date;
        this.actual_date = date;
        this.harvestUrl = k4Url + APIURL_PREFIX;
        this.userName = userName;
        this.pswd = pswd;
        xpath = factory.newXPath();
        qe = new LinkedList<Map.Entry<String, String>>();

        this.dateLimit = dateLimit;
    }

    public boolean hasNext() throws Exception {
        if (!qe.iterator().hasNext()) {
            getDocs();
        }
        boolean be = qe.iterator().hasNext();
        if (be) {
            Map.Entry<String, String> peek = qe.peek();
            if (dateLimitTouched(peek.getValue())) {
                return false;
            }
        }
        return be;
    }

    public Map.Entry<String, String> next() throws ParseException {
        Map.Entry<String, String> entry = qe.poll();
        
        actual_date = entry.getValue();
        return entry;
    }

    private void getDocs() throws Exception {
        String urlStr = harvestUrl + URIUtil.encodeQuery(actual_date);
        if (dateLimitTouched(this.actual_date)) return;


        logger.log(Level.INFO, "urlStr: {0}", urlStr);
        org.w3c.dom.Document solrDom = solrResults(urlStr);
        String xPathStr = "/response/result/@numFound";
        expr = xpath.compile(xPathStr);
        int numDocs = Integer.parseInt((String) expr.evaluate(solrDom, XPathConstants.STRING));
        logger.log(Level.INFO, "numDocs: {0}", numDocs);
        if (numDocs > 0) {
            xPathStr = "/response/result/doc/str[@name='PID']";
            expr = xpath.compile(xPathStr);
            NodeList nodes = (NodeList) expr.evaluate(solrDom, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                String pid = node.getFirstChild().getNodeValue();
                String to = node.getNextSibling().getFirstChild().getNodeValue();
                qe.add(new DocEntry(pid, to));
            }
        }
    }

    private boolean dateLimitTouched(String testingDate) throws ParseException {
        if (this.dateLimit != null && StringUtils.isAnyString(this.dateLimit)) {
            Date parsedActual = null;
            try {
                parsedActual = STANDARD_SIMPLE_DATE_FORMAT.parse(testingDate);
            } catch (ParseException e) {
                parsedActual = WO_MILLISECONDS_SIMPLE_DATE_FORMAT.parse(testingDate);
            }
            Date parsedLimit = STANDARD_SIMPLE_DATE_FORMAT.parse(this.dateLimit);
            boolean after = parsedActual.after(parsedLimit);
            if (after) {
                logger.info("Date limit touched "+testingDate);
                return true;
            }
        }
        return false;
    }

    protected org.w3c.dom.Document solrResults(String urlStr) throws SAXException, IOException, ParserConfigurationException {
        WebResource r = AbstractCDKSourceHarvestProcess.client(urlStr,userName,pswd);
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		InputStream is = null;
		try {
        	is = r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
            return builder.parse(is);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Retrying...", ex);
            is = r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
            return builder.parse(is);
        } finally {
        	IOUtils.tryClose(is);
        }
	}

    final class DocEntry<K, V> implements Map.Entry<K, V> {

        private final K key;
        private V value;

        public DocEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            V old = this.value;
            this.value = value;
            return old;
        }
    }
}
