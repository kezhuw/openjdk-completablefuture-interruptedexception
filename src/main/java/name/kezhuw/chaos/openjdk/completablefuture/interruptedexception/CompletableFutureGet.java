package name.kezhuw.chaos.openjdk.completablefuture.interruptedexception;

import java.io.PrintStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CompletableFutureGet {
    private static final PrintStream stdout = System.out;
    private static final PrintStream stderr = System.err;

    private static final String futureMethod;
    private static final FutureWaiter futureWaiter;

    private static final int maxRuns;

    static {
        String method = System.getenv("FUTURE_WAIT_METHOD");
        if (method == null || method.equalsIgnoreCase("get")) {
            futureMethod = "get()";
            futureWaiter = CompletableFuture::get;
        } else if (method.equalsIgnoreCase("timed_get")) {
            futureMethod = "get(1000, TimeUnit.DAYS)";
            futureWaiter = (CompletableFuture<Void> future) -> {
                try {
                    future.get(1000, TimeUnit.DAYS);
                } catch (TimeoutException ex) {
                    stderr.println("Got unexpected exception");
                    ex.printStackTrace();
                    System.exit(128);
                }
            };
        } else if (method.equalsIgnoreCase("join")) {
            futureMethod = "join()";
            futureWaiter = CompletableFuture::join;
        } else {
            String msg = String.format("Invalid FUTURE_WAIT_METHOD value %s, candidates are get, timed_get and join", method);
            throw new IllegalArgumentException(msg);
        }
    }

    static {
        String value = System.getenv("MAX_RUNS");
        if (value == null) {
            maxRuns = 1000;
        } else {
            maxRuns = Integer.parseInt(value, 10);
            if (maxRuns <= 0) {
                throw new IllegalArgumentException("Environment variable MAX_RUNS must greater than 0");
            }
        }
    }

    @FunctionalInterface
    interface FutureWaiter {
        void wait(CompletableFuture<Void> future) throws InterruptedException, ExecutionException;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 1; i <= maxRuns; i++) {
            long sleepMills = ThreadLocalRandom.current().nextLong(10);
            final String sleepString = sleepMills == 0 ? "--------" : String.format("sleep(%d)", sleepMills);
            final String prefix = String.format("%4d/%d interrupt-%s-complete", i, maxRuns, sleepString);

            CompletableFuture<Void> future = new CompletableFuture<>();

            CountDownLatch waitingFutureLatch = new CountDownLatch(1);
            CountDownLatch futureGotLatch = new CountDownLatch(1);

            Thread futureGetThread = new Thread(() -> {
                try {
                    waitingFutureLatch.countDown();
                    futureWaiter.wait(future);
                    // XXX: Test whether interrupt status was lost.
                    if (Thread.currentThread().isInterrupted()) {
                        stdout.format("%s: future.%s completes, Thread.isInterrupted returns true\n", prefix, futureMethod);
                    } else {
                        stderr.format("%s: future.%s completes, Thread.isInterrupted returns false\n", prefix, futureMethod);
                        System.exit(1);
                    }
                } catch (InterruptedException ex) {
                    stdout.format("%s: future.%s is interrupted.\n", prefix, futureMethod);
                    try {
                        futureWaiter.wait(future);
                    } catch (Exception ex1) {
                        stderr.println("Got unexpected exception");
                        ex.printStackTrace();
                        System.exit(128);
                    }
                } catch (ExecutionException ex) {
                    stderr.println("Got unexpected execution exception");
                    ex.printStackTrace();
                    System.exit(128);
                }
                futureGotLatch.countDown();
            }, String.format("future-get-thread-%d", i));
            futureGetThread.setDaemon(true);
            futureGetThread.start();

            waitingFutureLatch.await();
            Thread.sleep(1);

            try {
                futureGetThread.interrupt();
                if (sleepMills > 0) {
                    Thread.sleep(sleepMills);
                }
                future.complete(null);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }

            futureGotLatch.await();
        }
    }
}
