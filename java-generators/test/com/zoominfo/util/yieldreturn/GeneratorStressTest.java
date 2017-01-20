package com.zoominfo.util.yieldreturn;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeneratorStressTest {
    
    /**
     * Passively tracks that only one person is "in the room" at a time...
     */
    static class EntryTracker {
       
        private AtomicBoolean isEntered = new AtomicBoolean(false);
        
        void enter() {
            boolean result = isEntered.compareAndSet(false, true);
            assert(result);
        }
        void exit() {
            boolean result = isEntered.compareAndSet(true, false);
            assert(result);
        }

    }

    static class IntFuncGenerator extends Generator<IntSupplier> {
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
            //System.out.println("GEN " + this.name + " STARTING");
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
            //System.out.println("GEN " + this.name + " ENDING");
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
    public static void busyWork(final long calls, final int depth) {
        if (depth <= 0) {
            assert(calls == 0);
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
    public static void runLambdaTest(int nGenerators, final int yieldCount, final long busyWorkCalls, final int busyWorkDepth ) {

        IntFuncGenerator[] generators = new IntFuncGenerator[nGenerators];
        EntryTracker tracker = new EntryTracker(); // Keeps track of thread reentrancy.
         
        // Create some random unary int functions ...
        IntUnaryOperator[] intFuncs = new IntUnaryOperator[] {
                (i) -> i,
                (i) -> 10*i,
                (i) -> 100*i
        };
        
        tracker.enter(); //Enter the room - only one thead can be "in the room" at a time.
        // Initialize the generators; They are all identical (but) different instances.
        for (int i = 0; i < nGenerators; i++) {
            generators[i] = new IntFuncGenerator("G"+i, intFuncs, tracker, yieldCount, busyWorkCalls, busyWorkDepth);
        }
     
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
                assertEquals(expected,actual);
            }   
        }
        // We expect ALL the iterators to be done now...
        for (IntFuncGenerator gen: generators) {
            Iterator<IntSupplier> iter = gen.defaultIterator();
            tracker.exit();
            boolean actual = iter.hasNext();
            tracker.enter();
            boolean expected = false;
            assertEquals(expected,actual);
        }
    }
    
    
    @Test
    public void testMultiple() {
        // Create multiple iterators and iterate over them "in parallel".
        System.out.println("--TESTMULTIPLE BEGINS ---");
        final int STAGES = 20;
        for (int i=0; i<= STAGES; i++) {
            int busyWorkDepth = Math.max(1, 10*i);
            int yieldCount = 100*i;
            int nGenerators = 2*i; //Math.min(i, 1);
            long multFactor = Math.max(1, (long) nGenerators * yieldCount);
            long busyWorkCalls = Math.max(2*busyWorkDepth,  (long) (Math.pow(2, i)/multFactor));
            System.out.println("STARTING #" + i + ":: nGens:" + nGenerators + " yc:" + yieldCount +" bwC:" + busyWorkCalls + " bwD:" + busyWorkDepth);
            runLambdaTest(nGenerators, yieldCount, busyWorkCalls, busyWorkDepth);
            System.out.println("...ENDING ");
        }
        System.out.println("--TESTMULTIPLE COMPLETES ---");
    }
}
