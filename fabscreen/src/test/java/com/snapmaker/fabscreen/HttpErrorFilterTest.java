package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import fabscreen.libraries.legacy.lib.fabserver.HttpErrorFilter;

/** SCR-20: HTTP server errors are logged, except routine idle-connection noise. */
public class HttpErrorFilterTest {
    static class ConnectionClosedException extends IOException {
    }

    @Test
    public void routineSocketNoiseIsFiltered() {
        Assert.assertTrue(HttpErrorFilter.isRoutine(new SocketTimeoutException("Read timed out")));
        Assert.assertTrue(HttpErrorFilter.isRoutine(new SocketException("Connection reset")));
        Assert.assertTrue(HttpErrorFilter.isRoutine(new ConnectionClosedException()));
        Assert.assertTrue(HttpErrorFilter.isRoutine(null));
    }

    @Test
    public void realErrorsAreLogged() {
        Assert.assertFalse(HttpErrorFilter.isRoutine(new IOException("disk full")));
        Assert.assertFalse(HttpErrorFilter.isRoutine(new IllegalStateException("bug")));
    }
}
