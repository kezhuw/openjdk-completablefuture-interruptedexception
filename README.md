# `CompletableFuture.get` could swallow `InterruptedException` if waiting future completes immediately after `Thread.interrupt`

In investigating of [FLINK-19489 SplitFetcherTest.testNotifiesWhenGoingIdleConcurrent gets stuck](https://issues.apache.org/jira/browse/FLINK-19489)
with [commit tree](https://github.com/flink-ci/flink-mirror/tree/f8cc82b0c7d3ddd35b17c7f6475b8908363c930a),
I found that `CompletableFuture.get` could swallow `InterruptedException` if waiting future completes immediately after `Thread.interrupt`.

In following sections, I use code from openjdk11. After glimpsing of openjdk repository, I think this problem may apply to openjdk9 and all above.

## `CompletableFuture.waitingGet` should keep interrupt status if it returns no null value

```java
    /**
     * Returns raw result after waiting, or null if interruptible and
     * interrupted.
     */
    private Object waitingGet(boolean interruptible) {
        Signaller q = null;
        boolean queued = false;
        Object r;
        while ((r = result) == null) {
            if (q == null) {
                q = new Signaller(interruptible, 0L, 0L);
                if (Thread.currentThread() instanceof ForkJoinWorkerThread)
                    ForkJoinPool.helpAsyncBlocker(defaultExecutor(), q);
            }
            else if (!queued)
                queued = tryPushStack(q);
            else {
                try {
                    ForkJoinPool.managedBlock(q);
                } catch (InterruptedException ie) { // currently cannot happen
                    q.interrupted = true;
                }
                if (q.interrupted && interruptible)
                    break;
            }
        }
        if (q != null && queued) {
            q.thread = null;
            if (!interruptible && q.interrupted)
                Thread.currentThread().interrupt();
            if (r == null)
                cleanStack();
        }
        if (r != null || (r = result) != null)
            postComplete();
        return r;
    }
```

The last condition in `if` contains an assignment which could get no null return value, if calling thread
reach here because of interruption, that interruption will be swallow due to no null return value.

## Demonstration code
I have added a [maven project](pom.xml) with single file [CompletableFutureGet.java](src/main/java/name/kezhuw/chaos/openjdk/completablefuture/interruptedexception/CompletableFutureGet.java)
to demonstrate the problem. You can use following steps to verify whether the problem exist in particular java version.

1. Configure your java env to java 1.8 or java 9 to 15.
2. `mvn clean package` in project directory.
3. `java -jar target/openjdk-completablefuture-interruptedexception-0.1.0-SNAPSHOT.jar` in project directory.

In openjdk 1.8, the last step run indefinitely with no error. In openjdk 9 or above, when there is no `Thread.sleep`
before `futureGetThread.interrupt()` which execute concurrently with `future.complete`, it probably will print error log
`Future get thread lost interrupt status` and exit with error code `1`.

## OpenJDK code
* openjdk8: https://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/CompletableFuture.java#l278
* openjdk9: https://hg.openjdk.java.net/jdk9/sandbox/jdk/file/17f6f01737c2/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1805
* openjdk10: https://hg.openjdk.java.net/jdk/jdk10/file/b09e56145e11/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1805
* openjdk11: https://hg.openjdk.java.net/jdk/jdk11/file/1ddf9a99e4ad/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1805
* openjdk12: https://hg.openjdk.java.net/jdk/jdk12/file/06222165c35f/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1876
* openjdk13: https://hg.openjdk.java.net/jdk/jdk13/file/0368f3a073a9/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1876
* openjdk14: https://hg.openjdk.java.net/jdk/jdk14/file/6c954123ee8d/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1869
* openjdk15: https://hg.openjdk.java.net/jdk/jdk15/file/0dabbdfd97e6/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1869
* openjdk current: https://hg.openjdk.java.net/jdk/jdk/file/ee1d592a9f53/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1869