void setup() {
  runTest();
}



void runTest() {
  final String taskName = "myTask";
  RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        println(taskName + ": Starting Step 1.");
        Thread.sleep(2000);
        println(taskName + ": Finished Step 1. Waiting to do Step 2...");
        context.waitForNextStep();
        println(taskName + ": Starting Step 2.");
        Thread.sleep(2000);
        println(taskName + ": Finished Step 2. Task is complete; exiting run()");
      }
      catch (InterruptedException e) {
        println("Task canceled! Bailing");
      }
    }
  };
  println("MAIN: adding task " + taskName);
  rrs.addTask(myTask, taskName);
  rrs.stepAll();
  rrs.stepAll();
  //rrs.cancelAll();
  boolean ret = rrs.rundownAll(100000);
  println("MAIN: rundown all returns : " + ret);
  println("MAIN: ALL DONE!");
}


void draw() {
}
