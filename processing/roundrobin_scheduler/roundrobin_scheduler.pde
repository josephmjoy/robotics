void setup() {
  g_logger = new SimpleLogger();
  runTest();
  //noLoop();
}

static SimpleLogger g_logger;

void runTest() {
  final String taskName = "myTask";
  // g_logger.trace = true; // Uncomment to enable full tracing (very verbose!)
  final RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        workerLog(taskName + ": Starting Step 1.");
        Thread.sleep(2000);
        workerLog(taskName + ": Finished Step 1. Waiting to do Step 2...");
        if (context.waitForNextStep()) {
          workerLog(taskName + ": Starting Step 2.");
          Thread.sleep(2000);
          workerLog(taskName + ": Finished Step 2. Waiting to do Step 3...");
        }
        if (context.waitForNextStep()) {
          workerLog(taskName + ": Starting Step 3.");
          Thread.sleep(2000);
          workerLog(taskName + ": Finished Step 3. Task is complete; exiting run()");
        }
      }
      catch (InterruptedException e) {
        workerLog(taskName + "Task canceled! Bailing");
      }
    }
  };
  println("MAIN: adding task " + taskName);
  rrs.addTask(myTask, taskName);
  delay(100);
  rrs.stepAll(); // To start off - and step 1
  delay(100);
  rrs.stepAll(); // For step 2
  delay(100);
  rrs.stepAll(); // For step 3
  delay(100);
  rrs.stepAll(); // Extra - nothing should execute

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
