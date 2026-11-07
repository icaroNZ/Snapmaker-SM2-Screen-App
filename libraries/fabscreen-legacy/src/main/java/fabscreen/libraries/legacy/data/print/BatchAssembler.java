package fabscreen.libraries.legacy.data.print;

/**
 * Builds the G-code payload of one 0x13 batch (pure Java, no Android, unit-tested).
 * <p>
 * Rules the controller enforces (Snapmaker2-Controller snapmaker/src/hmi/event_handler.cpp):
 * the payload must hold exactly {@code end - start + 1} '\n'-terminated lines ("Lines number check
 * fail" otherwise, and it re-requests the same line every second for ever), must be at most
 * HMI_GCODE_PACK_SIZE (512) bytes, and an EMPTY payload means end of file (the job finishes).
 * So an empty payload may only be sent at a real end of file (SCR-03, SCR-07, SCR-10).
 */
public final class BatchAssembler {
    /** Maximum packet length (512) - event id - start line - end line, as BatchPrintController always used. */
    public static final int MAX_PAYLOAD_BYTES = 512 - 1 - 4 - 4;

    /** Where the lines come from (GcodePlayer in the app, a list in tests). */
    public interface LineSource {
        /** Current line, or null at end of file or after a read error. */
        String currentLine();

        /** Move to the next line. */
        void advance();

        /** Whether the last read failed (I/O error, missing file) rather than reaching end of file. */
        boolean hasReadError();
    }

    /**
     * Optional on-the-fly rewrite (initial temperature override). {@link #peek} must not change
     * state; {@link #commit} is called only once the rewritten line is really in the batch, so a
     * line that does not fit is rewritten again in the next batch (SCR-15).
     */
    public interface LineRewriter {
        String peek(String line);

        void commit(String line);
    }

    public enum Status {
        /** At least one line was added. */
        OK,
        /** No line left: a real end of file, the empty payload ends the job. */
        END_OF_FILE,
        /** The first line alone does not fit in a batch: do not send, pause the job. */
        LINE_TOO_LONG,
        /** The file could not be read: do not send, pause the job. */
        READ_ERROR
    }

    public static final class Result {
        public final String payload;
        public final int startLine;
        public final int endLine;
        public final int lineCount;
        public final Status status;
        /** Byte length of the first line that did not fit (for LINE_TOO_LONG diagnostics). */
        public final int rejectedLineBytes;

        Result(String payload, int startLine, int lineCount, Status status, int rejectedLineBytes) {
            this.payload = payload;
            this.startLine = startLine;
            this.endLine = startLine + lineCount - 1;
            this.lineCount = lineCount;
            this.status = status;
            this.rejectedLineBytes = rejectedLineBytes;
        }

        /** Whether this result may be sent to the controller. */
        public boolean isSendable() {
            return status == Status.OK || status == Status.END_OF_FILE;
        }
    }

    private BatchAssembler() {
    }

    /** Drop the comment text but keep the ';' (stock behaviour: the controller skips it). */
    public static String stripComment(String line) {
        int index = line.indexOf(';');
        return index == -1 ? line : line.substring(0, index + 1);
    }

    /**
     * Assemble a batch starting at the source's current line, which must be {@code startLine}.
     */
    public static Result assemble(LineSource source, int startLine, int maxBytes, LineRewriter rewriter) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int count = 0;
        Status status = Status.OK;
        int rejectedBytes = 0;

        while (true) {
            String raw = source.currentLine();
            if (raw == null) {
                if (count == 0) {
                    status = source.hasReadError() ? Status.READ_ERROR : Status.END_OF_FILE;
                }
                break;
            }

            String line = rewriter != null ? rewriter.peek(raw) : raw;
            line = stripComment(line) + "\n";
            int bytes = line.getBytes().length;
            if (used + bytes >= maxBytes) {
                if (count == 0) {
                    status = Status.LINE_TOO_LONG;
                    rejectedBytes = bytes;
                }
                break;
            }

            sb.append(line);
            used += bytes;
            count++;
            if (rewriter != null) {
                rewriter.commit(raw);
            }
            source.advance();
        }

        return new Result(sb.toString(), startLine, count, status, rejectedBytes);
    }

    /**
     * Rebuild the payload to resend from {@code lineNo} inside a stored batch that starts at
     * {@code batchStart}. Blank lines are kept (SCR-03: {@code String.split("\n")} dropped trailing
     * blank lines, so the line count no longer matched and the controller rejected the batch for ever).
     */
    public static String resendPayload(String stored, int batchStart, int lineNo) {
        String[] lines = stored.split("\n", -1);
        int n = lines.length;
        if (stored.endsWith("\n")) {
            n--; // split(-1) yields an empty element after the final '\n'
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lineNo - batchStart); i < n; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** Number of '\n' in a payload, which is what the controller counts. */
    public static int countLines(String payload) {
        int n = 0;
        for (int i = 0; i < payload.length(); i++) {
            if (payload.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
