  // Class Stepper synchronizes a "supervisor" and a "worker". The
  // supervisor blocks while the worker performs a quanta (step) of work.
  private class OldStepper {

    private volatile boolean canStep = false;
    private volatile boolean stepDone = false;
    private volatile boolean workerDone = false; // Worker has quit
    private volatile boolean last = false; // No more steps forthcoming
    private volatile boolean inLastStep = false; // Worker performing last step

    // Supervisor side: make worker progress by one step. Blocks until the worker has
    // completed the step.
    void step() {
      internalStep(false);
    }

    // Supervisor side: a "final" version of step that will be
    // the last call to step.
    void lastStep() {
      internalStep(true);
    }


    // Worker side: blocks until next step can be done.
    // If the exception is thrown, the caller MUST NOT attempt to
    // await any more steps. It does not 
    // need to (but can) call stopAwaitingSteps();
    // {return} is true if there will be more steps. {false}
    // if this is the final step (so cleanup may be performed).
    // Once false is returned, a subsequent call to awaitStep
    // WILL result in an InterruptedException being thrown.
    boolean awaitStep() throws InterruptedException {
      try {
        // Optionally notify superviser that previous step was complete
        synchronized(this) {
          if (canStep) {
            // We have been doing a step
            if (inLastStep) {
              // Oh oh. Worker has completed a final step. It 
              // SHOULD NOT call awaitStep again.
              throw new InterruptedException("Atempt to await step after a final step!");
            }
            assert(!stepDone);
            //canStep = false; // Very important.
            stepDone = true;
            this.notify();
          }
          //}

          // Wait for next step

          // STRANGE: Adding this step here things work, remove it and it dies without signaling anything.
          // Thread.sleep(1);
          //synchronized(this) {
          //log0("STePPer", "ABOUT TO WAIT FOR NEXT STEP");
          // This while loop may not be necessary, but some web reports talk about
          // spurious wakes.
          do {
            assert(!stepDone);
            System.err.println("WAITING FOR NEXT STEP");
            this.wait();
            System.err.println("DONE WAITING FOR NEXT STEP");
          } while (!workerDone && !canStep);
          // Record whether or not this is the start of the
          // last step
          inLastStep = last;
        }
      }
      catch (InterruptedException e) {
        // We do not expect an interrupt exception,
        // but if we do get one, we quit this task
        // 
        System.err.println("Caught interrupt exception in awaitStep");
        println("DONE DONE DONE");
        done();
        throw (e);
      }
      return !last;
    }


    // Worker: notify supervisor that worker will no longer
    // wait to do steps. Idempotent.
    void done() {
      synchronized(this) {
        println("IN DONE; canStep = " + canStep);
        //assert(canStep);
        //assert(!stepDone);
        workerDone = true; // May already be true - in exception case
        this.notify();
      }
    }


    private void internalStep(boolean finalStep) {
      log0("STEPPER: ", "entering internalStep");
      if (workerDone) {
        log0("STEPPER: ", "entering internalStep EARLY because worker done.");
        return; // ********* EARLY RETURN *****************
      }

      // Notify worker that work is available
      synchronized(this) {
        assert(!canStep);
        assert(!stepDone);
        canStep = true;
        if (finalStep) {
          assert(!this.last);
          this.last = true;
        }
        this.notify();
      }

      // Wait for step to complete
      try {
        synchronized(this) {
          // This while loop may not be necessary, but some web reports talk about
          // spurious wakes.
          while (!workerDone && !stepDone) {
            //assert(canStep);
            this.wait();
          }
          canStep = stepDone = false;
        }
      }
      catch (InterruptedException e) {
        // We do not expect the thread to be interrupted.
        // Neverthless, if it does, we cancel further stepping.
        // TODO: This will leave all the workers hanging, waiting for their
        // next step. But caller could potentially interrupt those threads.
        // But how does it know that this has happened?
        last = true;
        System.err.println("WorkItem.step caught execption " + e);
        System.err.flush();
      }
      log0("STEPPER: ", "Exiting internalStep");
    }
  }
