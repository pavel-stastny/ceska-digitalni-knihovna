package cz.incad.cdk.cdkharvester;


public class App 
{
    public static void main( String[] args ) throws Exception
    {
        CDKImportProcess p = new CDKImportProcess();
        //p.start("http://vmkramerius.incad.cz:8080/search", "alberto", "krameriusAdmin", "krameriusAdmin");
        p.start("http://localhost:8080/search", "alberto", "krameriusAdmin", "krameriusAdmin");
    }
}
