package name.kezhuw.chaos.openjdk11.completablefuture.interruptedexception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class CompletableFutureGet {
    private static final Logger logger = LoggerFactory.getLogger("ROOT");

    public static void main(String[] args) throws Exception {
        for (int i = 0; ; i++) {
            long sleepMills = ThreadLocalRandom.current().nextLong(10);
            logger.info("{}: sleep mills: {}", i, sleepMills);

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
                    logger.error("Got unexpected interrupted exception in future complete thread", ex);
                    System.exit(128);
                }
            });
            futureCompleteThread.setDaemon(true);
            futureCompleteThread.start();

            CountDownLatch futureGotLatch = new CountDownLatch(1);
            Thread futureGetThread = new Thread(() -> {
                try {
                    future.get();
                    while (!interrupted.get()) {
                        Thread.yield();
                    }
                    // XXX: Test whether interrupt status was lost.
                    if (Thread.currentThread().isInterrupted()) {
                        logger.info("Future get thread was interrupted.");
                    } else {
                        logger.error("Future get thread lost interrupt status");
                        System.exit(1);
                    }
                } catch (InterruptedException ex) {
                    // Thread.currentThread().interrupt();
                    logger.info("future.get() got interrupted");
                    try {
                        future.get();
                    } catch (Exception ex1) {
                        logger.error("Got unexpected exception", ex);
                    }
                } catch (ExecutionException ex) {
                    logger.error("Got unexpected execution exception", ex);
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
