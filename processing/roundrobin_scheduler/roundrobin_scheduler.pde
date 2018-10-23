void setup() {
  RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        println("Finished Step 1. Waiting to do Step 2...");
        context.waitForNextStep();
        println("Finished Step 2. Task is complete.");
      }
      catch (InterruptedException e) {
        println("Task canceled! Bailing");
      }
    }
  };
  rrs.addTask(myTask, "myTask");
  try {
    rrs.stepAll();
    rrs.cancelAll();
    boolean ret = rrs.rundownAll(0);
    println("rundown all returns : " + ret);
    println("ALL DONE!");
  }
  catch (InterruptedException e) {
    println("Oops - exception while blocked");
  }
}

void draw() {
}
