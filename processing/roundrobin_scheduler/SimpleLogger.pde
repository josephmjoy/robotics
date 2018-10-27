// A simple logging interface and implementation.
// Logs to stdout and stderr
// Trace and info can be disabled by
// setting the corresponding field variables.

import java.util.concurrent.atomic.AtomicLong;

interface SimpleLoggingInterface {
  void err(String prefix, String s);
  void info(String prefix, String s);
  void trace(String prefix, String s);
}

static class SimpleLogger implements SimpleLoggingInterface {

  public boolean trace = true; // set to false to disable logging trace msgs
  public boolean info = true;  // set to false to disable logging info msgs

  private final Thread mainThread;
  private final long startTime;
  private AtomicLong counter = new AtomicLong(0);


  private String msg(String prefix, String s) {
    String tab = Thread.currentThread() == mainThread ? "" : "====";
    long c = counter.getAndIncrement();
    return String.format("%02d:%05d %s%s %s", c, System.currentTimeMillis() - startTime, tab, prefix, s);
  }

  public SimpleLogger() {
    mainThread = Thread.currentThread(); 
    startTime  = System.currentTimeMillis();
  }

  @Override
  void err(String prefix, String s) {
    System.err.println(msg(prefix, s));
  }


  @Override
  void info(String prefix, String s) {
    if (info) System.out.println(msg(prefix, s));
  }


  @Override
  void trace(String prefix, String s) {
    if (trace) System.out.println(msg(prefix, s));
  }
}
