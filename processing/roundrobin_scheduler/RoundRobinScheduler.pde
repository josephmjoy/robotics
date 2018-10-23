import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

//
// Class RoundRobinScheduler maintains a list of 
// tasks and steps through each one in turn.
// Each task runs in it's own thread, but only
// one task runs at a time, and all tasks
// run only when the caller is blocked on
// the stepAll method.
// All method calls to RoundRobinScheduler
// MUST be called from the same main thread.
// This precondition is asserted.
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
    TaskImplementation t = new TaskImplementation(task, name);
    tasks.add(t);
    t.start();
  }

  // Steps once through all tasks. This is a blocking
  // call as it will wait until each task goes through 
  // one step.
  // MUST be called from the (single) main thread.
  // MUST NOT be called after task rundown has started (via
  // call to rundownAll)
  public void stepAll() throws InterruptedException {
    println("in stepAll");
    verifyMainThread();
    verifyNotRunningDown();
    for (TaskImplementation t : tasks) {
      t.step();
    }
    println("exiting stepAll");
  }


  // Cancels all tasks. If a task is still running, it will
  // raise a InterrupedException on that task's thread.
  // MUST be called from the single main thread.
  // MUST NOT be called after task rundown has started (via
  // call to rundownAll)
  public void cancelAll() {
        println("in cancelAll");

    verifyMainThread();
    verifyNotRunningDown();

    for (TaskImplementation t : tasks) {
      t.cancel();
    }
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
        println("in rundownAll");

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
    return ret;
  } 

  //
  // PRIVATE METHODS
  //
  private class TaskImplementation implements TaskContext {
    private final String name;
    private Semaphore doStep = new Semaphore(1);
    private Semaphore stepDone = new Semaphore(1);
    private final Task clientTask;
    private final Thread taskThread;


    TaskImplementation(final Task clientTask, String name) {
      this.name = name;
      this.clientTask = clientTask;
      final TaskContext context = this;
      this.taskThread = new Thread(new Runnable() {
        void run() {
          try {
            println("about to acquire doStep...");
            doStep.acquire();
            clientTask.run(context);
          }
          catch (InterruptedException e) {
            //
          }
          catch (Exception e) {
          }
          finally {
            stepDone.release();
            if (rundownLatch != null) {
              rundownLatch.countDown();
            }
          }
        }
      }
      );
      if (!stepDone.tryAcquire()) {
        // We should not get here as we have only just created the semaphore.
        throw new RuntimeException("Unexpected internal state!");
      }
    }


    public String name() {
      return name;
    }


    public void waitForNextStep() throws InterruptedException {
      stepDone.release();
      doStep.acquire();
    }

    void start() {
      taskThread.start();
    }
    
    void step() throws InterruptedException {
      doStep.release();
      stepDone.acquire();
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
}
