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
  private int doneTaskCount = 0; // Tasks that were done before rundown.

  // Supplies context to a client-provided  task
  public interface TaskContext {
    public String name();
    public void waitForNextStep() throws InterruptedException;
  }

  // Interface to client-provided task
  public interface  Task {
    public  abstract void run(TaskContext context);
  }

  public RoundRobinScheduler() {
    // Remember the main thread just to verify subsequent calls are
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
      ti.notifyStop();
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

  // Class Stepper synchronizes a "supervisor" and a "worker". The
  // supervisor blocks while the worker performs a quanta (step) of work.
  private class Stepper {

    private boolean canStep = false;
    private boolean stepDone = false;
    private boolean quit = false; // once quit, stay quit.

    // Supervisor side: make worker progress by one step. Blocks until the worker has
    // completed the step.
    void step() {

      if (quit) {
        return; // ********* EARLY RETURN *****************
      }

      // Notify worker that work is available
      synchronized(this) {
        assert(!canStep);
        assert(!stepDone);
        canStep = true;
        this.notify();
      }

      // Wait for step to complete
      try {
        synchronized(this) {
          // This while loop may not be necessary, but some web reports talk about
          // spurious wakes.
          while (!quit && !stepDone) {
            assert(canStep);
            this.wait();
          }
          canStep = stepDone = false;
        }
      }
      catch (InterruptedException e) {
        // We do not expect the thread to be interrupted.
        // Neverthless, if it does, we cancel further stepping.
        quit = true;
        System.err.println("WorkItem.step caught execption " + e);
      }
    }


    // Worker side: blocks until nest step can be done.
    // If the exception is thrown, the caller MUST NOT attempt to
    // await any more steps. It does not 
    // need to (but can) call stopAwaitingSteps();
    void awaitStep() throws InterruptedException {

      // Optionally notify superviser that previous step was complete
      synchronized(this) {
        if (canStep) {
          assert(!stepDone);
          stepDone = true;
          this.notify();
        }
      }

      try {
        // Wait for next step
        synchronized(this) {
          // This while loop may not be necessary, but some web reports talk about
          // spurious wakes.
          while (!quit && !canStep) {
            assert(!stepDone);
            this.wait();
          }
        }
      }
      catch (InterruptedException e) {
        // We do not expect an interrupt exception,
        // but if we do get one, we quit this task
        // 
        stopAwaitingSteps();
        throw (e);
      }
    }


    // Worker: notify supervisor that worker will no longer
    // be waiting to do steps.
    void stopAwaitingSteps() {
      synchronized(this) {
        assert(canStep);
        assert(!stepDone);
        quit = true; // quit may already be true - in exception case
        this.notify();
      }
    }
  }


  private class TaskImplementation implements TaskContext {
    private final String name;
    private final Thread taskThread;
    private final Stepper stepper = new Stepper(); // used to synchronize work


    TaskImplementation(final Task clientTask, String name) {
      this.name = name;
      final TaskContext context = this;
      this.taskThread = new Thread(new Runnable() {
        void run() {
          try {
            log(TaskImplementation.this, "waiting for work...");
            stepper.awaitStep(); // Initial step
            clientTask.run(context); // it will call back zero or more times to wait for more work.
          }
          catch (Exception e) {
            // This is a catch-all for any exception thrown by the stepper or the client code.
            // There is not much we can do to recover, so we re-throw the exception, but
            // also notify the client below.
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
            stepper.stopAwaitingSteps();
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
      stepper.awaitStep();
       log(this, "exiting waitForNextStep");
    }

    void start() {
      taskThread.start();
    }

    void step() {
      stepper.step();
    }

    // Stop simply notifies task that it needs to quit. However
    // it waits for the tasks to quit themselves.
    void notifyStop() {
      log(this, "in notifyStop");
      assert false; // UNIMPLEMENTED
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
