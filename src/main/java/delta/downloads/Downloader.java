package delta.downloads;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import delta.common.utils.files.FileCopy;

/**
 * A download service.
 * @author DAM
 */
public class Downloader
{
  private static final Logger LOGGER=LoggerFactory.getLogger(Downloader.class);

  // Underlying service
  private CloseableHttpClient _client;
  // Configuration
  //private boolean _followsRedirects;
  //private String _charset;
  private boolean _storeCookies;
  // Statistics
  private long _downloads;
  private long _bytes;
  // Session information
  private HashMap<String,String> _cookies;

  /**
   * Constructor.
   */
  public Downloader()
  {
    SSLConnectionSocketFactory sslConnectionSocketFactory=new SSLConnectionSocketFactory(SSLContexts.createDefault(),new String[] {"TLSv1.2"},null,
        SSLConnectionSocketFactory.getDefaultHostnameVerifier());

    _client=HttpClientBuilder.create().setSSLSocketFactory(sslConnectionSocketFactory).build();

    //_followsRedirects=true;
    //_charset=EncodingNames.UTF_8;
    _storeCookies=false;
    _downloads=0;
    _bytes=0;
    _cookies=new HashMap<String,String>();
  }

  /**
   * Get the cookies map.
   * @return a map.
   */
  public Map<String,String> getCookies()
  {
    return _cookies;
  }

  /**
   * Set the 'follow redirects' behavior.
   * @param followsRedirects <code>true</code> to follow redirect responses, <code>false</code> otherwise.
   */
  public void setFollowsRedirects(boolean followsRedirects)
  {
    //_followsRedirects=followsRedirects;
  }

  /**
   * Set the charset to use.
   * @param charset A charset identifier.
   */
  public void setCharset(String charset)
  {
    //_charset=charset;
  }

  /**
   * Set the value of the 'store cookies' flag.
   * @param storeCookies Flag value to set.
   */
  public void setStoreCookies(boolean storeCookies)
  {
    _storeCookies=storeCookies;
  }

  private interface ResultGetter<T>
  {
    public T getResult(HttpGet method, CloseableHttpResponse response) throws Exception;
  }

  private synchronized <T> T privateDowload(String url, ResultGetter<T> getter) throws DownloadException
  {
    if (LOGGER.isInfoEnabled())
    {
      LOGGER.info("Downloading from URL ["+url+"].");
    }
    T ret=null;
    HttpGet get=new HttpGet(url);
    try
    {
      /*
      get.setFollowRedirects(_followsRedirects);
      get.getParams().setHttpElementCharset(_charset);
      */

      CloseableHttpResponse response = _client.execute(get);
      int iGetResultCode=response.getStatusLine().getStatusCode();
      if (LOGGER.isInfoEnabled())
      {
        LOGGER.info("Status code : "+iGetResultCode);
      }
      if (iGetResultCode >= 200 && iGetResultCode < 300)
      {
        ret=getter.getResult(get,response);
      }
      if (_storeCookies)
      {
        catchCookies(get);
      }
    }
    catch (Exception e)
    {
      throw new DownloadException("Download error for ["+url+"]!",e);
    }
    finally
    {
      get.releaseConnection();
    }
    return ret;
  }

  private void updateStatistics(long length, long downloads)
  {
    _bytes+=length;
    _downloads+=downloads;
  }

  /**
   * Download an URL as a byte buffer.
   * @param url Source URL.
   * @return A byte buffer or <code>null</code>.
   * @throws DownloadException
   */
  public byte[] downloadBuffer(String url) throws DownloadException
  {
    ResultGetter<byte[]> getter=new ResultGetter<byte[]>()
    {
      public byte[] getResult(HttpGet method, CloseableHttpResponse response) throws Exception
      {
        byte[] ret=null;
        try
        {
          HttpEntity entity=response.getEntity();
          ret=EntityUtils.toByteArray(entity);
          if (ret!=null)
          {
            updateStatistics(ret.length,1);
          }
        }
        finally
        {
          response.close();
        }
        return ret;
      }
    };
    byte[] ret=privateDowload(url,getter);
    return ret;
  }

  /**
   * Download an URL as a string.
   * @param url Source URL.
   * @return A string or <code>null</code>.
   * @throws DownloadException
   */
  public String downloadString(String url) throws DownloadException
  {
    ResultGetter<String> getter=new ResultGetter<String>()
    {
      public String getResult(HttpGet method, CloseableHttpResponse response) throws Exception
      {
        String ret=null;
        try
        {
          HttpEntity entity=response.getEntity();
          ret=EntityUtils.toString(entity);
          if (ret!=null)
          {
            long length=entity.getContentLength();
            updateStatistics(length,1);
          }
        }
        finally
        {
          response.close();
        }
        return ret;
      }
    };
    String ret=privateDowload(url,getter);
    return ret;
  }

  /**
   * Download an URL into a file.
   * @param url Source URL.
   * @param to Target file.
   * @return <code>true</code> if file was successfully written, <code>false</code> otherwise.
   * @throws DownloadException
   */
  public boolean downloadToFile(String url, final File to) throws DownloadException
  {
    ResultGetter<Boolean> getter=new ResultGetter<Boolean>()
    {
      public Boolean getResult(HttpGet method, CloseableHttpResponse response) throws Exception
      {
        boolean ok=false;
        try
        {
          HttpEntity entity=response.getEntity();
          byte[] buffer=EntityUtils.toByteArray(entity);
          InputStream is=new ByteArrayInputStream(buffer);
          ok=FileCopy.copy(is,to);
          if (ok)
          {
            updateStatistics(to.length(),1);
          }
        }
        finally
        {
          response.close();
        }
        return Boolean.valueOf(ok);
      }
    };
    Boolean ret=privateDowload(url,getter);
    return (ret!=null)?ret.booleanValue():false;
  }

  /**
   * Download an URL into a file.
   * @param url Source URL.
   * @param to Target file.
   * @return <code>true</code> if file was successfully written, <code>false</code> otherwise.
   * @deprecated Use downloadToFile instead.
   */
  public boolean downloadPage(String url, File to)
  {
    boolean ret=false;
    try
    {
      ret=downloadToFile(url,to);
    }
    catch(DownloadException e)
    {
      LOGGER.error("Download error",e);
    }
    return ret;
  }

  /**
   * Download a page to a file using the POST method.
   * @param url Source URL.
   * @param to Target file.
   * @param parameters Parameters to post.
   * @return <code>true</code> if file was successfully written, <code>false</code> otherwise.
   */
  public boolean downloadPageAsPost(String url, File to, Map<String,String> parameters)
  {
    if (LOGGER.isInfoEnabled())
    {
      LOGGER.info("Downloading URL ["+url+"] to file ["+to+"]");
    }
    boolean ret=false;
    HttpPost post=new HttpPost(url);
    List<NameValuePair> form = new ArrayList<NameValuePair>();
    for(Map.Entry<String,String> parameter : parameters.entrySet())
    {
      form.add(new BasicNameValuePair(parameter.getKey(), parameter.getValue()));
    }
    UrlEncodedFormEntity entity = new UrlEncodedFormEntity(form, Consts.UTF_8);
    post.setEntity(entity);
    //System.out.println("Executing request " + post.getRequestLine());

    CloseableHttpResponse response = null;
    try
    {
      //get.setFollowRedirects(true);
      response = _client.execute(post);
      int iGetResultCode=response.getStatusLine().getStatusCode();
      if (LOGGER.isInfoEnabled())
      {
        LOGGER.info("Status code : "+iGetResultCode);
      }
      HttpEntity resultEntity=response.getEntity();
      byte[] buffer=EntityUtils.toByteArray(resultEntity);
      InputStream is=new ByteArrayInputStream(buffer);
      ret=FileCopy.copy(is,to);
      _bytes+=to.length();
      _downloads++;
      catchCookies(post);
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }
    finally
    {
      try
      {
        response.close();
      }
      catch(IOException ioe)
      {
        ioe.printStackTrace();
      }
    }
    return ret;
  }

  private void catchCookies(HttpRequestBase method)
  {
    _cookies.clear();
    Header[] headers=method.getAllHeaders();
    for(int i=0;i<headers.length;i++)
    {
      String name=headers[i].getName();
      if ("Cookie".equals(name))
      {
        String value=headers[i].getValue();
        int separator=value.indexOf(' ');
        if (separator!=-1)
        {
          String keyValue=value.substring(separator+1);
          separator=keyValue.indexOf('=');
          String cookieName=keyValue.substring(0,separator);
          String cookieValue=keyValue.substring(separator+1);
          separator=cookieValue.indexOf(';');
          if (separator!=-1)
          {
            cookieValue=cookieValue.substring(0,separator);
          }
          _cookies.put(cookieName,cookieValue);
        }
      }
    }
  }

  /**
   * Get the value of a cookie.
   * @param cookieName Name of the cookie to get.
   * @return A cookie value or <code>null</code> if not found.
   */
  public String getCookieValue(String cookieName)
  {
    return _cookies.get(cookieName);
  }

  /**
   * Get statistics.
   * @return a statistics string.
   */
  public String getStatistics()
  {
    return "Downloaded "+_downloads+" item(s) - "+((float)_bytes)/(1024*1024)+"Mo";
  }

  /**
   * Dispose all managed resources.
   */
  public void dispose()
  {
    _client=null;
    _cookies.clear();
  }
}
