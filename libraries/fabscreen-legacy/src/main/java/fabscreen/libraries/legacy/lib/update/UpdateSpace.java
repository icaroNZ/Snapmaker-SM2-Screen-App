package fabscreen.libraries.legacy.lib.update;

import java.util.List;

/**
 * Space planning for a firmware update (SCR-11), pure Java so it can be unit-tested.
 * The app used to delete the oldest G-code/laser/CNC files silently when space was short; now it
 * only proposes which files would be removed and asks first.
 */
public final class UpdateSpace {
    public static final long MIN_REQUIRED_BYTES = 300L * 1024 * 1024;

    private UpdateSpace() {
    }

    /**
     * Free space the update needs: the bundle copied into the cache plus room for Android 7.1 to
     * install a screen APK (2x for a local bundle, 3x from USB, which is copied first), never
     * below 300 MB. Same rule as the stock code.
     */
    public static long requiredBytes(long bundleBytes, boolean local) {
        long factor = local ? 2 : 3;
        return Math.max(bundleBytes * factor, MIN_REQUIRED_BYTES);
    }

    /** Bytes still missing (0 when there is enough). */
    public static long shortfall(long requiredBytes, long availableBytes) {
        return Math.max(0, requiredBytes - availableBytes);
    }

    /**
     * Whether a stored file may be proposed for deletion: never dot files, directories or the
     * workspace (power-loss recovery copy of the current job).
     */
    public static boolean isDeletable(String name, boolean isDirectory) {
        if (name == null || name.isEmpty() || isDirectory) {
            return false;
        }
        return !name.startsWith(".") && !name.equals("workspace");
    }

    /**
     * How many of the files (sizes ordered oldest first) are needed to cover {@code shortfall}.
     * Returns -1 when even all of them are not enough.
     */
    public static int countOldestToFree(List<Long> sizesOldestFirst, long shortfall) {
        if (shortfall <= 0) {
            return 0;
        }
        long freed = 0;
        for (int i = 0; i < sizesOldestFirst.size(); i++) {
            freed += sizesOldestFirst.get(i);
            if (freed >= shortfall) {
                return i + 1;
            }
        }
        return -1;
    }
}
