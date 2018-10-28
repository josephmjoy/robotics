
volatile int counter = 0;

RoundRobinScheduler rrs;
final int NUM_TASKS = 5;
final int NUM_STEPS = 5;

void runStressTest() {
  rrs = new RoundRobinScheduler();
  for (int i = 0; i < NUM_TASKS; i++) {
    RoundRobinScheduler.Task myTask = makeStressTask(NUM_STEPS);
    String taskName = "STRESS_TASK_" + i;
    rrs.addTask(myTask, taskName);
  }
}

int loopCounter = 0;

void stressLoop() {
  final int NUM_STEP_ALLS  = NUM_STEPS + 1;
  if (loopCounter < NUM_STEP_ALLS) {
    rrs.stepAll();
    counter++;
  }

  if (loopCounter == NUM_STEP_ALLS) {
    System.out.println("Counting complete. Starting task rundown");
    rrs.rundownAll(10000000);
    System.out.println("Finished task roundown");
    int expected = NUM_TASKS * NUM_STEPS + NUM_STEP_ALLS;
    if (expected != counter) {
      System.err.println("STRESS TEST FAILURE: Expected " + expected + "; got " + counter);
    } else {
      System.out.println("STRESS TEST SUCCESS: Tasks: " + NUM_TASKS + "; Steps: " + NUM_STEPS + " counter: " + counter);
    }
  }
  loopCounter++;
}

RoundRobinScheduler.Task makeStressTask(final int N) {
  return new RoundRobinScheduler.Task() {
    public  void run(RoundRobinScheduler.TaskContext context) {
      try {
        for (int i = 0; i < N && context.waitForNextStep(); i++) {
          g_logger.trace("****STRESS TASK****", " i=" + i + "/" + N);
          int x = counter;
          Thread.sleep(10);
          counter  = x + 1;
        }
      } 
      catch (InterruptedException e) {
        workerLog("Stress task canceled! Bailing");
      }
      g_logger.info("****STRESS TASK****", "DONE");
    }
  };
}
