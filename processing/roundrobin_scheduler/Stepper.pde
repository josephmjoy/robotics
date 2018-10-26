// Class Stepper synchronizes a "supervisor" and a "worker". The
// supervisor blocks while the worker performs a quanta (step) of work.
static class StepCoordinator {
  
  private enum OwnerID {
    SUPERVISOR, 
      WORKER
  };
  private Object lock = new Object(); // Used for all synchronization of ALL field variables

  // Modified by both owner and supervisor
  private OwnerID owner = OwnerID.SUPERVISOR; // Who owns the stepper   

  // Modified just by supervisor
  private volatile boolean last = false; // No more steps forthcoming

  // Modified just by worker
  private int stepCount = 0; // Incremented after each step is complete
  private volatile boolean workerDone = false; // Worker has quit
  private volatile boolean inLastStep = false; // Worker performing last step


  // Supervisor side: make worker progress by one step. Blocks until the worker has
  // completed the step.
  void step() {
    internalStep(false); // false == not final step
  }


  // Supervisor side: a "final" version of step that will be
  // the last call to step.
  void lastStep() {
    internalStep(true); // true == final step
  }


  // Worker side: blocks until next step can be done.
  // Return:  true if there will be more steps, false
  //     if this is the final step (so cleanup may be performed).
  // If the exception is thrown, the caller MUST NOT attempt to
  // await any more steps. It does not 
  // need to (but can) call stopAwaitingSteps();
  // Once false is returned, a subsequent call to awaitStep
  // WILL result in an InterruptedException being thrown.
  boolean awaitStep() throws InterruptedException {
    boolean ret;
    try {
      synchronized(lock) {
        if (inLastStep) {
          // Oh oh. Worker has completed a final step. It 
          // SHOULD NOT call awaitStep again.
          throw new IllegalStateException("Atempt to await step after a final step!");
        }
        if (stepCount > 0) {
          // Worker has previously done at least 1 step, so it
          // is owner. Transfer ownership back to supervisor
          transferOwnershipLK(OwnerID.WORKER, OwnerID.SUPERVISOR);
        }
        awaitOwnershipLK(OwnerID.WORKER);
        stepCount++;
        inLastStep = last; // Record if worker is in final step
        ret = !last;
      }
    }
    catch (InterruptedException e) {
      // We do not expect an interrupt exception,
      // but if we do get one, we quit this task
      // 
      System.err.println("Caught interrupt exception in awaitStep");
      stopAwaitingSteps();
      throw e;
    }
    return ret;
  }


  // Worker: notify supervisor that worker will no longer
  // wait to do steps. Worker must own the stepper to call
  // this.
  void stopAwaitingSteps() {
    synchronized(lock) {
      workerDone = true;
      transferOwnershipLK(OwnerID.WORKER, OwnerID.SUPERVISOR);
    }
  }

  @Override
  public String toString() {
    return "STEPPER [ow:" + owner + " sc:" + stepCount + "]";
  }
  
  private void internalStep(boolean finalStep) {
    log("entering internalStep");
    
    if (workerDone) {
      log("exiting internalStep EARLY because worker done.");
      return; // ********* EARLY RETURN *****************
    }

    synchronized(lock) {
      if (finalStep) {
        assert !this.last;
        this.last = true;
      }
      // Transfer ownership to worker and wait
      transferOwnershipLK(OwnerID.SUPERVISOR, OwnerID.WORKER);
      try {
        awaitOwnershipLK(OwnerID.SUPERVISOR);
      }
      catch (InterruptedException e) {
        // We do not expect the thread to be interrupted.
        // This is a fatal error as we can no longer guarantee
        // serialized execution across the supervisors and workers
        fatalError("Supervisor caught InterruptException waiting for worker. e: " + e);
      }
    }
    log("Exiting internalStep");
  }

  // MUST be called with lock held.
  // Throws InterruptedException if wait interrupted
  void awaitOwnershipLK(OwnerID newOwner) throws InterruptedException {
    log(newOwner + " waiting for ownership");
    while (owner != newOwner) {
      lock.wait();
    }
    log(newOwner + " claimed ownership");
  }
  // MUST be called with lock held.
  // Throws IllegalStateException if current owner is not {from}.
  private void transferOwnershipLK(OwnerID from, OwnerID to) {
    log("transferring ownership from " + from + " to " + to);
    verifyOwnershipLK(from);
    owner = to;
    lock.notify();
  }

  // MUST be called with lock held
  private void verifyOwnershipLK(OwnerID id) {
    if (owner != id) {
      String msg = "STEPPER owner is " + owner + "; Expecting " + id;
      System.err.println(msg);
      throw new IllegalStateException(msg);
    }
  }

  private void fatalError(String s) {
    System.err.println("STEPPER" + s);
    throw new RuntimeException("STEPPER: " + s);
  }
  private void log(String s) {
    log0(this.toString(), s);
    //log0("STEPPER ", s);
  }
}
