package fabscreen.libraries.legacy.data.print;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * SCR-03 (resend keeps blank lines), SCR-07 (read error is not an end of file), SCR-10 (over-long
 * line never yields an empty batch) and SCR-15 (override survives a batch boundary).
 */
public class BatchAssemblerTest {

    /** In-memory line source; current line = lines[index], null past the end or after failAt. */
    static final class ListSource implements BatchAssembler.LineSource {
        private final List<String> lines;
        private final int failAt;
        private int index;
        private boolean error;

        ListSource(List<String> lines, int startIndex, int failAt) {
            this.lines = lines;
            this.index = startIndex;
            this.failAt = failAt;
            this.error = startIndex >= failAt;
        }

        @Override
        public String currentLine() {
            if (error || index >= lines.size()) {
                return null;
            }
            return lines.get(index);
        }

        @Override
        public void advance() {
            index++;
            if (index >= failAt) {
                error = true;
            }
        }

        @Override
        public boolean hasReadError() {
            return error;
        }
    }

    private static ListSource source(int start, String... lines) {
        return new ListSource(Arrays.asList(lines), start, Integer.MAX_VALUE);
    }

    @Test
    public void batchWithTrailingBlankLinesKeepsLineCountOnResend() {
        BatchAssembler.Result r = BatchAssembler.assemble(source(10, pad(10, "G1 X1", "G1 X2", "", "")),
                10, BatchAssembler.MAX_PAYLOAD_BYTES, null);
        Assert.assertEquals(BatchAssembler.Status.OK, r.status);
        Assert.assertEquals("G1 X1\nG1 X2\n\n\n", r.payload);
        Assert.assertEquals(10, r.startLine);
        Assert.assertEquals(13, r.endLine);
        Assert.assertEquals(r.endLine - r.startLine + 1, BatchAssembler.countLines(r.payload));

        // Controller asks again from line 11 (inside the kept batch).
        String resend = BatchAssembler.resendPayload(r.payload, r.startLine, 11);
        Assert.assertEquals("G1 X2\n\n\n", resend);
        Assert.assertEquals(r.endLine - 11 + 1, BatchAssembler.countLines(resend));

        // The old String.split("\n") dropped both trailing blank lines (1 line instead of 3).
        Assert.assertEquals(1, "G1 X2\n\n\n".split("\n").length);

        // Resend of only the blank tail is still non-empty (an empty payload ends the job).
        String tail = BatchAssembler.resendPayload(r.payload, r.startLine, 13);
        Assert.assertEquals("\n", tail);
        Assert.assertEquals(1, BatchAssembler.countLines(tail));
    }

    @Test
    public void leadingAndMiddleBlankLinesSurviveResend() {
        String stored = "\nG28\n\nG1 Z5\n";
        Assert.assertEquals(stored, BatchAssembler.resendPayload(stored, 0, 0));
        Assert.assertEquals("\nG1 Z5\n", BatchAssembler.resendPayload(stored, 0, 2));
    }

    @Test
    public void commentsAreStrippedButSemicolonKept() {
        BatchAssembler.Result r = BatchAssembler.assemble(source(0, "G1 X1 ; move", ";LAYER:0", "M105"),
                0, BatchAssembler.MAX_PAYLOAD_BYTES, null);
        Assert.assertEquals("G1 X1 ;\n;\nM105\n", r.payload);
        Assert.assertEquals(2, r.endLine);
    }

    @Test
    public void batchStopsBeforeByteLimit() {
        String line = repeat('X', 99); // 100 bytes with '\n'
        String[] lines = new String[20];
        Arrays.fill(lines, line);
        BatchAssembler.Result r = BatchAssembler.assemble(source(0, lines), 0, BatchAssembler.MAX_PAYLOAD_BYTES, null);
        Assert.assertEquals(BatchAssembler.Status.OK, r.status);
        Assert.assertEquals(5, r.lineCount); // 500 bytes < 503; a 6th line would reach 600
        Assert.assertTrue(r.payload.getBytes().length < BatchAssembler.MAX_PAYLOAD_BYTES);
        Assert.assertEquals(4, r.endLine);
    }

    @Test
    public void overLongFirstLineIsAnErrorNotAnEmptyBatch() {
        String longLine = "G1 " + repeat('9', 597); // 600 bytes
        BatchAssembler.Result r = BatchAssembler.assemble(source(0, longLine, "G1 X1"), 0,
                BatchAssembler.MAX_PAYLOAD_BYTES, null);
        Assert.assertEquals(BatchAssembler.Status.LINE_TOO_LONG, r.status);
        Assert.assertFalse(r.isSendable());
        Assert.assertEquals(0, r.lineCount);
        Assert.assertEquals(601, r.rejectedLineBytes);
    }

    @Test
    public void overLongLineAfterOthersEndsTheBatchNormally() {
        String longLine = "G1 " + repeat('9', 597);
        BatchAssembler.Result r = BatchAssembler.assemble(source(0, "G28", longLine), 0,
                BatchAssembler.MAX_PAYLOAD_BYTES, null);
        Assert.assertEquals(BatchAssembler.Status.OK, r.status);
        Assert.assertEquals("G28\n", r.payload);
        Assert.assertEquals(0, r.endLine);
    }

    @Test
    public void realEndOfFileGivesEmptySendablePayload() {
        BatchAssembler.Result r = BatchAssembler.assemble(source(2, "G28", "M84"), 2,
                BatchAssembler.MAX_PAYLOAD_BYTES, null);
        Assert.assertEquals(BatchAssembler.Status.END_OF_FILE, r.status);
        Assert.assertTrue(r.isSendable());
        Assert.assertEquals("", r.payload);
    }

    @Test
    public void readErrorIsNotAnEndOfFile() {
        ListSource failing = new ListSource(Arrays.asList("G28", "G1 X1", "G1 X2", "G1 X3"), 0, 2);
        BatchAssembler.Result first = BatchAssembler.assemble(failing, 0, BatchAssembler.MAX_PAYLOAD_BYTES, null);
        // Lines read before the failure are still valid and sent.
        Assert.assertEquals(BatchAssembler.Status.OK, first.status);
        Assert.assertEquals("G28\nG1 X1\n", first.payload);

        BatchAssembler.Result next = BatchAssembler.assemble(failing, 2, BatchAssembler.MAX_PAYLOAD_BYTES, null);
        Assert.assertEquals(BatchAssembler.Status.READ_ERROR, next.status);
        Assert.assertFalse(next.isSendable());
    }

    @Test
    public void rewriterCommitsOnlyLinesThatFit() {
        final int[] commits = {0};
        BatchAssembler.LineRewriter rw = new BatchAssembler.LineRewriter() {
            @Override
            public String peek(String line) {
                return line.startsWith("M109") ? "M109 S" + repeat('1', 450) : line;
            }

            @Override
            public void commit(String line) {
                if (line.startsWith("M109")) {
                    commits[0]++;
                }
            }
        };
        BatchAssembler.Result r = BatchAssembler.assemble(source(0, repeat('G', 99), "M109 S200"), 0,
                BatchAssembler.MAX_PAYLOAD_BYTES, rw);
        Assert.assertEquals(0, r.endLine);
        Assert.assertEquals(0, commits[0]); // did not fit: not consumed
    }

    private static String[] pad(int n, String... tail) {
        String[] out = new String[n + tail.length];
        for (int i = 0; i < n; i++) {
            out[i] = "G0";
        }
        System.arraycopy(tail, 0, out, n, tail.length);
        return out;
    }

    static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
