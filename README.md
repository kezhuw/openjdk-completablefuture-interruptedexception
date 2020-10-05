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
to demonstrate the problem. You could change `java.version` in [pom.xml](pom.xml) to other versions to check whether this
problem exists in a particular java version.

## OpenJDK code
* openjdk9: https://hg.openjdk.java.net/jdk9/sandbox/jdk/file/17f6f01737c2/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java
* openjdk10: https://hg.openjdk.java.net/jdk/jdk10/file/b09e56145e11/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java
* openjdk11: https://hg.openjdk.java.net/jdk/jdk11/file/1ddf9a99e4ad/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1809
* openjdk15: https://hg.openjdk.java.net/jdk/jdk15/file/0dabbdfd97e6/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1873
* openjdk current: https://hg.openjdk.java.net/jdk/jdk/file/ee1d592a9f53/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#l1873