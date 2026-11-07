package fabscreen.libraries.legacy.data.print;

import android.os.SystemClock;
import android.util.Log;

import com.orhanobut.logger.Logger;

import java.io.IOException;

import fabscreen.libraries.legacy.BaseApplication;
import fabscreen.libraries.legacy.lib.LogHelper;
import fabscreen.libraries.legacy.lib.file.IFile;
import fabscreen.libraries.legacy.lib.file.IFileManager;
import okio.BufferedSource;
import okio.Okio;

/**
 * G-code Player.
 * <p>
 * State Machine:
 * IDLE -> PLAYING <-> PAUSED
 * ^                     |
 * |_____________________|
 */
public class GcodePlayer implements BatchAssembler.LineSource {
    private static final String TAG = "GcodePlayer";

    private IFile mFile;
    private BufferedSource source;

    private String line;

    // SCR-07: set when the file could not be opened or read, so the batch builder pauses the job
    // instead of resending a stale line (line-count failure loop) or sending an early end of file.
    private boolean mReadError;

    private int sentCount;
    private int receivedCount;
    private int totalCount;

    private IFileManager mIFileManager;

    GcodePlayer() {
        reset();
    }

    public void reset() {
        totalCount = sentCount = receivedCount = 0;
        line = null;
        mReadError = false;

        if (source != null) {
            try {
                mIFileManager.deviceHang();
                source.close();
            } catch (IOException e) { /* ignore */ }
            source = null;
        }
    }

    void setGcodeFile(IFile file) {
        mFile = file;
    }

    private void openFile() {
        if (source != null) {
            try {
                mIFileManager.deviceHang();
                source.close();
            } catch (IOException e) { /* ignore */ }
            source = null;
        }

        mReadError = false;
        try {
            mIFileManager = mFile.isLocal() ?
                    BaseApplication.getInstance().getFabLocalFileManager() :
                    BaseApplication.getInstance().getFabUsbFileManager();
            source = Okio.buffer(Okio.source(mIFileManager.getInputStream(mFile)));
        } catch (Exception e) {
            mReadError = true;
            Logger.e("G-code open failed for %s (local=%b): %s",
                    mFile == null ? "null" : mFile.getName(), mFile != null && mFile.isLocal(), e);
            LogHelper.log(e);
        }
    }

    void setLineno(int startLineno) {
        // TODO: remove this test code
        final long startTime = SystemClock.elapsedRealtime();

        String line;
        int pos;

        // rewind
        // setGcodeFile(mFile);
        openFile();

        sentCount = receivedCount = startLineno;
        pos = 0;

        if (source == null) {
            // openFile() failed and logged it; mReadError is set.
            line = null;
            return;
        }

        // run to start point
        while (pos < startLineno) {
            try {
                line = source.readUtf8Line();
            } catch (IOException e) {
                mReadError = true;
                this.line = null;
                Logger.e("G-code read failed at line %d of %s while seeking to %d: %s",
                        pos, mFile.getName(), startLineno, e);
                LogHelper.log(e);
                return;
            }

            if (line == null) {
                break;
            }

            pos++;
        }
        long took = SystemClock.elapsedRealtime() - startTime;
        if (took > 1000) {
            // SCR-17: this re-scan runs on the main thread; long ones risk an ANR.
            Logger.w("set line number %d of %s takes %d ms (file %d bytes)", startLineno,
                    mFile == null ? "null" : mFile.getName(), took, mFile == null ? 0 : mFile.length());
        } else {
            Logger.i(String.format("set line number takes %s ms", took));
        }
    }

    void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    int getTotalCount() {
        return totalCount;
    }

    /**
     * Get next line.
     */
    String nextLine() {
        if (source == null || mReadError) {
            // Do not count lines that were never read; keep reporting the error.
            mReadError = true;
            line = null;
            return null;
        }
        try {
            line = source.readUtf8Line();
        } catch (IOException e) {
            mReadError = true;
            line = null;
            Logger.e("G-code read failed at line %d of %s (local=%b): %s",
                    sentCount, mFile == null ? "null" : mFile.getName(), mFile != null && mFile.isLocal(), e);
            LogHelper.log(e);
            return null;
        }

        sentCount++;
        if (sentCount % 1000 == 0) {
            Logger.i(String.format("line %s: %s", sentCount, line));
        }
        return line;
    }

    /**
     * Get current line.
     */
    String getLine() {
        return line;
    }

    /** Whether opening or reading the file failed (SCR-07). */
    public boolean hasReadError() {
        return mReadError;
    }

    @Override
    public String currentLine() {
        return line;
    }

    @Override
    public void advance() {
        nextLine();
    }

    /**
     * Get current line number (start from 0).
     *
     * @return int the line number
     */
    int getLineNo() {
        return sentCount - 1;
    }

    void onAck() {
        receivedCount++;
    }

    void skipLine() {
        receivedCount++;
    }

    public float getProgress() {
        if (totalCount == 0) return 0;
        int nowCount = getProgressCount();
        return 1.f * nowCount / totalCount;
    }

    int getSentCount() {
        return sentCount;
    }

    int getReceivedCount() {
        return receivedCount;
    }

    int getProgressCount() {
        // In batch mode, receivedCount will not be modified
        // in single mode, sentCount may be greater than receivedCount
        // GcodePlayer takes the larger number and does not need to determine the mode
        return Math.max(receivedCount, sentCount - 1);
    }
}
