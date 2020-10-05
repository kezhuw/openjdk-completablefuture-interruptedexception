# `CompletableFuture.get` could swallow `InterruptedException` if waiting future completes immediately after `Thread.interrupt`

In investigating of [FLINK-19489 SplitFetcherTest.testNotifiesWhenGoingIdleConcurrent gets stuck](https://issues.apache.org/jira/browse/FLINK-19489)
with [commit tree](https://github.com/flink-ci/flink-mirror/tree/f8cc82b0c7d3ddd35b17c7f6475b8908363c930a),
I found that `CompletableFuture.get` could swallow `InterruptedException` if waiting future completes immediately after `Thread.interrupt`.

In following sections, I use code from openjdk11. After glimpsing of openjdk repository, I think this problem may apply to openjdk9 and all above.

## `CompletableFuture.waitingGet` should keep interrupt status if it returns no null value

Let's start with `CompletableFuture.get` and `CompletableFuture.reportGet` to get constraints
`CompletableFuture.waitingGet` must follow.

```java
public T get() throws InterruptedException, ExecutionException {
    Object r;
    if ((r = result) == null)
        r = waitingGet(true);
    return (T) reportGet(r);
}

/**
 * Reports result using Future.get conventions.
 */
private static Object reportGet(Object r)
    throws InterruptedException, ExecutionException {
    if (r == null) // by convention below, null means interrupted
        throw new InterruptedException();
    if (r instanceof AltResult) {
        Throwable x, cause;
        if ((x = ((AltResult)r).ex) == null)
            return null;
        if (x instanceof CancellationException)
            throw (CancellationException)x;
        if ((x instanceof CompletionException) &&
            (cause = x.getCause()) != null)
            x = cause;
        throw new ExecutionException(x);
    }
    return r;
}
```

The single parameter of `reportGet` comes from `waitingGet` and `reportGet` throws `InterruptedException`
if and only if its parameter is `null`. This means that if waiting future is interrupted and completed at
the same time in `CompletableFuture.waitingGet`, it has only two choices:
1. Return null and clear interrupt status, otherwise we will get double-interruption.
2. Return no-null value and keep interrupt status, otherwise we will lose that interruption and later
  interruptible method may hang.

Let's see whether, `waitingGet` conforms to rule-2 if it returns no-null value.

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
                // tag(interrupted): `ForkJoinPool.managedBlock` return due to interrupted,
                // interrupt status was cleared.
                break;
        }
    }
    if (q != null && queued) {
        q.thread = null;
        // tag(self-interrupt): this applies only to `CompletableFuture.join`.
        if (!interruptible && q.interrupted)
            Thread.currentThread().interrupt();
        if (r == null)
            cleanStack();
    }
    if (r != null || (r = result) != null)      // tag(assignment)
        postComplete();
    return r;
}
```

I add three placeholders `tag(interrupted)`, `tag(self-interrupt)` and `tag(assignment)` in comments. Here
are execution steps and assumptions:

* Let's assume that an interruption occurs before `tag(interrupted)` and future completion, then
  `tag(interrupted)` will break while-loop in `CompletableFuture.get` path with interrupt status cleared.

* `if` block in `tag(self-interrupt)` applies only to `CompletableFuture.join` which is a no interruptible
  blocking method, it restores interrupt status if interruption occurs in blocking wait. It is skipped in
  `CompletableFuture.get` path.

* `(r = result) != null` in `tag(assignment)` assign `result` to return value and check it. What if the future
  is completed by other thread before `tag(assingment)` ? `result` field will have no-null value, then `waitingGet`
  will return no-null value, and lose interrupt status in `q.interrupted`.

## Demonstration code
I have added a ready-to-run [maven project](pom.xml) with single file [CompletableFutureGet.java](src/main/java/name/kezhuw/chaos/openjdk/completablefuture/interruptedexception/CompletableFutureGet.java)
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