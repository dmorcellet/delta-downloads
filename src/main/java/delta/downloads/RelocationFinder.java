package delta.downloads;

import java.net.HttpURLConnection;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds relocations.
 * @author DAM
 */
public class RelocationFinder
{
  private static final Logger LOGGER=LoggerFactory.getLogger(RelocationFinder.class);

  /**
   * Finds relocation for the given URL, if any.
   * @param urlStr URL to use.
   * @return An URL or <code>null</code> if no relocation.
   */
  public String getRelocation(String urlStr)
  {
    String loc=null;
    HttpURLConnection conn=null;
    try
    {
      URL url=new URL(urlStr);
      conn=(HttpURLConnection)url.openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.connect();
      System.out.println(conn.getHeaderFields());
      int responseCode=conn.getResponseCode();
      if (responseCode==302)
      {
        loc=conn.getHeaderField("Location");
        if (loc!=null)
        {
          byte[] b=loc.getBytes("ISO8859-1");
          loc=new String(b,"UTF-8");
        }
      }
    }
    catch(Exception e)
    {
      LOGGER.error("",e);
    }
    finally
    {
      if (conn!=null)
      {
        conn.disconnect();
      }
      conn=null;
    }
    return loc;
  }

  /**
   * Main method for this tool.
   * @param args Not used.
   */
  public static void main(String[] args)
  {
    RelocationFinder finder=new RelocationFinder();
    String url="http://content.turbine.com/sites/lorebook.lotro.com/images/icons/item/instrument/eq_c_flute_tier7_v3.png";
    String loc=finder.getRelocation(url);
    System.out.println("loc="+loc);
  }
}
