void setup() {
  runTest();
}



void runTest() {
  RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        println("Starting Step 1.");
        Thread.sleep(2000);
        println("Finished Step 1. Waiting to do Step 2...");
        context.waitForNextStep();
        println("Starting Step 2.");
        Thread.sleep(2000);
        println("Finished Step 2. Task is complete; exiting run()");
      }
      catch (InterruptedException e) {
        println("Task canceled! Bailing");
      }
    }
  };
  rrs.addTask(myTask, "myTask");
  rrs.stepAll();
  //rrs.cancelAll();
  boolean ret = rrs.rundownAll(100000);
  println("rundown all returns : " + ret);
  println("ALL DONE!");
}


void draw() {
}
