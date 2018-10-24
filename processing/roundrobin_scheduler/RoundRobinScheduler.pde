import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

//
// Class RoundRobinScheduler maintains a list of 
// tasks and steps through each one in turn.
// Each task runs in it's own thread, but only
// one task runs at a time, and all tasks
// run only when the client is blocked on
// the stepAll method.
// All method calls to RoundRobinScheduler
// MUST be called from the same thread
// that called its constructor.
//
static class RoundRobinScheduler {

  private List<TaskImplementation> tasks = new ArrayList<TaskImplementation>();
  private final Thread mainThread;
  private CountDownLatch rundownLatch; // if NON null, implies rundown has begun.


  // Supplies context to a running task
  public interface TaskContext {
    public String name();
    public void waitForNextStep() throws InterruptedException;
  }


  // Interface to client-provided task
  public interface  Task {
    public  abstract void run(TaskContext context);
  }

  public RoundRobinScheduler() {
    // Remember the main thread to verify subsequent calls are
    // from this thread.
    mainThread = Thread.currentThread();
  }


  // Adds a task with the given name to the list of
  // round-robin tasks. The name is just to help with
  // debugging and logging. The task will be added to
  // the end of the list of tasks.
  // MUST be called from the (single) main 
  // thread, which is the thread that created the
  // RoundRobinScheduler.
  // MUST NOT be called after task rundown has started (via
  // call to rundownAll)
  public void addTask(Task task, String name) {
    verifyMainThread();
    verifyNotRunningDown();
    TaskImplementation ti = new TaskImplementation(task, name);
    tasks.add(ti);
    ti.start();
  }


  // Steps once through all tasks. This is a blocking
  // call as it will wait until each task goes through 
  // one step.
  // MUST be called from the (single) main thread.
  // MUST NOT be called after task rundown has started (via
  // call to rundownAll)
  public void stepAll() {
    log("entering stepAll");
    verifyMainThread();
    verifyNotRunningDown();
    for (TaskImplementation ti : tasks) {
      log("stepping task " + ti.name());
      ti.step();
    }
    log("exiting stepAll");
  }


  // Cancels all tasks. If a task is still running, it will
  // raise a InterrupedException on that task's thread.
  // MUST be called from the single main thread.
  // MUST NOT be called after task rundown has started (via
  // call to rundownAll)
  public void cancelAll() {
    log("entering cancelAll");
    verifyMainThread();
    verifyNotRunningDown();
    for (TaskImplementation ti : tasks) {
      ti.cancel();
    }
    log("exiting cancelAll");
  }


  // Waits for all tasks to complete OR until
  // {waitMs} milliseconds transpires. No more
  // tasks must be added after this method is called.
  // MUST be called from the single main thread.
  // MUST be called only once.
  // Return: true if all tasks have completed.
  //   false if an InterruptedException was thrown, typically
  // indicating the timeout has expired.
  public boolean rundownAll(int waitMs) {
    log("entering rundownAll");
    verifyMainThread();
    verifyNotRunningDown();
    boolean ret = true;
    rundownLatch = new CountDownLatch(tasks.size());
    try {    
      ret = rundownLatch.await(waitMs, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      // If this thread was interrupted for other reasons besides 
      // timeout we also return false.
      ret = false;
    }
    log("exiting rundownAll");
    return ret;
  } 

  //
  // PRIVATE METHODS
  //

  // Class WorkTicket synchronizes a "supervisor" and a "worker". The
  // supervisor assigns blocking work, and the worker blocks until work
  // is available.
  private class WorkTicket {

    // Supervisor side: assigns work and blocks until the worker has
    // completed the work.
    void doWork() {
    }

    // Worker side: blocks until work is available.
    void waitForWork() throws InterruptedException {
    }

    // Worker side: notify supervisor that work is complete.
    // This unblocks the supervisor waiting in doWork.
    void workComplete() {
    }
  }

  private class TaskImplementation implements TaskContext {
    private final String name;
    private final Task clientTask;
    private final Thread taskThread;
    private final WorkTicket ticket = new WorkTicket(); // used to synchronize work


    TaskImplementation(final Task clientTask, String name) {
      this.name = name;
      this.clientTask = clientTask;
      final TaskContext context = this;
      this.taskThread = new Thread(new Runnable() {
        void run() {
          try {
            log(TaskImplementation.this, "waiting for work...");
            ticket.waitForWork();
            clientTask.run(context); // it will call back zero or more times to wait for more work.
          }
          catch (InterruptedException e) {
            //
          }
          catch (Exception e) {
          }
          finally {
            ticket.workComplete();
            if (rundownLatch != null) {
              rundownLatch.countDown();
            }
          }
        }
      }
      );
    }


    public String name() {
      return name;
    }


    public void waitForNextStep() throws InterruptedException {
      log(this, "entering waitForNextStep");
      ticket.workComplete();
      ticket.waitForWork();
      log(this, "exiting waitForNextStep");
    }

    void start() {
      taskThread.start();
    }

    void step() {
      ticket.doWork();
    }

    void cancel() {
    }
  }

  private void verifyMainThread() {
    if (Thread.currentThread() != mainThread) {
      throw new IllegalStateException("Attempt to make call from non-main thread.");
    }
  }

  private void verifyNotRunningDown() {
    if (rundownLatch != null) {
      // This method should not be called during rundown.
      throw new IllegalStateException("Cannot perform operation during run down");
    }
  }

  private void log(TaskImplementation ti, String s) {
    log0(String.format("TASK [%s]: ", ti.name), s);
  }
  
  
  private void log(String s) {
    log0("SCHEDULER: ", s);
  }
  
  private long startTime = System.currentTimeMillis();
  private void log0(String prefix, String s) {
       println(String.format("%05d %s %s", System.currentTimeMillis() - startTime, prefix, s));
  }
  
}
