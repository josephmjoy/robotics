void setup() {
  g_logger = new SimpleLogger();
  runTest();
  //noLoop();
}

static SimpleLogger g_logger;

void runTest() {
  final String taskName = "myTask";
  final RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        workerLog(taskName + ": Starting Step 1.");
        Thread.sleep(2000);
        workerLog(taskName + ": Finished Step 1. Waiting to do Step 2...");
        context.waitForNextStep();
        workerLog(taskName + ": Starting Step 2.");
        Thread.sleep(2000);
        workerLog(taskName + ": Finished Step 2. Task is complete; exiting run()");
      }
      catch (InterruptedException e) {
        workerLog(taskName + "Task canceled! Bailing");
      }
    }
  };
  println("MAIN: adding task " + taskName);
  rrs.addTask(myTask, taskName);
  delay(100);
  rrs.stepAll();
  delay(100);
  rrs.stepAll();
  //rrs.cancelAll();
  //boolean ret = rrs.rundownAll(100000);
  //println("MAIN: rundown all returns : " + ret);
  println("MAIN: ALL DONE!");
}

void workerLog(String s) {
  g_logger.info("WORKER: ", s);
}

void draw() {
}
