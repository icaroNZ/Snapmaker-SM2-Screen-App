package fabscreen.libraries.legacy.data.serial;

import com.orhanobut.logger.Logger;

import java.util.LinkedList;
import java.util.Queue;

import io.reactivex.ObservableEmitter;

/**
 * Queue of emitters waiting for replies with one key. Methods are synchronized (SCR-18): requests
 * are added from the main/computation threads while replies arrive on the serial reader thread.
 */
public class RequestReceiver {
    private int key;
    private Queue<ObservableEmitter<Object>> mEmitterQueue = new LinkedList<>();
    private ObservableEmitter<Object> mDefaultEmitter;

    public RequestReceiver(int key) {
        this.key = key;
    }

    public synchronized int size() {
        return mEmitterQueue.size();
    }

    public synchronized void setDefaultEmitter(ObservableEmitter<Object> emitter) {
        mDefaultEmitter = emitter;
    }

    public synchronized void addEmitter(ObservableEmitter<Object> emitter) {
        mEmitterQueue.add(emitter);
    }

    public synchronized void receive(Object data) {
        if (mEmitterQueue.isEmpty()) {
            if (mDefaultEmitter != null) {
                mDefaultEmitter.onNext(data);
            }
        } else {
            while (true) {
                if (mEmitterQueue.size() == 0) {
                    // Unexpected empty queue
                    Logger.e("emitter queue #%d (%s) is empty.", key, mEmitterQueue.hashCode());
                    break;
                }

                ObservableEmitter<Object> emitter = mEmitterQueue.remove();

                if (emitter.isDisposed()) {
                    // The command is being canceled, discard this emitter and seek for
                    // next not disposed emitter.
                    Logger.d("Found emitter disposed, skip.");
                    continue;
                }

                emitter.onNext(data);
                emitter.onComplete();
                break;
            }
        }
    }

    public synchronized void error(Throwable error) {
        if (mEmitterQueue.isEmpty()) {
            if (mDefaultEmitter != null) {
                mDefaultEmitter.onError(error);
            }
        } else {
            // SCR-14: skip requests that were already cancelled (timeout/dispose), as receive() does.
            while (!mEmitterQueue.isEmpty()) {
                ObservableEmitter<Object> emitter = mEmitterQueue.remove();
                if (emitter.isDisposed()) {
                    Logger.d("Found emitter disposed, skip.");
                    continue;
                }
                emitter.tryOnError(error);
                break;
            }
        }
    }

    /**
     * SCR-14: fail every queued request (not the default watcher). Called when the serial link is
     * lost: the pending requests were dropped silently, so untimed requests (levelling, Z offset)
     * waited for ever and locked their page.
     *
     * @return the number of live requests that were failed
     */
    public synchronized int errorAll(Throwable error) {
        int failed = 0;
        while (!mEmitterQueue.isEmpty()) {
            ObservableEmitter<Object> emitter = mEmitterQueue.remove();
            if (emitter.isDisposed()) {
                continue;
            }
            emitter.tryOnError(error);
            failed++;
        }
        return failed;
    }

    public int getKey() {
        return key;
    }

    public synchronized void complete() {
        if (mEmitterQueue.isEmpty()) {
            if (mDefaultEmitter != null) {
                mDefaultEmitter.onComplete();
            }
        } else {
            while (true) {
                if (mEmitterQueue.size() == 0) {
                    // Unexpected empty queue
                    Logger.e("emitter queue #%d (%s) is empty.", key, mEmitterQueue.hashCode());
                    break;
                }

                ObservableEmitter<Object> emitter = mEmitterQueue.remove();

                if (emitter.isDisposed()) {
                    // The command is being canceled, discard this emitter and seek for
                    // next not disposed emitter.
                    Logger.d("Found emitter disposed, skip.");
                    continue;
                }

                emitter.onComplete();
                break;
            }
        }
    }
}
