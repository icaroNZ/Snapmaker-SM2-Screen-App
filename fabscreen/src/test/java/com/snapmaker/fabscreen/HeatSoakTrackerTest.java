package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import fabscreen.libraries.legacy.data.calibration.HeatSoakTracker;

public class HeatSoakTrackerTest {

    @Test
    public void noHeatingIsDone() {
        HeatSoakTracker t = new HeatSoakTracker(0, 300);
        Assert.assertFalse(t.isActive());
        Assert.assertEquals(HeatSoakTracker.PHASE_DONE, t.getPhase());
    }

    @Test
    public void heatSoakWaitThenDone() {
        HeatSoakTracker t = new HeatSoakTracker(60, 120);
        Assert.assertEquals(HeatSoakTracker.PHASE_HEATING, t.getPhase());
        Assert.assertFalse(t.update(25, 0));
        Assert.assertEquals(120, t.getSecondsLeft(0));
        Assert.assertFalse(t.update(58, 10_000));
        Assert.assertEquals(HeatSoakTracker.PHASE_HEATING, t.getPhase());

        // within 1 degree: soak countdown starts
        Assert.assertTrue(t.update(59, 20_000));
        Assert.assertEquals(HeatSoakTracker.PHASE_SOAKING, t.getPhase());
        Assert.assertEquals(120, t.getSecondsLeft(20_000));
        Assert.assertEquals(119, t.getSecondsLeft(21_000));
        Assert.assertEquals(60, t.getSecondsLeft(80_000));
        Assert.assertEquals(1, t.getSecondsLeft(139_500));

        // temperature dips do not restart the countdown
        Assert.assertFalse(t.update(57, 100_000));
        Assert.assertEquals(HeatSoakTracker.PHASE_SOAKING, t.getPhase());

        Assert.assertTrue(t.update(60, 140_000));
        Assert.assertEquals(HeatSoakTracker.PHASE_WAITING, t.getPhase());
        Assert.assertEquals(0, t.getSecondsLeft(150_000));
        Assert.assertTrue(t.isActive());

        t.finish();
        Assert.assertFalse(t.isActive());
    }

    @Test
    public void zeroSoakGoesStraightToWaiting() {
        HeatSoakTracker t = new HeatSoakTracker(60, 0);
        Assert.assertTrue(t.update(60, 5_000));
        Assert.assertEquals(HeatSoakTracker.PHASE_WAITING, t.getPhase());
    }

    @Test
    public void hotterBedMustCoolFirst() {
        HeatSoakTracker t = new HeatSoakTracker(60, 60);
        Assert.assertFalse(t.update(85, 0));
        Assert.assertEquals(HeatSoakTracker.PHASE_HEATING, t.getPhase());
        Assert.assertTrue(t.update(61, 30_000));
        Assert.assertEquals(HeatSoakTracker.PHASE_SOAKING, t.getPhase());
    }

    @Test
    public void probingStartEndsTracking() {
        HeatSoakTracker t = new HeatSoakTracker(60, 600);
        t.update(30, 0);
        t.finish();
        Assert.assertEquals(HeatSoakTracker.PHASE_DONE, t.getPhase());
        Assert.assertFalse(t.update(60, 1_000));
        Assert.assertEquals(0, t.getSecondsLeft(1_000));
    }
}
