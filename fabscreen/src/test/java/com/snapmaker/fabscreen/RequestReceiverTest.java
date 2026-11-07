package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import fabscreen.libraries.legacy.data.serial.RequestReceiver;
import io.reactivex.Observable;
import io.reactivex.observers.TestObserver;

/** SCR-14 (fail pending requests on link loss, skip cancelled ones) and SCR-18 (thread safety). */
public class RequestReceiverTest {

    private static TestObserver<Object> request(RequestReceiver receiver) {
        return Observable.create(receiver::addEmitter).test();
    }

    @Test
    public void errorAllFailsEveryLiveRequestButNotTheWatcher() {
        RequestReceiver receiver = new RequestReceiver(10002);
        TestObserver<Object> watcher = Observable.create(receiver::setDefaultEmitter).test();
        TestObserver<Object> a = request(receiver);
        TestObserver<Object> cancelled = request(receiver);
        TestObserver<Object> b = request(receiver);
        cancelled.dispose();

        int failed = receiver.errorAll(new IOException("link lost"));

        Assert.assertEquals(2, failed);
        a.assertError(IOException.class);
        b.assertError(IOException.class);
        watcher.assertNoErrors().assertNotComplete();
        Assert.assertEquals(0, receiver.size());

        // After the link loss, new replies go to the watcher again
        receiver.receive("reply");
        watcher.assertValue("reply");
    }

    @Test
    public void errorSkipsCancelledRequests() {
        RequestReceiver receiver = new RequestReceiver(10005);
        TestObserver<Object> cancelled = request(receiver);
        TestObserver<Object> live = request(receiver);
        cancelled.dispose();

        receiver.error(new IOException("bad reply"));

        live.assertError(IOException.class);
        Assert.assertEquals(0, receiver.size());
    }

    @Test
    public void receiveGoesToOldestLiveRequest() {
        RequestReceiver receiver = new RequestReceiver(10007);
        TestObserver<Object> cancelled = request(receiver);
        TestObserver<Object> live = request(receiver);
        cancelled.dispose();
        receiver.receive(42);
        live.assertValue(42).assertComplete();
    }

    @Test
    public void concurrentAddAndReceiveLoseNothing() throws InterruptedException {
        final RequestReceiver receiver = new RequestReceiver(10014);
        final int n = 2000;
        final AtomicInteger delivered = new AtomicInteger();
        final CountDownLatch added = new CountDownLatch(1);

        Thread adder = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                Observable.create(receiver::addEmitter).subscribe(o -> delivered.incrementAndGet());
            }
            added.countDown();
        });
        adder.start();

        int receivedCalls = 0;
        long deadline = System.currentTimeMillis() + 10000;
        while (receivedCalls < n && System.currentTimeMillis() < deadline) {
            if (receiver.size() > 0) {
                receiver.receive("ok");
                receivedCalls++;
            }
        }
        Assert.assertTrue(added.await(10, TimeUnit.SECONDS));
        Assert.assertEquals(n, receivedCalls);
        Assert.assertEquals(n, delivered.get());
        Assert.assertEquals(0, receiver.size());
    }
}
