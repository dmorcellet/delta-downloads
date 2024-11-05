package delta.downloads.async;

import java.io.File;
import java.util.Date;

import org.junit.jupiter.api.Test;

/**
 * Test class for the DownloadsManager.
 * @author DAM
 */
public class DownloadsManagerTest
{
  /**
   * Test a download.
   */
  @Test
  void testDownload()
  {
    DownloadsManager downloadsMgr=new DownloadsManager();
    String url="https://sourceforge.net/projects/lotrocompanion/files/1.zip/download";
    File to=new File("d:/tmp/toto1.zip");
    DownloadTask task=downloadsMgr.newFileDownload(url,to);
    DownloadListener listener=new DownloadListener()
    {
      @Override
      public void downloadTaskUpdated(DownloadTask updatedTask)
      {
        System.out.println(Thread.currentThread().getName()+" - "+new Date().toString()+" = > "+updatedTask);
      }
    };
    boolean ok=downloadsMgr.startDownload(task,listener);
    if (ok)
    {
      downloadsMgr.waitForTaskTermination(task);
    }
    System.out.println(Thread.currentThread().getName()+" - Finished!");
  }
}
