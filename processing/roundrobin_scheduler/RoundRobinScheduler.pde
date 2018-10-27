// A round robin scheduler that may be used to implement
// concurrent blocking tasks with serialized execution.
// Author: Joseph M. Joy FTC 12598 and FRC 1899 mentor
//
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

//
// Class RoundRobinScheduler maintains a list of 
// tasks and steps through each one in turn.
// Each task runs in it's own system thread, but only
// one task runs at a time, and all tasks
// run only when the client is blocked on
// the stepAll method.
// All method calls to RoundRobinScheduler
// MUST be called from the same thread
// that called its constructor.
static class RoundRobinScheduler {

  private List<TaskImplementation> tasks = new ArrayList<TaskImplementation>();
  private final Thread mainThread; // The tread that called the constructor
  private CountDownLatch rundownLatch; // If NON null, implies rundown has begun.
  private int doneTaskCount = 0; // Tasks that were completed before rundown began.


  // Supplies context to a client-provided  task
  public interface TaskContext {
    public String name();

    // Worker side: blocks until next step can be done.
    // Return:  true if there will be more steps, false
    //     if this is the final step (so cleanup may be performed).
    // If an exception is thrown, the caller MUST NOT attempt to
    // wait for any more steps.
    // Once false is returned, a subsequent call to this method
    // WILL result in an InterruptedException being thrown.
    public boolean waitForNextStep() throws InterruptedException;
  }


  // Interface to client-provided task - similar to java.lang.Runnable,
  // but with the added context.
  public interface  Task {
    public  abstract void run(TaskContext context);
  }


  public RoundRobinScheduler() {
    // Remember the main thread, just to verify subsequent calls are
    // from this thread.
    mainThread = Thread.currentThread();
  }


  // Adds a task with the given name to the list of
  // round-robin tasks. The name is to help with
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


  // Cancels all tasks.
  // MUST be called from the single main thread.
  // MUST NOT be called after task rundown has started (via
  // call to rundownAll)
  public void cancelAll() {
    log("entering cancelAll");
    verifyMainThread();
    verifyNotRunningDown();
    for (TaskImplementation ti : tasks) {
      ti.lastStep();
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
    rundownLatch = new CountDownLatch(tasks.size() - doneTaskCount);
    try {    
      ret = rundownLatch.await(waitMs, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      // If this thread was interrupted for other reasons besides 
      // timeout (can't think of any) we also return false.
      ret = false;
    }
    log("exiting rundownAll");
    return ret;
  } 

  //
  // PRIVATE CLASSES AND METHODS
  //


  private class TaskImplementation implements TaskContext {
    private final String name;
    private final Thread taskThread;
    private final StepCoordinator stepper = new StepCoordinator(); // used to synchronize work
    private boolean finalStepComplete; // true when the scheduler has already stepped for the last time.

    TaskImplementation(final Task clientTask, String name) {
      this.name = name;
      final TaskContext context = this;
      this.taskThread = new Thread(new Runnable() {
        void run() {
          try {
            System.out.println("THREAD: BEGIN Task " + TaskImplementation.this.name);
            if (stepper.awaitStep()) { // Initial step
              log(TaskImplementation.this, "About to call client's run method");
              clientTask.run(context); // it will call back zero or more times to wait for more work.
              log(TaskImplementation.this, "Client's run method returns.");
            }
          }
          catch (Exception e) {
            // This is a catch-all for any exception thrown by the stepper or the client code.
            // There is not much we can do to recover, so we re-throw the exception, but
            // also notify the client below.
            System.err.println("Caught exception " + e);
            System.err.flush();
          }
          finally {
            if (rundownLatch != null) {
              rundownLatch.countDown();
            } else {
              log("NULL rundown Latch!");
              doneTaskCount++;
            }
            // The following must be AFTER counting down
            // the latch, else we have a race condition between the main thread
            // initializing rundownLatch and this thread counting it down.  
            println("THREAD: END Task " + TaskImplementation.this.name + ". Calling done()");
            stepper.stopAwaitingSteps();
          }
        }
      }
      );
    }


    @Override() 
    public String name() {
      return name;
    }


    @Override()
    public boolean waitForNextStep() throws InterruptedException {
      log(this, "entering waitForNextStep");
      if (finalStepComplete) {
        throw new InterruptedException("Final step has already completed!");
      }
      finalStepComplete = stepper.awaitStep();
      log(this, "exiting waitForNextStep");
      return finalStepComplete;
    }

    // Called by scheduler main thread
    // to start the dedicated thread assigned
    // to this task.
    void start() {
      taskThread.start();
    }

    // Called by scheduler main thread
    void step() {
      stepper.step();
    }

    // Performs the last step.
    // Called by scheduler main thread.
    void lastStep() {
      log(this, "entering lastStep");
      stepper.lastStep();
      log(this, "exiting lastStep");
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
    g_logger.info(String.format("TASK [%s]: ", ti.name), s);
  }


  private void log(String s) {
    g_logger.info("SCHEDULER: ", s);
  }
}
