package cz.incad.cdk.cdkharvester;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import cz.incad.cdk.cdkharvester.changeindex.AddField;
import cz.incad.cdk.cdkharvester.changeindex.ChangeField;
import cz.incad.cdk.cdkharvester.changeindex.PrivateConnectUtils;
import cz.incad.cdk.cdkharvester.changeindex.ResultsUtils;
import cz.incad.cdk.cdkharvester.commands.SupportedCommands;
import cz.incad.cdk.cdkharvester.process.foxml.ProcessFOXML;
import cz.incad.cdk.cdkharvester.process.solr.ProcessSOLRXML;
import cz.incad.cdk.cdkharvester.utils.FilesUtils;
import cz.incad.kramerius.FedoraAccess;
import cz.incad.kramerius.fedora.om.RepositoryException;
import cz.incad.kramerius.utils.BasicAuthenticationClientFilter;
import cz.incad.kramerius.utils.IOUtils;
import cz.incad.kramerius.utils.conf.KConfiguration;
import cz.incad.kramerius.utils.pid.PIDParser;
import org.apache.commons.configuration.Configuration;
import org.w3c.dom.Document;

import javax.ws.rs.core.MediaType;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public abstract class AbstractCDKSourceHarvestProcess {

    protected List<ProcessFOXML> foxmlProcessingChain = new ArrayList<ProcessFOXML>();
    protected List<ProcessSOLRXML> solrProcessingChain = new ArrayList<>();


    protected File batchFolders;

    protected Transformer transformer;
    protected String k4Url;
    protected String sourceName;
    protected String collectionPid;
    protected String userName;
    protected String pswd;

    public static WebResource client(String url, String userName, String pswd) {
        Client c = Client.create();
        int connectionTimeout = getConfig().getInt("cdk.client.connection.timeout", 2000);
        int readTimeout = getConfig().getInt("cdk.client.read.timeout", 600000);
        CDKImportProcess.logger.info("connection timeout: " + connectionTimeout);
        CDKImportProcess.logger.info("read timeout: " + readTimeout);
        c.setConnectTimeout(connectionTimeout);
        c.setReadTimeout(readTimeout);
        WebResource r = c.resource(url);
        r.addFilter(new BasicAuthenticationClientFilter(userName, pswd));
        return r;
    }

    /**
     * Pipes everything from the reader to the writer via a buffer
     */
    private static void pipe(Reader reader, Writer writer) throws IOException {
        char[] buf = new char[1024];
        int read = 0;
        while ((read = reader.read(buf)) >= 0) {
            writer.write(buf, 0, read);
        }
        writer.flush();
    }

    /**
     * Pipes everything from the reader to the writer via a buffer except lines
     * starting with '<?'
     */
    private static void pipeString(Reader reader, StringBuilder writer) throws IOException {
        char[] buf = new char[1024];
        int read = 0;
        while ((read = reader.read(buf)) >= 0) {
            if (!(buf[0] == '<' && buf[1] == '?')) {
                writer.append(buf, 0, read);
            }
        }
    }

    /**
     * Batch mode properties
     */
    protected int getBatchModeSize() {
        return KConfiguration.getInstance().getConfiguration().getInteger("cdk.prepareFOXML.batch.size", 400);
    }

    /**
     * threshold properties
     */
    protected boolean getThresholdMode() {
        return KConfiguration.getInstance().getConfiguration().getBoolean("cdk.prepareFOXML.batch.threshold.enabled", true);
    }

    protected int getThresholdIteration() {
        return KConfiguration.getInstance().getConfiguration().getInt("cdk.prepareFOXML.batch.threshold.iterations", 5);
    }

    protected boolean getThresholdWithDelay() {
        return KConfiguration.getInstance().getConfiguration().getBoolean("cdk.prepareFOXML.batch.threshold_with_delay.enabled", false);
    }

    protected int getThresholdWithDelayIteration() {
        return KConfiguration.getInstance().getConfiguration().getInt("cdk.prepareFOXML.batch.threshold_with_delay.iterations", 5);
    }

    protected int getThresholdWithDelayNumber() {
        return KConfiguration.getInstance().getConfiguration().getInt("cdk.prepareFOXML.batch.threshold_with_delay.delaytime", 100);
    }

    protected void initTransformations() throws TransformerFactoryConfigurationError, TransformerConfigurationException {
        TransformerFactory tfactory = TransformerFactory.newInstance();
        InputStream stylesheet = this.getClass().getResourceAsStream("/cz/incad/cdk/cdkharvester/tr.xsl");
        StreamSource xslt = new StreamSource(stylesheet);
        //transformer = tfactory.newTransformer(xslt);
        this.setTransformer(tfactory.newTransformer(xslt));
    }

    public Transformer getTransformer() {
        return transformer;
    }

    public void setTransformer(Transformer transformer) {
        this.transformer = transformer;
    }

    protected void processFoxmlBatch() throws IOException, InterruptedException {
        File batchFolders = FilesUtils.batchFolders(this.sourceName);
        File subFolder = new File(batchFolders, FilesUtils.FOXML_FILES);
        if (getThresholdMode()) {
            List<String> params = new ArrayList<String>();
            params.add(subFolder.getAbsolutePath());
            params.add("" + getThresholdIteration());
            if (getThresholdWithDelay()) {
                params.add("" + getThresholdWithDelayIteration());
            }
            SupportedCommands.FEDORA_THRESHOLD.doCommand(params.toArray(new String[params.size()]));
        } else {
            SupportedCommands.FEDORA.doCommand(new String[]{subFolder.getAbsolutePath()});
        }
    }

    protected void processSolrXmlBatch() throws IOException {
        File batchFolders = FilesUtils.batchFolders(this.sourceName);
        File subFolder = new File(batchFolders, FilesUtils.SOLRXML_FILES);
        SupportedCommands.SOLR.doCommand(new String[]{subFolder.getAbsolutePath()});
    }

    protected static Configuration getConfig() {
        return KConfiguration.getInstance().getConfiguration();
    }

    protected void storeFOXMLToDisk(String pid, InputStream is) throws IOException {
        CDKImportProcess.logger.info("storing foxml to disk; preparing the batch");
        FilesUtils.dumpXMLS(this.sourceName, FilesUtils.FOXML_FILES, is, pid);
    }

    protected void storeSOLRXMLToDisk(String pid, InputStream is) throws IOException {
        CDKImportProcess.logger.info("storing solrxml to disk; preparing the batch");
        FilesUtils.dumpXMLS(this.sourceName, FilesUtils.SOLRXML_FILES, is, pid);
    }

    protected void prepareFOXML(String name, InputStream foxml, String pid) throws Exception {
        if (foxml == null) {
            CDKImportProcess.logger.info("No inputstream for foxml");
            return;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        IOUtils.copyStreams(foxml, bos);

        InputStream processingStream = new ByteArrayInputStream(bos.toByteArray());
        for (int i = 0, ll = this.foxmlProcessingChain.size(); i < ll; i++) {
            ProcessFOXML unit = foxmlProcessingChain.get(i);
            processingStream = new ByteArrayInputStream(unit.process(name, this.k4Url, pid, processingStream));
        }
        storeFOXMLToDisk(pid, processingStream);
    }

    protected InputStream foxml(String pid, String url) {
        WebResource r = client(url, this.userName, this.pswd);
        try {
            return r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
        } catch (UniformInterfaceException ex2) {
            if (ex2.getResponse().getStatus() == 404) {
                CDKImportProcess.logger.log(Level.WARNING, "Call to {0} failed with message {1}. Skyping document.",
                        new Object[]{url, ex2.getResponse().toString()});
                return null;
            } else {
                CDKImportProcess.logger.log(Level.WARNING, "Call to {0} failed. Retrying...", url);
                return r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
            }
        } catch (Exception ex) {
            CDKImportProcess.logger.log(Level.WARNING, "Call to {0} failed. Retrying...", url);
            return r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
        }
    }

    /**
     * Reads data from the data reader and posts it to solr, writes the response
     * to output
     */
    // TODO: REWRITE
    protected void postData(Reader data, StringBuilder output) throws Exception {

        URL solrUrl = null;
        String solrUrlString = getSolrUpdateEndpoint();
        try {
            solrUrl = new URL(solrUrlString);
        } catch (MalformedURLException e) {
            throw new Exception("solrUrl=" + solrUrlString + ": ", e);
        }
        HttpURLConnection urlc = null;
        String POST_ENCODING = "UTF-8";
        try {
            urlc = (HttpURLConnection) solrUrl.openConnection();
            urlc.setConnectTimeout(getConfig().getInt("http.timeout", 10000));
            try {
                urlc.setRequestMethod("POST");
            } catch (ProtocolException e) {
                throw new Exception("Shouldn't happen: HttpURLConnection doesn't support POST??", e);
            }
            urlc.setDoOutput(true);
            urlc.setDoInput(true);
            urlc.setUseCaches(false);
            urlc.setAllowUserInteraction(false);
            urlc.setRequestProperty("Content-type", "text/xml; charset=" + POST_ENCODING);

            OutputStream out = urlc.getOutputStream();

            try {
                Writer writer = new OutputStreamWriter(out, POST_ENCODING);
                pipe(data, writer);
                writer.close();
            } catch (IOException e) {
                throw new Exception("IOException while posting data", e);
            } finally {
                if (out != null) {
                    out.close();
                }
            }

            InputStream in = urlc.getInputStream();
            int status = urlc.getResponseCode();
            StringBuilder errorStream = new StringBuilder();
            try {
                if (status != HttpURLConnection.HTTP_OK) {
                    errorStream.append("postData URL=").append(solrUrlString).append(" HTTP response code=")
                            .append(status).append(" ");
                    throw new Exception("URL=" + solrUrlString + " HTTP response code=" + status);
                }
                Reader reader = new InputStreamReader(in);
                pipeString(reader, output);
                reader.close();
            } catch (IOException e) {
                throw new Exception("IOException while reading response", e);
            } finally {
                if (in != null) {
                    in.close();
                }
            }

            InputStream es = urlc.getErrorStream();
            if (es != null) {
                try {
                    Reader reader = new InputStreamReader(es);
                    pipeString(reader, errorStream);
                    reader.close();
                } catch (IOException e) {
                    throw new Exception("IOException while reading response", e);
                } finally {
                    es.close();
                }
            }
            if (errorStream.length() > 0) {
                throw new Exception("postData error: " + errorStream.toString());
            }

        } catch (IOException e) {
            throw new Exception("Solr has throw an error. Check tomcat log. " + e);
        } finally {
            if (urlc != null) {
                urlc.disconnect();
            }
        }
    }

    protected String getSolrUpdateEndpoint() {
        String solrUrlString = getConfig().getString("solrHost") + "/update";
        return solrUrlString;
    }

    protected String getSolrSelectEndpoint() {
        String solrUrlString = getConfig().getString("solrHost") + "/select";
        return solrUrlString;
    }

    protected void commit() throws java.rmi.RemoteException, Exception {
        String s = "<commit />";
        CDKImportProcess.logger.log(Level.FINE, "commit");

        postData(new StringReader(s), new StringBuilder());
    }

    protected void processBatches() throws Exception {
        processFoxmlBatch();
        processSolrXmlBatch();
        commit();
        FilesUtils.deleteFolder(new File(batchFolders, FilesUtils.FOXML_FILES));
        FilesUtils.deleteFolder(new File(batchFolders, FilesUtils.SOLRXML_FILES));
    }

    protected void initVariables(String url, String name, String collectionPid, String userName, String pswd) {
        this.k4Url = url;
        this.sourceName = name;
        this.collectionPid = collectionPid;
        this.userName = userName;
        this.pswd = pswd;
    }

    public String getCollectionPid() {
        return collectionPid;
    }

    protected void changeTranformationVariables() {
        getTransformer().setParameter("collectionPid", getCollectionPid());
        getTransformer().setParameter("solr_url", getSolrSelectEndpoint());
    }

    protected void prepareIndex(String name, String pid, boolean replace) throws Exception {
        org.json.JSONObject results = findDocFromCurrentIndex(pid);
        if (!replace && ResultsUtils.docsExists(results)) {
            if (ResultsUtils.collectionExists(results)) {
                List<String> collections = ResultsUtils.dissectCollections(results);
                if (!collections.contains(this.getCollectionPid())) {
                    AddField addField = new AddField(pid, "collection", this.getCollectionPid());
                    addField.addValueToArray(getSolrUpdateEndpoint());
                }
            } else {
                ChangeField chField = new ChangeField(pid, "collection", this.getCollectionPid());
                chField.changeField(getSolrUpdateEndpoint());
            }
        } else {

            try {
                String url = k4Url + "/api/" + CDKImportProcess.API_VERSION + "/cdk/" + pid + "/solrxml";
                InputStream t = solrxml(url);

                byte[] rawData = IOUtils.bos(t, true);
                ByteArrayInputStream processingStream = new ByteArrayInputStream(rawData);
                StreamResult destStream = new StreamResult(new StringWriter());
                changeTranformationVariables();
                getTransformer().transform(new StreamSource(processingStream), destStream);

                StringWriter sw = (StringWriter) destStream.getWriter();
                processingStream = new ByteArrayInputStream(sw.toString().getBytes("UTF-8"));

                for (int i = 0, ll = this.solrProcessingChain.size(); i < ll; i++) {
                    ProcessSOLRXML unit = solrProcessingChain.get(i);
                    processingStream = new ByteArrayInputStream(unit.process(name, this.k4Url, pid, processingStream));
                }

                //logger.info(sw.toString());
                storeSOLRXMLToDisk(pid, new ByteArrayInputStream(IOUtils.bos(processingStream, true)));

            } catch (UniformInterfaceException e) {
                CDKImportProcess.logger.info("cannot prepareIndex document");
            }
        }
    }

    protected InputStream solrxml(String url) {
        WebResource r = client(url, this.userName, this.pswd);
        InputStream t = r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
        return t;
    }

    public org.json.JSONObject findDocFromCurrentIndex(String pid)
            throws UnsupportedEncodingException, URISyntaxException {
        org.json.JSONObject results = PrivateConnectUtils.findDoc(getSolrSelectEndpoint(), pid);
        return results;
    }

    protected void replicate(String name, String pid, boolean replace) throws Exception {
        String url = k4Url + "/api/" + CDKImportProcess.API_VERSION + "/cdk/" + pid + "/foxml?collection=" + getCollectionPid();
        CDKImportProcess.logger.log(Level.FINE, "get foxml from origin {0}...", url);

        PIDParser parser = new PIDParser(pid);
        if (!parser.isPagePid() && !pid .contains("/@")) {
            if (!Utils.getSkipList().contains(pid)) {
                InputStream t = foxml(pid, url);
                prepareFOXML(name, t, pid);
                prepareIndex(name, pid, replace);
            } else {
                CDKImportProcess.logger.log(Level.INFO,"skipping pid {0} because of configuration",pid);
            }
        } else {
            prepareIndex(name, pid, replace);
        }
    }

    protected boolean pidExists(String pid, FedoraAccess fa) throws IOException {
        try {
            return fa.getInternalAPI().objectExists(pid);
        } catch (RepositoryException e) {
            CDKImportProcess.logger.log(Level.SEVERE,e.getMessage(),e);
            return false;
        } catch (Exception e) {
            CDKImportProcess.logger.log(Level.SEVERE,e.getMessage(),e);
            return false;
        }
    }
}
