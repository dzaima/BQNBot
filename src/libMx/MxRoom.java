package libMx;

import java.util.*;

import static dzaima.utils.JSON.Obj;

public class MxRoom {
  public final MxServer s;
  public final String rid;
  
  public MxRoom(MxServer s, String rid) {
    this.s = s;
    this.rid = rid;
  }
  
  
  public MxMessage message(String id) {
    return new MxMessage(this, s.getJ("_matrix/client/r0/rooms/"+rid+"/event/"+id+"?access_token="+s.gToken));
  }
  
  public ArrayList<MxEvent> beforeMsg(String id, int am) {
    Obj o = s.getJ("_matrix/client/r0/rooms/"+rid+"/context/"+id+"?limit="+am+"&access_token="+s.gToken);
    ArrayList<MxEvent> res = new ArrayList<>();
    if (!o.has("events_before")) return null;
    for (Obj c : o.arr("events_before").objs()) {
      res.add(new MxEvent(this, c));
    }
    Collections.reverse(res);
    return res;
  }
  public static class Chunk {
    public final ArrayList<MxEvent> events;
    public final String sTok;
    public final String eTok;
  
    public Chunk(ArrayList<MxEvent> events, String sTok, String eTok) { this.events = events; this.sTok = sTok; this.eTok = eTok; }
  }
  public Chunk beforeTok(String from, int am) {
    return beforeTok(from, null, am);
  }
  public Chunk beforeTok(String from, String to, int am) {
    Obj o = s.getJ("_matrix/client/r0/rooms/"+rid+"/messages?limit="+am+"&from="+from+(to==null?"":"&to="+to)+"&dir=b&access_token="+s.gToken);
    ArrayList<MxEvent> res = new ArrayList<>();
    if (!o.has("chunk")) return null;
    for (Obj c : o.arr("chunk").objs()) {
      res.add(new MxEvent(this, c));
    }
    Collections.reverse(res);
    return new Chunk(res, o.str("start"), o.str("end"));
  }
  
  @Deprecated
  public Obj messagesSince(String since, int timeout) {
    return s.messagesSince(since, timeout);
  }
  
  public void readTo(String id) {
    s.postJ("_matrix/client/r0/rooms/"+rid+"/receipt/m.read/"+id+"?access_token="+s.gToken, "{}");
  }
}
