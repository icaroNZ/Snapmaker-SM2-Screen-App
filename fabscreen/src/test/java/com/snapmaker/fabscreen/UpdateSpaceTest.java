package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import fabscreen.libraries.legacy.lib.update.UpdateSpace;

/** SCR-11: update space planning proposes files, never the workspace, and never deletes by itself. */
public class UpdateSpaceTest {
    private static final long MB = 1024L * 1024;

    @Test
    public void requiredSpaceKeepsStockRule() {
        Assert.assertEquals(300 * MB, UpdateSpace.requiredBytes(5 * MB, true));
        Assert.assertEquals(300 * MB, UpdateSpace.requiredBytes(5 * MB, false));
        Assert.assertEquals(400 * MB, UpdateSpace.requiredBytes(200 * MB, true));
        Assert.assertEquals(600 * MB, UpdateSpace.requiredBytes(200 * MB, false));
    }

    @Test
    public void shortfallNeverNegative() {
        Assert.assertEquals(0, UpdateSpace.shortfall(300 * MB, 500 * MB));
        Assert.assertEquals(100 * MB, UpdateSpace.shortfall(300 * MB, 200 * MB));
    }

    @Test
    public void workspaceDotFilesAndDirectoriesAreNeverProposed() {
        Assert.assertFalse(UpdateSpace.isDeletable("workspace", false));
        Assert.assertFalse(UpdateSpace.isDeletable(".session.json", false));
        Assert.assertFalse(UpdateSpace.isDeletable("models", true));
        Assert.assertFalse(UpdateSpace.isDeletable("", false));
        Assert.assertFalse(UpdateSpace.isDeletable(null, false));
        Assert.assertTrue(UpdateSpace.isDeletable("benchy.gcode", false));
    }

    @Test
    public void oldestFilesCoverShortfall() {
        Assert.assertEquals(0, UpdateSpace.countOldestToFree(Arrays.asList(10L, 20L), 0));
        Assert.assertEquals(1, UpdateSpace.countOldestToFree(Arrays.asList(10L, 20L, 30L), 10));
        Assert.assertEquals(2, UpdateSpace.countOldestToFree(Arrays.asList(10L, 20L, 30L), 11));
        Assert.assertEquals(3, UpdateSpace.countOldestToFree(Arrays.asList(10L, 20L, 30L), 60));
        Assert.assertEquals(-1, UpdateSpace.countOldestToFree(Arrays.asList(10L, 20L, 30L), 61));
        Assert.assertEquals(-1, UpdateSpace.countOldestToFree(Collections.<Long>emptyList(), 1));
    }
}
