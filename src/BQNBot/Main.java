package BQNBot;

import BQN.Sys;
import BQN.errors.BQNError;
import BQN.tools.FmtInfo;
import BQN.types.Value;
import libMx.*;
import org.jsoup.Jsoup;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.atomic.*;

@SuppressWarnings("deprecation") // thread.stop is the only way to do this
public class Main {
  public static String pathLogin = "mxLogin";
  public static int TIMEOUT = 1000; // ms per message
  public static int MAXLEN = 5000;  // chars
  public static int MAXW = 100; // max char count in line
  public static int MAXSW = 300; // max char count in single-line mode
  public static int MAXH = 25; // max line count
  public static MxServer s;
  
  public static void truncate(AtomicBoolean multiline, AtomicReference<String> resCode, StringBuilder rb) {
    if (rb.length()>0 && rb.charAt(rb.length()-1)=='\n') rb.setLength(rb.length()-1);
    if (rb.length()>MAXLEN+20) { rb.setLength(MAXLEN); rb.append("…"); }
    StringBuilder b = new StringBuilder();
    int lnc = 0;
    int lnw = 0;
    for (int i = 0; i < rb.length(); i++) {
      lnw++;
      if (isNL(rb.charAt(i))) { lnc++; lnw = 0; }
      if (lnw<=MAXW) b.append(rb.charAt(i));
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
  
    s = MxServer.of(Paths.get(pathLogin));
    MxLogin me = s.primaryLogin;
    
    MxSync mxSync = new MxSync(s, s.latestBatch());
    mxSync.start();
    
    
    MxServer.LOG = false; // boring & pointless logs
    HashMap<String, String> msgs = new HashMap<>();
    while (true) {
      MxEvent mxE = mxSync.next();
      if (mxE.m==null) {
        if (mxE.type.equals("m.room.redaction")) {
          String mxId = mxE.o.getString("redacts");
          if (msgs.containsKey(mxId)) me.deleteMessage(mxE.r, msgs.get(mxId));
        }
      } else {
        MxMessage msg = mxE.m;
        String body = Jsoup.parse(msg.fmt.html).wholeText();
        if (body.length()<5) continue;
        boolean pretty = body.substring(0, 4).equalsIgnoreCase("BQN)") || body.substring(0, 4).equalsIgnoreCase(")BQN");
        if (pretty || body.substring(0, 5).equalsIgnoreCase("BQNr)") || body.substring(0, 5).equalsIgnoreCase(")BQNr")) {
          String src = body.substring(pretty? 4 : 5);
          System.out.println("Executing: ```");
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
                if (prr!=null) rb.append(pretty? FmtInfo.fmt(prr.pretty(sys.fi)) : prr.ln(sys.fi));
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
          if (ri.length() > MAXLEN) ri = ri.substring(0, MAXLEN);
          MxFmt f = new MxFmt();
          // f.reply(properID);
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
            me.editMessage(mxE.r, msgs.get(msg.editsId), f);
          } else {
            String properID = msg.editsId!=null? msg.editsId : msg.id;
            String nid = me.sendMessage(mxE.r, f);
            msgs.put(properID, nid);
          }
          System.gc();
        }
      }
    }
  }
  
  public static boolean isNL(char c) { return c=='\n' | c=='\r'; }
  
  static HashMap<String, MxUser> users = new HashMap<>();
  static MxUser getUser(String id) {
    MxUser u = users.get(id);
    if (u==null) users.put(id, u = s.user(id));
    return u;
  }
  
  static class SSys extends Sys {
    StringBuilder rb = new StringBuilder();
    public void println(String s) { if (rb.length()<MAXLEN) rb.append(s).append('\n'); }
    public void off(int i) { throw new RuntimeException("Cannot exit in safe mode"); }
    public String input() { throw new RuntimeException("Cannot take input in safe mode"); }
    public boolean hasInput() { return false; }
  }
}
