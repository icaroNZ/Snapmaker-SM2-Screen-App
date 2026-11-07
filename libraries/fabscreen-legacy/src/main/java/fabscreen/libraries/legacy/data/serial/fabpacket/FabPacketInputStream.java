package fabscreen.libraries.legacy.data.serial.fabpacket;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * FabPacketInputStream
 * <p>
 * Read `FabPacket` from inputStream.
 * <p>
 * Inspired by the implementation of [BufferedInputStream](http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/tip/src/share/classes/java/io/BufferedInputStream.java)
 */
public class FabPacketInputStream implements Closeable {
    private final static String TAG = FabPacketInputStream.class.getSimpleName();

    private static int DEFAULT_BUFFER_SIZE = 8192;

    /**
     * Largest data length (event id + payload) accepted in a frame header. Same limit as the controller
     * (Snapmaker2-Controller snapmaker/src/common/protocol_sstp.h SSTP_INVALID_DATA_LENGTH = 1024).
     * A header outside 1..MAX_DATA_LENGTH is a false marker match: skip one byte and resync.
     */
    public static final int MAX_DATA_LENGTH = 1024;

    /** Number of false headers skipped since the stream was created (diagnostics). */
    private volatile long mResyncCount;

    private volatile byte buf[];

    // length of valid data in buf
    private int count;

    private int pos;

    private InputStream in;

    public FabPacketInputStream(InputStream in) {
        this(in, DEFAULT_BUFFER_SIZE);
    }

    public FabPacketInputStream(InputStream in, int size) {
        this.in = in;
        buf = new byte[size];
    }

    private byte[] getBufIfOpen() throws IOException {
        byte[] buffer = buf;
        if (buffer == null) {
            throw new IOException("Stream closed");
        }
        return buffer;
    }

    /**
     * Read up to {@code length} more bytes into the buffer, growing (and compacting) it first so the
     * bytes always fit. The grown array is stored in {@link #buf}; callers must re-read {@code buf}
     * after calling this (SCR-02: the old code grew only a local copy).
     */
    private int fill(int length) throws IOException {
        byte[] buffer = getBufIfOpen();
        if (count + length >= buffer.length) {
            int needed = (count - pos) + length + 1;
            int nsz = buffer.length;
            while (nsz < needed) {
                nsz *= 2;
            }
            byte[] nbuf = new byte[nsz];
            System.arraycopy(buffer, pos, nbuf, 0, count - pos);
            count -= pos;
            pos = 0;
            buf = nbuf;
            buffer = nbuf;
        }

        final int n = in.read(buffer, count, length);
        if (n > 0) {
            count += n;
        }
        return n;
    }

    private int fullfill(int length) throws IOException {
        int left = length;
        while (left > 0) {
            int f = fill(left);
            if (f == -1) {
                return -1;
            }
            left -= f;
        }
        return length;
    }

    public synchronized FabPacket readPacket() throws IOException {
        byte[] buffer = getBufIfOpen();

        while (true) {
            // Scan buffer to find packet marker
            while (pos + 1 < count) {
                if (buffer[pos] == (byte) 0xaa && buffer[pos + 1] == (byte) 0x55) {
                    break;
                }
                pos++;
            }

            // Throw away bytes before pos
            if (pos < count && pos >= count / 2) {
                System.arraycopy(buffer, pos, buffer, 0, count - pos);
                count -= pos;
                pos = 0;
            }

            // marker no found, fill 128 bytes and start over
            if (pos + 1 >= count) {
                int f = fill(128);
                buffer = getBufIfOpen();
                if (f == -1) {
                    return null;
                }
                continue;
            }

            if (pos + 8 > count) {
                int f = fullfill(pos + 8 - count);
                buffer = getBufIfOpen();
                if (f == -1) {
                    return null;
                }
            }

            // check length
            if ((buffer[pos + 2] ^ buffer[pos + 3]) == buffer[pos + 5]) {
                int length = (buffer[pos + 2] & 0xFF) * 0x100 + (buffer[pos + 3] & 0xFF);

                if (length == 0 || length > MAX_DATA_LENGTH) {
                    // False marker match (or corrupt header): resync from the next byte.
                    // Not logged here: this class is also used by host tests; SerialConnection
                    // reports getResyncCount() changes.
                    mResyncCount++;
                    pos++;
                    continue;
                }

                if (pos + 8 + length > count) {
                    int f = fullfill(pos + 8 + length - count);
                    buffer = getBufIfOpen();
                    if (f == -1) {
                        return null;
                    }
                }

                int checksum = (buffer[pos + 6] & 0xFF) * 0x100 + (buffer[pos + 7] & 0xFF);
                buffer[pos + 6] = buffer[pos + 7] = 0;
                int checksumCalculated = FabPacket.calculateChecksum(buffer, pos + 8, length);
                buffer[pos + 6] = (byte) ((checksum >> 8) & 0xFF);
                buffer[pos + 7] = (byte) (checksum & 0xFF);

                if (checksum == checksumCalculated) {
                    byte[] packet = new byte[8 + length];
                    System.arraycopy(buffer, pos, packet, 0, 8 + length);
                    pos += 8 + length;
                    return new FabPacket(packet);
                } else {
                    mResyncCount++;
                    pos++;
                }
            } else {
                pos++;
            }
        }
    }

    /** Number of headers rejected (bad length or bad checksum) since this stream was created. */
    public long getResyncCount() {
        return mResyncCount;
    }

    @Override
    public void close() throws IOException {
        if (in != null) {
            in.close();
        }
    }
}
