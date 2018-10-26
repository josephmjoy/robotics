void setup() {
  runTest();
  //noLoop();
}

private static long startTime = System.currentTimeMillis();
private static int counter = 0;
static private Thread g_mainThread ;
static void log0(String prefix, String s) {
  String tab = Thread.currentThread() == g_mainThread ? "" : "====";
  System.out.println(String.format("%02d:%05d %s%s %s", counter, System.currentTimeMillis() - startTime, tab, prefix, s));
  counter++;
}

void runTest() {
  g_mainThread = Thread.currentThread();
  final String taskName = "myTask";
  final RoundRobinScheduler rrs = new RoundRobinScheduler();
  RoundRobinScheduler.Task myTask = new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        log0("WORKER: ", taskName + ": Starting Step 1.");
        Thread.sleep(2000);
        log0("WORKER: ", taskName + ": Finished Step 1. Waiting to do Step 2...");
        context.waitForNextStep();
        log0("WORKER: ", taskName + ": Starting Step 2.");
        Thread.sleep(2000);
        log0("WORKER: ", taskName + ": Finished Step 2. Task is complete; exiting run()");
      }
      catch (InterruptedException e) {
        log0("WORKER: ", taskName + "Task canceled! Bailing");
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

void draw() {
}
