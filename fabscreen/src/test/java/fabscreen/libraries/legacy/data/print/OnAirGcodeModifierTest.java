package fabscreen.libraries.legacy.data.print;

import org.junit.Assert;
import org.junit.Test;

/** SCR-15: the initial temperature override keeps the other arguments and survives batch splits. */
public class OnAirGcodeModifierTest {

    private static OnAirGcodeModifier modifier(float nozzle, float bed) {
        OnAirGcodeModifier m = new OnAirGcodeModifier();
        m.setOverrideInitialNozzleTemperature(nozzle);
        m.setOverrideInitialHeatedBedTemperature(bed);
        return m;
    }

    @Test
    public void m109KeepsCAndWArguments() {
        OnAirGcodeModifier m = modifier(215f, 60f);
        Assert.assertEquals("M109 S215.0 C2 W1", m.commitInitialOverride("M109 S260 C2 W1"));
    }

    @Test
    public void m104KeepsToolArgument() {
        OnAirGcodeModifier m = modifier(205f, 60f);
        Assert.assertEquals("M104 T1 S205.0", m.commitInitialOverride("M104 T1 S165"));
    }

    @Test
    public void m190KeepsRForm() {
        OnAirGcodeModifier m = modifier(205f, 65f);
        Assert.assertEquals("M190 R65.0", m.commitInitialOverride("M190 R60"));
        Assert.assertEquals("M140 S65.0", m.commitInitialOverride("M140 S60 ; bed"));
    }

    @Test
    public void missingTemperatureArgumentIsAppended() {
        OnAirGcodeModifier m = modifier(200f, 60f);
        Assert.assertEquals("M109 T0 S200.0", m.commitInitialOverride("M109 T0"));
    }

    @Test
    public void peekDoesNotConsumeTheMarker() {
        OnAirGcodeModifier m = modifier(200f, 60f);
        Assert.assertEquals("M109 S200.0", m.peekInitialOverride("M109 S210"));
        Assert.assertFalse(m.getInitialM109Marker());
        // Next batch: still rewritten, then consumed
        Assert.assertEquals("M109 S200.0", m.commitInitialOverride("M109 S210"));
        Assert.assertTrue(m.getInitialM109Marker());
        // Only the first occurrence is overridden
        Assert.assertEquals("M109 S210", m.commitInitialOverride("M109 S210"));
    }

    @Test
    public void unrelatedAndCommentLinesUnchanged() {
        OnAirGcodeModifier m = modifier(200f, 60f);
        Assert.assertEquals("G1 X1", m.peekInitialOverride("G1 X1"));
        Assert.assertEquals("", m.peekInitialOverride("; comment"));
    }

    @Test
    public void noOverrideLeavesLineAlone() {
        OnAirGcodeModifier m = modifier(-1f, -1f);
        Assert.assertEquals("M109 S260 C2 W1", m.commitInitialOverride("M109 S260 C2 W1"));
    }
}
