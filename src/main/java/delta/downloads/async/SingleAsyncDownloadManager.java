package delta.downloads.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.methods.AsyncByteConsumer;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import delta.common.utils.NumericTools;

/**
 * Manager for a single download.
 * @author DAM
 */
public class SingleAsyncDownloadManager
{
  private static final Logger LOGGER=LoggerFactory.getLogger(SingleAsyncDownloadManager.class);

  private CloseableHttpAsyncClient _client;
  private DownloadTask _task;
  private DownloadListener _listener;
  
  /**
   * Constructor.
   * @param client Underlying HTTP client.
   * @param task Download task.
   */
  public SingleAsyncDownloadManager(CloseableHttpAsyncClient client, DownloadTask task)
  {
    _client=client;
    _task=task;
  }

  /**
   * Set the listener.
   * @param listener Listener to use.
   */
  public void setListener(DownloadListener listener)
  {
    _listener=listener;
  }

  /**
   * Get the managed download task.
   * @return the managed download task.
   */
  public DownloadTask getDownloadTask()
  {
    return _task;
  }

  /**
   * Start download.
   * @return <code>true</code> if start was successfull, <code>false</code> otherwise.
   */
  public boolean start()
  {
    BytesReceiver receiver=_task.getReceiver();
    boolean ok=receiver.start();
    if (!ok)
    {
      _task.setDownloadState(DownloadState.FAILED);
      return false;
    }
    String url=_task.getURL();
    final HttpGet get=new HttpGet(url);
    HttpAsyncRequestProducer producer=HttpAsyncMethods.create(get);
    AsyncByteConsumer<HttpResponse> consumer=buildConsumer();
    FutureCallback<HttpResponse> callback=buildCallback();
    _task.setDownloadState(DownloadState.RUNNING);
    // Handle exceptions raised below?
    Future<HttpResponse> future=_client.execute(producer,consumer,callback);
    _task.setFuture(future);
    return true;
  }

  private AsyncByteConsumer<HttpResponse> buildConsumer()
  {
    AsyncByteConsumer<HttpResponse> consumer=new AsyncByteConsumer<HttpResponse>()
    {
      private HttpResponse _responseStorage;

      @Override
      protected void onByteReceived(ByteBuffer buf, IOControl ioctrl) throws IOException
      {
        handleBytesReceived(buf);
      }

      @Override
      protected HttpResponse buildResult(HttpContext context) throws Exception
      {
        return this._responseStorage;
      }

      @Override
      protected void onResponseReceived(HttpResponse response) throws HttpException, IOException
      {
        LOGGER.debug("Received response: {}",response);
        this._responseStorage=response;
        Header[] headers=response.getHeaders("Content-Length");
        if ((headers!=null)&&(headers.length>0))
        {
          String valueStr=headers[0].getValue();
          Integer expectedLength=NumericTools.parseInteger(valueStr);
          LOGGER.debug("Expected length: {}",expectedLength);
          _task.setExpectedSize(expectedLength);
          invokeListener();
        }
      }
    };
    return consumer;
  }

  private FutureCallback<HttpResponse> buildCallback()
  {
    FutureCallback<HttpResponse> futureCb=new FutureCallback<HttpResponse>()
    {
      public void completed(final HttpResponse response)
      {
        handleCompletion(response.getStatusLine());
      }
      public void failed(Exception ex)
      {
        handleFailure(ex);
      }
      public void cancelled()
      {
        handleCancellation();
      }
    };
    return futureCb;
  }

  private void handleBytesReceived(ByteBuffer buf)
  {
    if (LOGGER.isDebugEnabled())
    {
      LOGGER.debug("Received: {}", Integer.valueOf(buf.remaining()));
    }
    int bytesCount=buf.remaining();
    byte[] bytes=buf.array();
    BytesReceiver receiver=_task.getReceiver();
    boolean ok=receiver.handleBytes(bytes,0,bytesCount);
    if (ok)
    {
      int doneSize=_task.getDoneSize();
      doneSize+=bytesCount;
      _task.setDoneSize(doneSize);
      if (LOGGER.isDebugEnabled())
      {
        LOGGER.debug("Done size: {} / {}",Integer.valueOf(doneSize),_task.getExpectedSize());
      }
      invokeListener();
    }
    else
    {
      cancel();
      handleFailure(null);
    }
  }

  private void handleCompletion(StatusLine statusLine)
  {
    if (LOGGER.isDebugEnabled())
    {
      LOGGER.debug("COMPLETED {} => {}",_task.getURL(),statusLine);
    }
    int statusCode=statusLine.getStatusCode();
    if (statusCode==HttpStatus.SC_OK)
    {
      _task.setDownloadState(DownloadState.OK);
    }
    else
    {
      _task.setDownloadState(DownloadState.FAILED);
    }
    handleTermination();
  }

  private void handleFailure(Exception e)
  {
    LOGGER.warn("Failure received for: {} with exception {}",_task,e);
    _task.setDownloadState(DownloadState.FAILED);
    handleTermination();
  }

  private void handleCancellation()
  {
    LOGGER.warn("Cancellation received for: {}",_task);
    _task.setDownloadState(DownloadState.CANCELLED);
    handleTermination();
  }

  private void handleTermination()
  {
    BytesReceiver receiver=_task.getReceiver();
    receiver.terminate();
    invokeListener();
  }

  /**
   * Cancel download.
   */
  public void cancel()
  {
    Future<HttpResponse> future=_task.getFuture();
    if (future!=null)
    {
      future.cancel(true);
    }
  }

  /**
   * Wait for download termination.
   */
  public void waitForDownloadTermination()
  {
    try
    {
      HttpResponse httpResponse=_task.getFuture().get();
      Integer statusCode=getStatusCode(httpResponse);
      DownloadState state=getDownloadState(statusCode);
      _task.setDownloadState(state);
    }
    catch (Exception e)
    {
      LOGGER.error("Caught exception in future.get()!",e);
    }
  }

  private Integer getStatusCode(HttpResponse httpResponse)
  {
    StatusLine line=httpResponse.getStatusLine();
    if (line!=null)
    {
      return Integer.valueOf(line.getStatusCode());
    }
    return null;
  }

  private DownloadState getDownloadState(Integer statusCode)
  {
    if ((statusCode!=null) && (statusCode.intValue()==HttpStatus.SC_OK))
    {
      return DownloadState.OK;
    }
    return DownloadState.FAILED;
  }

  private void invokeListener()
  {
    if (_listener!=null)
    {
      _listener.downloadTaskUpdated(_task);
    }
  }
}
