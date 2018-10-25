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

  // Class Stepper synchronizes a "supervisor" and a "worker". The
  // supervisor blocks while the worker performs a quanta (step) of work.
  private class Stepper {

    private volatile boolean canStep = false;
    private volatile boolean stepDone = false;
    private volatile boolean workerDone = false; // Worker has quit
    private volatile boolean last = false; // No more steps forthcoming
    private volatile boolean inLastStep = false; // Worker performing last step

    // Supervisor side: make worker progress by one step. Blocks until the worker has
    // completed the step.
    void step() {
      internalStep(false);
    }

    // Supervisor side: a "final" version of step that will be
    // the last call to step.
    void lastStep() {
      internalStep(true);
    }


    // Worker side: blocks until next step can be done.
    // If the exception is thrown, the caller MUST NOT attempt to
    // await any more steps. It does not 
    // need to (but can) call stopAwaitingSteps();
    // {return} is true if there will be more steps. {false}
    // if this is the final step (so cleanup may be performed).
    // Once false is returned, a subsequent call to awaitStep
    // WILL result in an InterruptedException being thrown.
    boolean awaitStep() throws InterruptedException {

      // Optionally notify superviser that previous step was complete
      synchronized(this) {
        if (canStep) {
          // We have been doing a step
          if (inLastStep) {
            // Oh oh. Worker has completed a final step. It 
            // SHOULD NOT call awaitStep again.
            throw new InterruptedException("Atempt to await step after a final step!");
          }
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
          while (!workerDone && !canStep) {
            assert(!stepDone);
            this.wait();
          }
          // Record whether or not this is the start of the
          // last step
          inLastStep = last;
        }
      }
      catch (InterruptedException e) {
        // We do not expect an interrupt exception,
        // but if we do get one, we quit this task
        // 
        done();
        throw (e);
      }
      return !last;
    }


    // Worker: notify supervisor that worker will no longer
    // wait to do steps. Idempotent.
    void done() {
      synchronized(this) {
        assert(canStep);
        assert(!stepDone);
        workerDone = true; // May already be true - in exception case
        this.notify();
      }
    }


    private void internalStep(boolean finalStep) {
      log0("STEPPER: ", "entering internalStep");
      if (workerDone) {
        log0("STEPPER: ", "entering internalStep EARLY because worker done.");
        return; // ********* EARLY RETURN *****************
      }

      // Notify worker that work is available
      synchronized(this) {
        assert(!canStep);
        assert(!stepDone);
        canStep = true;
        if (finalStep) {
          assert(!this.last);
          this.last = true;
        }
        this.notify();
      }

      // Wait for step to complete
      try {
        synchronized(this) {
          // This while loop may not be necessary, but some web reports talk about
          // spurious wakes.
          while (!workerDone && !stepDone) {
            assert(canStep);
            this.wait();
          }
          canStep = stepDone = false;
        }
      }
      catch (InterruptedException e) {
        // We do not expect the thread to be interrupted.
        // Neverthless, if it does, we cancel further stepping.
        // TODO: This will leave all the workers hanging, waiting for their
        // next step. But caller could potentially interrupt those threads.
        // But how does it know that this has happened?
        last = true;
        System.err.println("WorkItem.step caught execption " + e);
      }
      log0("STEPPER: ", "Exiting internalStep");
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
            println("THREAD: BEGIN Task " + TaskImplementation.this.name);
            stepper.awaitStep(); // Initial step
            log(TaskImplementation.this, "Abount to call client's run method");
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
            stepper.done();
            println("THREAD: END Task " + TaskImplementation.this.name);

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

    // Performs the last step.
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
    log0(String.format("TASK [%s]: ", ti.name), s);
  }


  private void log(String s) {
    log0("SCHEDULER: ", s);
  }

  private volatile long startTime = System.currentTimeMillis();
  private volatile int counter = 0;
  void log0(String prefix, String s) {
    println(String.format("%02d:%05d %s %s", counter, System.currentTimeMillis() - startTime, prefix, s));
    counter++;
  }
}
