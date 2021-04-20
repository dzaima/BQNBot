package libMx;

import org.json.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MxSync {
  public final MxServer s;
  ConcurrentLinkedQueue<MxEvent> recv = new ConcurrentLinkedQueue<>();
  
  public MxSync(MxServer s, String since) {
    this(s, s.messagesSince(since, 0));
  }
  
  MxSync(MxServer s, JSONObject prev) {
    this.s = s;
    update(prev);
    stoppedBatchToken = prev.getString("next_batch");
  }
  
  public MxSync(MxRoom r, String since) {
    this(r.s, r.s.messagesSince(since, 0));
  }
  
  void update(JSONObject upd) {
    JSONObject rs = upd.getJSONObject("rooms").getJSONObject("join");
    for (String rid : rs.keySet()) {
      MxRoom r = s.room(rid);
      JSONObject info = rs.optJSONObject(rid);
      if (info!=null) {
        for (Object evo : info.getJSONObject("timeline").getJSONArray("events")) {
          recv.add(new MxEvent(r, (JSONObject) evo));
        }
      }
    }
  }
  
  
  private final AtomicBoolean running = new AtomicBoolean(false);
  private String stoppedBatchToken;
  public void start() {
    if (!running.compareAndSet(false, true)) throw new RuntimeException("Cannot start a started MxSync");
    Tools.thread(() -> {
      MxServer.log("Sync started");
      String batch = stoppedBatchToken;
      stoppedBatchToken = null;
      while (running.get()) {
        try {
          JSONObject c = s.messagesSince(batch, 30000);
          update(c);
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
  }
  public MxEvent poll() {
    assert running.get();
    return recv.poll();
  }
  public MxEvent next() {
    assert running.get();
    while (true) {
      MxEvent res = recv.poll();
      if (res!=null) return res;
      Tools.sleep(100);
    }
  }
}
