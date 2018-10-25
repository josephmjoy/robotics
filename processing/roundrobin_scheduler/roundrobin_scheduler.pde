void setup() {
  runTest();
}



void runTest() {
  final String taskName = "myTask";
  final RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        rrs.log0("WORKER: ", taskName + ": Starting Step 1.");
        Thread.sleep(2000);
        rrs.log0("WORKER: ", taskName + ": Finished Step 1. Waiting to do Step 2...");
        context.waitForNextStep();
        rrs.log0("WORKER: ", taskName + ": Starting Step 2.");
        Thread.sleep(2000);
        rrs.log0("WORKER: ", taskName + ": Finished Step 2. Task is complete; exiting run()");
      }
      catch (InterruptedException e) {
        rrs.log0("WORKER: ", taskName + "Task canceled! Bailing");
      }
    }
  };
  println("MAIN: adding task " + taskName);
  rrs.addTask(myTask, taskName);
  rrs.stepAll();
  delay(10);
  rrs.stepAll();
  //rrs.cancelAll();
  boolean ret = rrs.rundownAll(100000);
  println("MAIN: rundown all returns : " + ret);
  println("MAIN: ALL DONE!");
}


void draw() {
}
