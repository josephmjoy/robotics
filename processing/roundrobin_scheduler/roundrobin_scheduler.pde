void setup() {
  RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        println("Starting task");
        context.waitForNextStep();
        println("Done task");
      }
      catch (InterruptedException e) {
        println("Task canceled! Bailing");
      }
    }
  };
  rrs.addTask(myTask, "myTask");
  try {
  rrs.stepAll();
  rrs.waitForAllDone(0);
  }
  catch (InterruptedException e) {
    println("Oopps - exception while blocked");
  }
}

void draw() {
}
