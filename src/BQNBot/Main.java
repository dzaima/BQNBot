package BQNBot;

import BQN.Sys;
import BQN.errors.BQNError;
import BQN.tools.FmtInfo;
import BQN.types.Value;
import libMx.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

@SuppressWarnings("deprecation") // thread.stop is the only way to do this
public class Main {
  public static Path defLoginPath = Paths.get("mxLogin");
  public static int TIMEOUT = 5000; // ms per message
  public static int MAXLEN = 5000;  // chars
  public static int MAXW = 100; // max char count in line
  public static int MAXSW = 300; // max char count in single-line mode
  public static int MAXH = 25; // max line count
  
  MxServer s;
  MxLogin me;
  
  HashMap<String, String> msgs = new HashMap<>();
  MxEvent currentEvent;
  
  public static void truncate(AtomicBoolean multiline, AtomicReference<String> resCode, StringBuilder rb) {
    if (rb.length()>0 && rb.charAt(rb.length()-1)=='\n') rb.setLength(rb.length()-1);
    if (rb.length()>MAXLEN+20) { rb.setLength(MAXLEN); rb.append("…"); }
    StringBuilder b = new StringBuilder();
    int lnc = 0;
    int lnw = 0;
    for (int i = 0; i < rb.length(); i++) {
      lnw++;
      if (isNL(rb.charAt(i))) { lnc++; lnw = 0; }
      if (lnw<MAXW) b.append(rb.charAt(i));
      if (lnw==MAXW) b.append('…');
      if (lnc>MAXH) { b.append('…'); break; }
    }
    multiline.set(lnc>0);
    if (lnc == 0) {
      if (rb.length()>MAXSW) { rb.setLength(MAXSW); rb.append('…'); }
      resCode.set(rb.toString());
    } else {
      resCode.set(b.toString());
    }
  }
  
  public static void main(String[] args) {
    if (!BQN.Main.SAFE) throw new RuntimeException("dzaima/BQN wasn't built with safe mode!");
    new Main().run(args.length==0? defLoginPath : Paths.get(args[0]));
  }
  
  void run(Path loginPath) {
    s = MxServer.of(new MxLoginMgr.MxFileLogin(loginPath));
    me = s.primaryLogin;
    
    MxSync mxSync = new MxSync(s, s.latestBatch());
    mxSync.start();
    
    BQN.Main.exec("2+2", new SSys().gsc, null); // help with cold start
    //noinspection InfiniteLoopStatement
    while (true) {
      MxEvent mxE = mxSync.next();
      if (mxE.m==null) {
        if (mxE.type.equals("m.room.redaction")) {
          String mxId = mxE.o.getString("redacts");
          if (msgs.containsKey(mxId)) me.deleteMessage(mxE.r, msgs.get(mxId));
        }
      } else if (!mxE.uid.equals(me.uid) && !mxE.m.type.equals("m.notice")) {
        MxMessage msg = mxE.m;
        currentEvent = mxE;
        MsgReader r = new MsgReader();
        readMsg(r, Jsoup.parse(msg.fmt.html));
        r.end();
        currentEvent = null;
      }
    }
  }
  void execBQN(String src, int mode) {
    MxServer.log("BONBot", "Executing: ```");
    System.out.println(src);
    System.out.println("```");
    AtomicBoolean multiline = new AtomicBoolean(false);
    AtomicBoolean done = new AtomicBoolean(false);
    AtomicReference<String> resCode = new AtomicReference<>("");
    AtomicReference<String> resInfo = new AtomicReference<>("");
    Thread t = Tools.thread(() -> {
      try {
        SSys sys = new SSys();
        try {
          BQN.Main.vind = false;
          BQN.Main.debug = false;
          BQN.Main.quotestrings = true;
          StringBuilder rb = sys.rb;
          Value prr = BQN.Main.exec(src, sys.gsc, null);
          if (prr!=null && mode!=2) rb.append(mode==0? FmtInfo.fmt(prr.pretty(sys.fi)) : prr.ln(sys.fi));
          truncate(multiline, resCode, rb);
          done.set(true);
        } catch (BQNError e) {
          String em = e.getMessage();
          if (em!=null) resInfo.set(e.getClass().getSimpleName()+": "+em);
          else resInfo.set("Expression errored: "+e.getClass().getSimpleName());
          truncate(multiline, resCode, sys.rb);
          done.set(true);
        } catch (Exception e) {
          resInfo.set("Expression errored: "+e.getMessage());
          truncate(multiline, resCode, sys.rb);
          done.set(true);
        }
      } catch (OutOfMemoryError e) {
        resInfo.set("Took too much memory");
        done.set(true);
      }
    });
    long sns = System.currentTimeMillis();
    String rc, ri;
    while (true) {
      long time = System.currentTimeMillis()-sns;
      if (done.get()) {
        rc = resCode.get();
        ri = resInfo.get();
        break;
      }
      if (time > TIMEOUT) {
        t.stop();
        rc = "";
        ri = "Expression took too long";
        break;
      }
      Tools.sleep(20);
    }
    MxServer.log("BQNBot", "evaluated");
    if (ri.length() > MAXLEN) ri = ri.substring(0, MAXLEN);
    MxFmt f = new MxFmt();
    // f.reply(properID);
    
    MxMessage msg = currentEvent.m;
    String nick = getUser(msg.uid).name();
    f.body.append(nick).append(':');
    f.html.append("<a href=\"https://matrix.to/#/").append(Tools.toHTML(msg.uid)).append("\">").append(Tools.toHTML(nick)).append("</a>");
    if (rc.length()>0) {
      if (multiline.get()) {
        f.mc(rc, "bqn");
      } else {
        f.txt(" ");
        f.c(rc);
        if (ri.length()>0) f.txt("\n");
      }
    } else if (ri.length()>0) f.txt(" ");
    if (ri.length()>0) f.txt(ri);
    if (rc.isEmpty() && ri.isEmpty()) f.txt("(no output)");
    
    if (msg.editsId!=null && msgs.containsKey(msg.editsId)) {
      me.editMessage(currentEvent.r, msgs.get(msg.editsId), f);
    } else {
      String properID = msg.editsId!=null? msg.editsId : msg.id;
      String nid = me.sendMessage(currentEvent.r, f);
      msgs.put(properID, nid);
    }
    System.gc();
  }
  
  public static boolean isNL(char c) { return c=='\n' | c=='\r'; }
  
  HashMap<String, MxUser> users = new HashMap<>();
  MxUser getUser(String id) {
    MxUser u = users.get(id);
    if (u==null) users.put(id, u = s.user(id));
    return u;
  }
  
  
  
  class MsgReader {
    StringBuilder b = new StringBuilder();
    boolean cmd = false;
    int mode; // 0 - pretty; 1 - repr; 2 - quiet
    boolean done = false;
    boolean chk(String name, int mode) {
      int cl = name.length()+1;
      if (b.length()>= cl) {
        if (b.substring(0,cl).equalsIgnoreCase(name+")") || b.substring(0,cl).equalsIgnoreCase(")"+name)) {
          b.delete(0,cl);
          cmd = true;
          this.mode = mode;
          return true;
        }
      }
      return false;
    }
    boolean checkCmd() {
      return cmd || chk("bqn", 0) || chk("bqnr", 1) || chk("bqnq", 2);
    }
    void nl() {
      if (checkCmd()) b.append("\n");
      else b.delete(0, b.length());
    }
    void end() {
      if (checkCmd() && !done) {
        execBQN(b.toString(), mode);
        done = true;
      }
    }
  }
  
  void readMsg(MsgReader r, Node n) {
    if (n instanceof TextNode) {
      String[] lns = (((TextNode) n).getWholeText()+"\n").split("\n");
      for (int i = 0; i < lns.length; i++) {
        if (i!=0) r.nl();
        r.b.append(lns[i]);
      }
    } else {
      String name = n instanceof Element? ((Element) n).tagName() : "";
      if (!r.checkCmd() && (name.equals("p") || name.equals("pre"))) r.end();
      for (Node c : n.childNodes()) readMsg(r, c);
      if (name.equals("br") || name.equals("p") || name.equals("div")) r.nl();
      if (name.equals("pre") || name.equals("code")) r.end();
    }
  }
  
  
  
  static class SSys extends Sys {
    StringBuilder rb = new StringBuilder();
    public void println(String s) { if (rb.length()<MAXLEN) rb.append(s).append('\n'); }
    public void off(int i) { throw new RuntimeException("Cannot exit in safe mode"); }
    public String input() { throw new RuntimeException("Cannot take input in safe mode"); }
    public boolean hasInput() { return false; }
  }
}
