/**
 * PROJECT: Robotics sample code.
 * Module: Stress test for the "Java Generator" code from https://github.com/domlachowicz/java-generators.
 */
package com.zoominfo.util.yieldreturn;
import org.junit.Test;
import java.util.Iterator;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeneratorStressTest {
    Logger log = new Logger();
    
    class Logger {
        
         void logError(String s) {
            System.out.println("ERR, " + s);
        }
        
        void logWarning(String s) {
            System.out.println("WARN, " + s);
        }
        
        void logInfo(String s) {
            System.out.println("INFO, " + s);
        }
        void logAssert(boolean b, String s) {
            if (!b) {
                System.out.println("ASSERTFAIL, " + s);
            }
            assert(b);
        }
    }
    
    /**
     * Passively tracks that only one person is "in the room" at a time...
     */
    class EntryTracker {
       
        private AtomicBoolean isEntered = new AtomicBoolean(false);
        
        void enter() {
            boolean result = isEntered.compareAndSet(false, true);
            log.logAssert(result, "Entering a room with someone in it");
        }
        void exit() {
            boolean result = isEntered.compareAndSet(true, false);
            log.logAssert(result, "Leaving a room with no one in it");
        }
    }


    /**
     * Wraps a generator that returns an "int supplier", i.e., a
     * function with no arguments that returns an int.
     * Internally, it cycles through a set of sequence generators (intFuncs).
     * It also uses an "entry tracker" to verify that client and iterator code are strictly interleaved.
     */
    class IntFuncGenerator extends Generator<IntSupplier> {
        final String name;
        int yieldCount;
        IntUnaryOperator[] intFuncs;
        long busyWorkCalls;
        int busyWorkDepth;
        Iterator<IntSupplier> myIterator;
        EntryTracker tracker; 
        
        /**
         * Returns a generator that yields exactly yieldCount times. The lambda returned in the ith
         *  iteration is intFuncs[i%intFuncs.length].applyAsInt(i) where i is the ith iteration.
         * @param name  Name of this generator. Used for debugging/logging.
         * @param intFuncs  Array of int functions that determine successive lambdas 
         * @param yieldCount Exact number of iterations for this generator.
         * @param busyWorkCalls  Count of extra "busy work" calls to make before yielding. 
         * @param busyWorkDepth Recursion depth when performing "busy work".r
         */
        public IntFuncGenerator(String name, IntUnaryOperator[] intFuncs, EntryTracker tracker, int yieldCount, long busyWorkCalls, int busyWorkDepth) {
            this.name = name;
            this.yieldCount = yieldCount;
            this.intFuncs = intFuncs;
            this.tracker = tracker;
            this.busyWorkCalls = busyWorkCalls;
            this.busyWorkDepth = busyWorkDepth;
            this.myIterator = null;
        }
        
        @Override
        protected void run() {
            tracker.enter(); // "Enter the room" (only one thread can be "in the room").
            //log.logInfo("INTGEN, name:" + this.name + ", STARTING");
            int count = yieldCount;
            while (count > 0) {                
                for (IntUnaryOperator func : intFuncs) {                   
                    if (count <= 0) {
                        break;
                    }
                    int index = yieldCount - count; // 0-based index of the iterator
                    count--;
                    busyWork(busyWorkCalls, busyWorkDepth);
                    final int param = index;
                    tracker.exit();
                    yield(() -> func.applyAsInt(param));
                    tracker.enter();
                }
            }
            
            // A final exit...
            //log.logInfo("INTGEN, name:" + this.name + ", ENDING");
            tracker.exit();
        }
        
        /**
         * For testing purposes, we maintain an on-demand single iterator.
         * @return Return the "default" iterator for this instance.
         */
        public Iterator<IntSupplier> defaultIterator() {
            if (myIterator == null) {
                myIterator = this.iterator();
            }
            return myIterator;
        }
    }


    /**
     * Do meaningless "busy work", recursing if necessary
     * @param calls Minimum number of calls to make
     * @param depth Call depth
     */
    public void busyWork(final long calls, final int depth) {
        if (depth <= 0) {
            log.logAssert(calls == 0, "busyWork called with 0 depth and nonzero calls");
            return; // ***EARLY RETURN***
        }

        long callsLeft = Math.max(1,  calls-depth);
        while (callsLeft > 0) {
            busyWork(depth-1, depth-1);
            callsLeft -= depth;
        }
    }
    
    /**
     * Create multiple generators and iterate over them "in parallel".
     * @param nGenerators Count of generators
     * @param yieldCount  Total number of yields (for all generators)
     * @param busyWorkCalls Number of calls when doing "busy work" before each yield.
     * @param busyWorkDepth  Recursion depth when doing "busy work" before each yield.
     */
    public void runLambdaTest(int nGenerators, final int yieldCount, final long busyWorkCalls, final int busyWorkDepth ) {

        IntFuncGenerator[] generators = new IntFuncGenerator[nGenerators];
        EntryTracker tracker = new EntryTracker(); // Keeps track of thread reentrancy.
         
        // Create some random unary int functions ...
        // These ones return the ith multiple of the nth prime.
        // The generators will keep cycling through these functions to
        // generate the lambda that is returned as the next yield value. The client code is expecting this and
        // again verifies this sequence. If the Generator code returns values in the wrong sequence
        // it will be caught by the client code.
        IntUnaryOperator[] intFuncs = new IntUnaryOperator[] {
                (i) -> 2*i,
                (i) -> 3*i,
                (i) -> 7*i,
                (i) -> 11*i,
                (i) -> 13*i,
                (i) -> 17*i,
                (i) -> 19*i,
                (i) -> 23*i,
                (i) -> 29*i,
                (i) -> 31*i
        };
        
        tracker.enter(); //Enter the room - only one thead can be "in the room" at a time.
        
        // Initialize the generators; They are all identical (but) different instances.
        for (int i = 0; i < nGenerators; i++) {
            generators[i] = new IntFuncGenerator("G"+i, intFuncs, tracker, yieldCount, busyWorkCalls, busyWorkDepth);
        }
     
        // Run through the generators "in parallel"
        for (int i=0; i<yieldCount; i++) {
            for (IntFuncGenerator gen: generators) {
                Iterator<IntSupplier> iter = gen.defaultIterator();
                // Occasionally yield...
                // (We could make this random but it is arguably more
                //  repeatable this way...)
                if (i % 3 == 0) {
                    Thread.yield();
                }
                tracker.exit(); // "Exit the room"
                IntSupplier func = iter.next();
                tracker.enter(); // Get back "in the room"
                int actual = func.getAsInt();
                int expected = intFuncs[i % intFuncs.length].applyAsInt(i);
                log.logAssert(expected == actual, "runLambdaTest, generator:" + gen.name + ", iteration:" + i + " expected:" + expected + ", actual:" + actual);
            }   
        }
        // We expect ALL the iterators to be done now...
        for (IntFuncGenerator gen: generators) {
            Iterator<IntSupplier> iter = gen.defaultIterator();
            tracker.exit();
            boolean actual = iter.hasNext();
            tracker.enter();
            boolean expected = false;
            log.logAssert(expected == actual, "runLambda, generator:" + gen.name + ", Unexpected items left");
        }
    }
    
    /**
     * Goes through stages, running more and more intensive versions of runLambdaTests.
     */
    @Test
    public void testMultiple() {
        // Create multiple iterators and iterate over them "in parallel".
        log.logInfo("testMultiple, ----TESTING BEGINS----");
        final int STAGES = 20;
        for (int i=0; i<= STAGES; i++) {
            int busyWorkDepth = Math.max(1, 10*i);
            int yieldCount = 100*i;
            int nGenerators = 2*i; //Math.min(i, 1);
            long multFactor = Math.max(1, (long) nGenerators * yieldCount);
            long busyWorkCalls = Math.max(2*busyWorkDepth,  (long) (Math.pow(2, i)/multFactor));
            log.logInfo("testMultiple,    STAGE START, stage:" + i + ", nGens:" + nGenerators + ", yieldCount:" + yieldCount +", bwCalls:" + busyWorkCalls + ", bwDepth:" + busyWorkDepth);
            runLambdaTest(nGenerators, yieldCount, busyWorkCalls, busyWorkDepth);
            log.logInfo(" testMultiple,   STAGE END, stage:" + i);
        }
        log.logInfo("testMultiple, ----TESTING COMPLETES----");
    }
}
