import java.util.List;
//
// Class RoundRobinScheduler maintains a list of 
// tasks and steps through each one in turn.
// Each task runs in it's own thread, but only
// one task runs at a time, and all tasks
// run only when the caller is blocked on
// the stepAll method.
//
static class RoundRobinScheduler {


  // Supplies context to a running task
  public interface TaskContext {
    public String name();
    public void waitForNextStep() throws InterruptedException;
  }


  // Interface to client-provided task
  public interface  Task {
    public  abstract void run(TaskContext context);
  }

  // Adds a task with the given name to the list of
  // round-robin tasks. The name is just to help with
  // debugging and logging. The task will be added to
  // the end of the list of tasks.
  public void addTask(Task task, String name) {
  }

  // Steps once through all tasks. This is a blocking
  // call as it will wait until each task goes through 
  // one step.
  public void stepAll() throws InterruptedException {
  }


  // Cancels all tasks. If a task is still running, it will
  // raise a InterrupedException on that task's thread.
  public void cancelAll() {
  }


  // Waits for all tasks to complete OR until
  // {waitMs} milliseconds transpires.
  public boolean waitForAllDone(int waitMs) {
    return true;
  } 

  //
  // PRIVATE FIELDS
  //
  private class TaskImplementation implements TaskContext {
    private String name;
    public String name() {
      return name;
    }
    public void waitForNextStep() throws InterruptedException {
    }
  }
  
  private List<TaskImplementation> tasks;
  
}
