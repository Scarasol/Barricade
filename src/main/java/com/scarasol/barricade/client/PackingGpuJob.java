package com.scarasol.barricade.client;

import com.github.argon4w.acceleratedrendering.core.backends.programs.ComputeProgram;
import com.scarasol.barricade.pile.*;
import java.util.*;
import static org.lwjgl.opengl.GL43.*;

/** Finite-domain proposal, collision screen and apply stages, with persistent worker buffers. */
final class PackingGpuJob implements FieldGpuJob {
    private final FieldBatch batch;
    private final List<PackingBatch> plans;
    private final ComputeProgram evaluate, select;
    private final PackingBuffers buffers;
    private int phase, round, dispatches;
    private long fence;
    private double deviceMillis;

    PackingGpuJob(FieldBatch batch, ComputeProgram evaluate, ComputeProgram select, PackingBuffers buffers) {
        this.batch = batch; this.evaluate = evaluate; this.select = select; this.buffers = buffers;
        plans = batch instanceof PackingGroup group ? group.plans : List.of((PackingBatch) batch);
        buffers.upload(batch);
    }
    @Override public void submit() {
        if (fence != 0 || finished()) return;
        while (phase < 5) {
            int current = phase;
            if (plans.stream().anyMatch(plan -> plan.input[plan.input[16] + current * plan.input[7] * 2]
                    != plan.input[plan.input[16] + (current + 1) * plan.input[7] * 2 - 1])) break;
            phase++; round = 0;
        }
        if (finished()) return;
        glQueryCounter(buffers.start, GL_TIMESTAMP);
        for (int k = 0; k < 4 && phase < 5; k++) {
            evaluate.useProgram();
            glUniform1i(0, phase); glUniform1i(1, 0); glUniform1i(2, round);
            for (int i = 0; i < plans.size(); i++) {
                PackingBatch plan = plans.get(i); bind(i);
                int first = plan.input[plan.input[16] + phase * plan.input[7] * 2];
                int last = plan.input[plan.input[16] + (phase + 1) * plan.input[7] * 2 - 1];
                for (int base = first; base < last; base += 16_384) {
                    int count = Math.min(16_384, last - base);
                    glUniform1i(3, base); glUniform1i(4, count);
                    evaluate.dispatch((count + 63) / 64, 1, 1); dispatches++;
                }
            }
            evaluate.waitBarriers();
            select.useProgram(); glUniform1i(0, phase); glUniform1i(2, round);
            for (int stage = 1; stage <= 3; stage++) {
                glUniform1i(1, stage); glUniform1i(3, 0);
                for (int i = 0; i < plans.size(); i++) {
                    bind(i); int count = plans.get(i).input[7]; glUniform1i(4, count);
                    select.dispatch((count + 63) / 64, 1, 1); dispatches++;
                }
                select.waitBarriers();
            }
            if (++round == PackingBatch.ROUNDS[phase]) { phase++; round = 0; break; }
        }
        glQueryCounter(buffers.end, GL_TIMESTAMP);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0); glFlush();
    }
    private void bind(int index) {
        PackingBatch plan = plans.get(index);
        long inputOffset = batch instanceof PackingGroup ? batch.input[4 + index * 2] * 4L : 0;
        long stateOffset = batch instanceof PackingGroup ? batch.input[5 + index * 2] * 4L : 0;
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, buffers.input, inputOffset, plan.input.length * 4L);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, buffers.state, stateOffset, plan.initial.length * 4L);
    }
    @Override public boolean awaitBatch() {
        if (fence == 0) return true;
        int result = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000);
        if (result == GL_WAIT_FAILED) throw new IllegalStateException("Regional packing fence failed");
        if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) return false;
        deviceMillis += (glGetQueryObjecti64(buffers.end, GL_QUERY_RESULT)
                - glGetQueryObjecti64(buffers.start, GL_QUERY_RESULT)) / 1e6;
        glDeleteSync(fence); fence = 0; return true;
    }
    @Override public boolean finished() { return phase == 5 && fence == 0; }
    @Override public double deviceMillis() { return deviceMillis; }
    @Override public int dispatches() { return dispatches; }
    @Override public int[] read() {
        if (!finished()) throw new IllegalStateException("Regional packing is not complete");
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers.state);
        int[] output = new int[plans.stream().mapToInt(PackingBatch::resultWords).sum()];
        int destination = 0;
        for (int i = 0; i < plans.size(); i++) {
            PackingBatch plan = plans.get(i);
            int offset = batch instanceof PackingGroup ? batch.input[5 + i * 2] : 0;
            int[] poses = new int[plan.initial[6]];
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, offset * 4L, poses);
            System.arraycopy(poses, 0, output, destination, poses.length);
            output[destination + 12] = poses.length;
            int coverageOffset = plan.initial[12];
            int[] coverage = new int[plan.input[7]];
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, (offset + coverageOffset) * 4L, coverage);
            System.arraycopy(coverage, 0, output, destination + poses.length, coverage.length);
            destination += plan.resultWords();
        }
        return output;
    }
    @Override public void close() {
        if (fence != 0) glDeleteSync(fence);
    }
}
