package com.scarasol.barricade.client;

import com.github.argon4w.acceleratedrendering.core.backends.programs.ComputeProgram;
import com.scarasol.barricade.pile.ArchivedBatchLayout;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL43.*;

/** GPU-worker GL resources. Readback only after the whole region pass has finished. */
final class ArchivedLayoutGpuJob implements AutoCloseable {
    private static final int MAX_EVALUATION_BATCH = 65_536;
    private static final int MAX_TIMED_ROUNDS = 8;
    private final ArchivedBatchLayout layout;
    private final ComputeProgram evaluateProgram, selectionProgram;
    private final int evaluationBatchSize, ownerBatchSize;
    private final int input = glGenBuffers(), state = glGenBuffers();
    private final int timerStart = glGenQueries(), timerMiddle = glGenQueries(), timerEnd = glGenQueries();
    private final int[] proposalStart = new int[MAX_TIMED_ROUNDS], proposalEnd = new int[MAX_TIMED_ROUNDS];
    private final int[] screenStart = new int[MAX_TIMED_ROUNDS], screenEnd = new int[MAX_TIMED_ROUNDS];
    private final int[] applyStart = new int[MAX_TIMED_ROUNDS], applyEnd = new int[MAX_TIMED_ROUNDS];
    private int phase, round;
    private int submittedPhase, submittedRounds;
    private int timedRounds;
    private long fence;
    private double batchMillis;
    private double selectionApplyMillis;
    private double deviceMillis;
    private double deviceSelectionApplyMillis;
    private double proposalMillis, screenMillis, applyMillis;
    private double deviceProposalMillis, deviceScreenMillis, deviceApplyMillis;

    ArchivedLayoutGpuJob(ArchivedBatchLayout layout, ComputeProgram evaluateProgram, ComputeProgram selectionProgram) {
        this(layout, evaluateProgram, selectionProgram, MAX_EVALUATION_BATCH, Integer.MAX_VALUE);
    }
    ArchivedLayoutGpuJob(ArchivedBatchLayout layout, ComputeProgram evaluateProgram, ComputeProgram selectionProgram,
                 int evaluationBatchSize, int ownerBatchSize) {
        this.layout = layout; this.evaluateProgram = evaluateProgram; this.selectionProgram = selectionProgram;
        if (evaluationBatchSize < 64 || ownerBatchSize < 1) throw new IllegalArgumentException("Invalid dispatch batch size");
        this.evaluationBatchSize = evaluationBatchSize; this.ownerBatchSize = ownerBatchSize;
        for (int i = 0; i < MAX_TIMED_ROUNDS; i++) {
            proposalStart[i] = glGenQueries(); proposalEnd[i] = glGenQueries();
            screenStart[i] = glGenQueries(); screenEnd[i] = glGenQueries();
            applyStart[i] = glGenQueries(); applyEnd[i] = glGenQueries();
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, input);
        glBufferData(GL_SHADER_STORAGE_BUFFER, layout.input, GL_STATIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, state);
        glBufferData(GL_SHADER_STORAGE_BUFFER, layout.initial, GL_DYNAMIC_READ);
    }
    void submit(int rounds) {
        if (fence != 0) return;
        while (phase < 5) {
            int ranges = layout.input[16], cells = layout.input[7];
            boolean empty = layout.input[ranges + phase * cells * 2]
                    == layout.input[ranges + (phase + 1) * cells * 2 - 1];
            boolean quota = layout.initial[layout.initial[16] + phase]
                    >= layout.input[layout.input[15] + phase];
            if (!empty && !quota) break;
            phase++; round = 0;
        }
        if (phase == 5) { batchMillis = 0; selectionApplyMillis = 0; submittedRounds = 0; return; }
        if (rounds > MAX_TIMED_ROUNDS) throw new IllegalArgumentException("Too many layout rounds per submission");
        submittedPhase = phase; submittedRounds = 0;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, input);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, state);
        glQueryCounter(timerStart, GL_TIMESTAMP);
        timedRounds = 0;
        for (int i = 0; i < rounds && phase < 5; i++) {
            evaluateProgram.useProgram();
            glUniform1i(0, phase); glUniform1i(2, round);
            glUniform1i(1, 0);
            int ranges = layout.input[16], cells = layout.input[7];
            int first = layout.input[ranges + phase * cells * 2];
            int last = layout.input[ranges + (phase + 1) * cells * 2 - 1];
            for (int base = first; base < last; base += evaluationBatchSize) {
                glUniform1i(3, base);
                evaluateProgram.dispatch((Math.min(evaluationBatchSize, last - base) + 63) / 64, 1, 1);
            }
            evaluateProgram.waitBarriers();
            glQueryCounter(timerMiddle, GL_TIMESTAMP);
            selectionProgram.useProgram();
            glUniform1i(0, phase); glUniform1i(2, round);
            int query = timedRounds++;
            glQueryCounter(proposalStart[query], GL_TIMESTAMP);
            for (int stage = 1; stage <= 2; stage++) {
                if (stage == 2) glQueryCounter(proposalEnd[query], GL_TIMESTAMP);
                if (stage == 2) glQueryCounter(screenStart[query], GL_TIMESTAMP);
                glUniform1i(1, stage);
                for (int base = 0; base < layout.input[7]; base += ownerBatchSize) {
                    int count = Math.min(ownerBatchSize, layout.input[7] - base);
                    glUniform1i(3, base); glUniform1i(4, count);
                    selectionProgram.dispatch((count + 63) / 64, 1, 1);
                }
                selectionProgram.waitBarriers();
            }
            glQueryCounter(screenEnd[query], GL_TIMESTAMP);
            glQueryCounter(applyStart[query], GL_TIMESTAMP);
            glUniform1i(1, 3);
            for (int base = 0; base < layout.input[7]; base += ownerBatchSize) {
                int count = Math.min(ownerBatchSize, layout.input[7] - base);
                glUniform1i(3, base); glUniform1i(4, count);
                selectionProgram.dispatch((count + 63) / 64, 1, 1);
            }
            selectionProgram.waitBarriers();
            glQueryCounter(applyEnd[query], GL_TIMESTAMP);
            submittedRounds++;
            if (++round == ArchivedBatchLayout.ROUNDS[phase]) { phase++; round = 0; break; }
        }
        glQueryCounter(timerEnd, GL_TIMESTAMP);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();
    }
    boolean poll() {
        return waitForBatch(0);
    }
    boolean awaitBatch() {
        return waitForBatch(1_000_000);
    }
    private boolean waitForBatch(long timeout) {
        if (fence == 0) return true;
        int status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, timeout);
        if (status == GL_WAIT_FAILED) throw new IllegalStateException("GPU layout fence failed");
        if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) return false;
        long end = glGetQueryObjecti64(timerEnd, GL_QUERY_RESULT);
        batchMillis = (end - glGetQueryObjecti64(timerStart, GL_QUERY_RESULT)) / 1_000_000.0;
        selectionApplyMillis = (end - glGetQueryObjecti64(timerMiddle, GL_QUERY_RESULT)) / 1_000_000.0;
        proposalMillis = 0; screenMillis = 0; applyMillis = 0;
        for (int i = 0; i < timedRounds; i++) {
            proposalMillis += (glGetQueryObjecti64(proposalEnd[i], GL_QUERY_RESULT)
                    - glGetQueryObjecti64(proposalStart[i], GL_QUERY_RESULT)) / 1_000_000.0;
            screenMillis += (glGetQueryObjecti64(screenEnd[i], GL_QUERY_RESULT)
                    - glGetQueryObjecti64(screenStart[i], GL_QUERY_RESULT)) / 1_000_000.0;
            applyMillis += (glGetQueryObjecti64(applyEnd[i], GL_QUERY_RESULT)
                    - glGetQueryObjecti64(applyStart[i], GL_QUERY_RESULT)) / 1_000_000.0;
        }
        deviceMillis += batchMillis;
        deviceSelectionApplyMillis += selectionApplyMillis;
        deviceProposalMillis += proposalMillis; deviceScreenMillis += screenMillis; deviceApplyMillis += applyMillis;
        glDeleteSync(fence); fence = 0;
        return true;
    }
    double batchMillis() { return batchMillis; }
    double selectionApplyMillis() { return selectionApplyMillis; }
    double deviceMillis() { return deviceMillis; }
    double deviceSelectionApplyMillis() { return deviceSelectionApplyMillis; }
    double deviceProposalMillis() { return deviceProposalMillis; }
    double deviceScreenMillis() { return deviceScreenMillis; }
    double deviceApplyMillis() { return deviceApplyMillis; }
    int nextPhase() { return phase; }
    int submittedPhase() { return submittedPhase; }
    int submittedRounds() { return submittedRounds; }
    boolean finished() { return phase == 5 && fence == 0; }
    int[] read() {
        if (!finished()) throw new IllegalStateException("GPU layout is still running");
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, state);
        int[] header = new int[ArchivedBatchLayout.HEADER];
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, header);
        if (header[3] != 0) throw new ArchivedBatchLayout.CapacityException("GPU occupancy capacity exceeded");
        if (header[0] < 0 || header[0] > layout.candidates.size())
            throw new IllegalStateException("GPU layout returned invalid output");
        int recordCapacity = 0;
        for (int value : ArchivedBatchLayout.ROUNDS) recordCapacity += value * layout.input[7];
        int recordWords = recordCapacity * 4;
        IntBuffer buffer = BufferUtils.createIntBuffer(recordWords);
        if (recordWords > 0) glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) header[5] * 4, buffer);
        int coveredOffset = ArchivedBatchLayout.HEADER + recordWords;
        int[] output = new int[coveredOffset + layout.input[7]];
        System.arraycopy(header, 0, output, 0, header.length);
        output[5] = ArchivedBatchLayout.HEADER;
        buffer.get(output, ArchivedBatchLayout.HEADER, recordWords);
        int[] covered = new int[layout.input[7]];
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) header[12] * 4, covered);
        output[12] = coveredOffset;
        System.arraycopy(covered, 0, output, coveredOffset, covered.length);
        return output;
    }
    @Override public void close() {
        if (fence != 0) glDeleteSync(fence);
        glDeleteQueries(timerStart); glDeleteQueries(timerMiddle); glDeleteQueries(timerEnd);
        for (int i = 0; i < MAX_TIMED_ROUNDS; i++) {
            glDeleteQueries(proposalStart[i]); glDeleteQueries(proposalEnd[i]);
            glDeleteQueries(screenStart[i]); glDeleteQueries(screenEnd[i]);
            glDeleteQueries(applyStart[i]); glDeleteQueries(applyEnd[i]);
        }
        glDeleteBuffers(input); glDeleteBuffers(state);
    }
}
