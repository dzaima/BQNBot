package libMx;

import dzaima.utils.JSON.Obj;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MxSync2 {
  public final MxServer s;
  ConcurrentLinkedQueue<Obj> recv = new ConcurrentLinkedQueue<>();
  
  public MxSync2(MxServer s, String since) {
    this(s, s.messagesSince(since, 0));
  }
  
  MxSync2(MxServer s, Obj prev) {
    this.s = s;
    recv.add(prev);
    stoppedBatchToken = prev.str("next_batch");
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
    thr = Utils.thread(() -> {
      MxServer.log("Sync started");
      String batch = stoppedBatchToken;
      stoppedBatchToken = null;
      int failTime = 16;
      while (running.get()) {
        try {
          Obj c = s.messagesSince(batch, MxServer.SYNC_TIMEOUT);
          recv.add(c);
          batch = c.str("next_batch");
          failTime = 16;
        } catch (Throwable t) {
          failTime = Math.min(2*failTime, 180);
          MxServer.warn("Failed to update:");
          t.printStackTrace();
          MxServer.warn("Retrying in "+failTime+"s");
          Utils.sleep(failTime*1000);
        }
        Utils.sleep(100);
      }
      stoppedBatchToken = batch;
    });
  }
  public void stop() {
    if (!running.compareAndSet(true, false)) throw new RuntimeException("Cannot stop a stopped MxSync");
    thr.interrupt();
  }
  public Obj poll() {
    assert running.get();
    return recv.poll();
  }
  public Obj next() {
    assert running.get();
    while (true) {
      Obj res = recv.poll();
      if (res!=null) return res;
      Utils.sleep(100);
    }
  }
}
