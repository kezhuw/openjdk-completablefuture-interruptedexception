package name.kezhuw.chaos.openjdk.completablefuture.interruptedexception;

import java.io.PrintStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class CompletableFutureGet {
    private static final PrintStream stdout = System.out;
    private static final PrintStream stderr = System.err;

    private static final FutureWaiter futureWaiter;

    static {
        String method = System.getenv("FUTURE_WAIT_METHOD");
        if (method == null || method.equalsIgnoreCase("get")) {
            futureWaiter = CompletableFuture::get;
        } else if (method.equalsIgnoreCase("join")) {
            futureWaiter = CompletableFuture::join;
        } else {
            String msg = String.format("Invalid FUTURE_WAIT_METHOD value %s, candidates are get and join", method);
            throw new IllegalArgumentException(msg);
        }
    }

    @FunctionalInterface
    interface FutureWaiter {
        void wait(CompletableFuture<Void> future) throws InterruptedException, ExecutionException;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; ; i++) {
            long sleepMills = ThreadLocalRandom.current().nextLong(10);
            stdout.format("%d: sleep mills: %d\n", i, sleepMills);

            CompletableFuture<Void> future = new CompletableFuture<>();
            AtomicBoolean interrupted = new AtomicBoolean();

            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Thread futureCompleteThread = new Thread(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    future.complete(null);
                } catch (InterruptedException ex) {
                    stdout.println("Got unexpected interrupted exception in future complete thread");
                    ex.printStackTrace();
                    System.exit(128);
                }
            });
            futureCompleteThread.setDaemon(true);
            futureCompleteThread.start();

            CountDownLatch futureGotLatch = new CountDownLatch(1);
            Thread futureGetThread = new Thread(() -> {
                try {
                    futureWaiter.wait(future);
                    while (!interrupted.get()) {
                        Thread.yield();
                    }
                    // XXX: Test whether interrupt status was lost.
                    if (Thread.currentThread().isInterrupted()) {
                        stdout.println("Future get thread was interrupted.");
                    } else {
                        stderr.println("Future get thread lost interrupt status");
                        System.exit(1);
                    }
                } catch (InterruptedException ex) {
                    // Thread.currentThread().interrupt();
                    stdout.println("future.get() got interrupted");
                    try {
                        futureWaiter.wait(future);
                    } catch (Exception ex1) {
                        stderr.println("Got unexpected exception");
                        ex.printStackTrace();
                    }
                } catch (ExecutionException ex) {
                    stderr.println("Got unexpected execution exception");
                    ex.printStackTrace();
                    System.exit(128);
                }
                futureGotLatch.countDown();
            });
            futureGetThread.setDaemon(true);
            futureGetThread.start();

            Thread interruptingThread = new Thread(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    if (sleepMills > 0) {
                        Thread.sleep(sleepMills);
                    }
                    futureGetThread.interrupt();
                    interrupted.set(true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            interruptingThread.setDaemon(true);
            interruptingThread.start();

            readyLatch.await();
            startLatch.countDown();
            futureGotLatch.await();
        }
    }
}
