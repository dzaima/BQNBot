package libMx;

import org.json.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MxSync2 {
  public final MxServer s;
  ConcurrentLinkedQueue<JSONObject> recv = new ConcurrentLinkedQueue<>();
  
  public MxSync2(MxServer s, String since) {
    this(s, s.messagesSince(since, 0));
  }
  
  MxSync2(MxServer s, JSONObject prev) {
    this.s = s;
    recv.add(prev);
    stoppedBatchToken = prev.getString("next_batch");
  }
  
  @Deprecated
  public MxSync2(MxRoom r, String since) {
    this(r.s, r.s.messagesSince(since, 0));
  }
  
  
  private final AtomicBoolean running = new AtomicBoolean(false);
  private String stoppedBatchToken;
  private Thread thr;
  public void start() {
    if (!running.compareAndSet(false, true)) throw new RuntimeException("Cannot start a started MxSync");
    thr = Tools.thread(() -> {
      MxServer.log("Sync started");
      String batch = stoppedBatchToken;
      stoppedBatchToken = null;
      while (running.get()) {
        try {
          JSONObject c = s.messagesSince(batch, 30000);
          recv.add(c);
          batch = c.getString("next_batch");
        } catch (Throwable t) {
          MxServer.log("Failed to update:");
          t.printStackTrace();
        }
        Tools.sleep(100);
      }
      stoppedBatchToken = batch;
    });
  }
  public void stop() {
    if (!running.compareAndSet(true, false)) throw new RuntimeException("Cannot stop a stopped MxSync");
    thr.interrupt();
  }
  public JSONObject poll() {
    assert running.get();
    return recv.poll();
  }
  public JSONObject next() {
    assert running.get();
    while (true) {
      JSONObject res = recv.poll();
      if (res!=null) return res;
      Tools.sleep(100);
    }
  }
}
