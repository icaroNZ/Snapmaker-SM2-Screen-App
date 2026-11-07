package com.snapmaker.fabscreen.modules.control;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import fabscreen.libraries.legacy.data.ISlaveComputer;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketContent;
import io.reactivex.Observable;
import io.reactivex.observers.TestObserver;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.TestScheduler;

/** SCR-06/SCR-12: M5 on leaving the Control page never errors and never hangs. */
public class ToolPowerGuardTest {

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    /** ISlaveComputer whose sendGcode returns {@code reply} and records the G-code sent. */
    private static ISlaveComputer slave(final Observable<FabPacketContent.GcodeResponse> reply, final List<String> sent) {
        return (ISlaveComputer) Proxy.newProxyInstance(ISlaveComputer.class.getClassLoader(),
                new Class<?>[]{ISlaveComputer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendGcode")) {
                        sent.add((String) args[0]);
                        return reply;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void acknowledgedM5IsTrue() {
        List<String> sent = new ArrayList<>();
        TestObserver<Boolean> t = ToolPowerGuard.offRequest(
                slave(Observable.just(new FabPacketContent.GcodeResponse()), sent), "laser", "test").test();
        t.assertValue(true).assertComplete().assertNoErrors();
        Assert.assertEquals("M5", sent.get(0));
    }

    @Test
    public void linkDownIsFalseNotAnError() {
        List<String> sent = new ArrayList<>();
        TestObserver<Boolean> t = ToolPowerGuard.offRequest(
                slave(Observable.<FabPacketContent.GcodeResponse>error(new IOException("Serial port is not connected.")), sent),
                "spindle", "test").test();
        t.assertValue(false).assertComplete().assertNoErrors();
    }

    @Test
    public void noReplyTimesOutAfterTwoSeconds() {
        TestScheduler scheduler = new TestScheduler();
        RxJavaPlugins.setComputationSchedulerHandler(s -> scheduler);
        List<String> sent = new ArrayList<>();
        TestObserver<Boolean> t = ToolPowerGuard.offRequest(
                slave(Observable.<FabPacketContent.GcodeResponse>never(), sent), "spindle", "back").test();
        t.assertNoValues();
        scheduler.advanceTimeBy(ToolPowerGuard.OFF_TIMEOUT_S, TimeUnit.SECONDS);
        t.assertValue(false).assertComplete().assertNoErrors();
    }

    @Test
    public void switchOffSwallowsErrors() {
        List<String> sent = new ArrayList<>();
        ToolPowerGuard.switchOff(slave(Observable.<FabPacketContent.GcodeResponse>error(new IOException("x")), sent),
                "laser", "pause");
        Assert.assertEquals(1, sent.size());
    }
}
