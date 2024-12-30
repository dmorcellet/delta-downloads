package delta.downloads.async;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronous/Asynchronous downloads manager.
 * @author DAM
 */
public class DownloadsManager
{
  private static final Logger LOGGER=LoggerFactory.getLogger(DownloadsManager.class);

  private CloseableHttpAsyncClient _client;
  private Map<Integer,SingleAsyncDownloadManager> _tasks;
  private int _nextID;

  /**
   * Constructor.
   */
  public DownloadsManager()
  {
    _nextID=1;
    _tasks=new HashMap<Integer,SingleAsyncDownloadManager>();
    _client=buildClient();
  }

  /**
   * Synchronous download.
   * @param url URL to get.
   * @param to File to write to.
   * @param listener Download listener (optional).
   * @return the download task.
   */
  public DownloadTask syncDownload(String url, File to, DownloadListener listener)
  {
    DownloadTask task=newFileDownload(url,to);
    boolean startOK=startDownload(task,listener);
    if (startOK)
    {
      waitForTaskTermination(task);
    }
    return task;
  }

  /**
   * Synchronous download of a buffer.
   * @param url URL to get.
   * @param listener Download listener (optional).
   * @return the result buffer.
   */
  public byte[] syncDownloadBuffer(String url, DownloadListener listener)
  {
    DownloadTask task=newBufferDownload(url);
    boolean startOK=startDownload(task,listener);
    if (startOK)
    {
      waitForTaskTermination(task);
      DownloadState state=task.getDownloadState();
      if (state==DownloadState.OK)
      {
        BufferReceiver receiver=(BufferReceiver)task.getReceiver();
        return receiver.getBytes();
      }
      LOGGER.warn("Unexpected download state: {}",state);
    }
    return null;
  }

  /**
   * Build a new download task (to file).
   * @param url URL to get.
   * @param to File to write to.
   * @return A new download task.
   */
  public DownloadTask newFileDownload(String url, File to)
  {
    FileReceiver receiver=new FileReceiver(to);
    return newTask(url,receiver);
  }

  /**
   * Build a new download task (to buffer).
   * @param url URL to get.
   * @return A new download task.
   */
  public DownloadTask newBufferDownload(String url)
  {
    BufferReceiver receiver=new BufferReceiver();
    return newTask(url,receiver);
  }

  private DownloadTask newTask(String url, BytesReceiver receiver)
  {
    DownloadTask task=new DownloadTask(_nextID,url,receiver);
    SingleAsyncDownloadManager downloadManager=new SingleAsyncDownloadManager(_client,task);
    _tasks.put(Integer.valueOf(_nextID),downloadManager);
    _nextID++;
    return task;
  }

  /**
   * Start a download.
   * @param task Download task.
   * @param listener Optional listener for download status updates.
   * @return <code>true</code> if download started, <code>false</code> otherwise.
   */
  public boolean startDownload(DownloadTask task, DownloadListener listener)
  {
    boolean ok=false;
    int taskID=task.getID();
    SingleAsyncDownloadManager downloadManager=getDownloadManager(taskID);
    if (downloadManager!=null)
    {
      downloadManager.setListener(listener);
      ok=downloadManager.start();
    }
    else
    {
      task.setDownloadState(DownloadState.FAILED);
    }
    return ok;
  }

  /**
   * Wait for the termination of a download.
   * @param task Download task.
   */
  public void waitForTaskTermination(DownloadTask task)
  {
    int taskID=task.getID();
    SingleAsyncDownloadManager downloadManager=getDownloadManager(taskID);
    if (downloadManager!=null)
    {
      downloadManager.waitForDownloadTermination();
    }
  }

  private SingleAsyncDownloadManager getDownloadManager(int taskID)
  {
    return _tasks.get(Integer.valueOf(taskID));
  }

  /**
   * Wait for the termination of all downloads.
   */
  public void waitForTermination()
  {
    // Unimplemented!
  }

  /**
   * Cancel all downloads.
   */
  public void cancel()
  {
    for(SingleAsyncDownloadManager taskMgr : _tasks.values())
    {
      DownloadTask task=taskMgr.getDownloadTask();
      DownloadState state=task.getDownloadState();
      if (state==DownloadState.RUNNING)
      {
        taskMgr.cancel();
      }
    }
  }

  private CloseableHttpAsyncClient buildClient()
  {
    String[] protocols=new String[] {"TLSv1.2"};
    SSLContext sslcontext=SSLContexts.createDefault();
    SSLIOSessionStrategy sslSessionStrategy=new SSLIOSessionStrategy(sslcontext,protocols,null,SSLIOSessionStrategy.getDefaultHostnameVerifier());
    CloseableHttpAsyncClient httpclient=HttpAsyncClients.custom().setSSLStrategy(sslSessionStrategy).build();
    httpclient.start();
    return httpclient;
  }

  /**
   * Release all managed resources.
   */
  public void dispose()
  {
    if (_client!=null)
    {
      try
      {
        _client.close();
      }
      catch(IOException ioe)
      {
        LOGGER.warn("Caught exception when closing the HTTP client!", ioe);
      }
      _client=null;
    }
  }
}
