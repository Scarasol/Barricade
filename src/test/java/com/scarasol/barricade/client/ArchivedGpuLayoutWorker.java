package com.scarasol.barricade.client;

import com.github.argon4w.acceleratedrendering.core.backends.programs.*;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.ArchivedBatchLayout;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;

/** Owns an independent hidden context; AR supplies the compute program/shader API. */
final class ArchivedGpuLayoutWorker {
    private final long window;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Barricade GPU layout");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<CompletableFuture<int[]>> requests = ConcurrentHashMap.newKeySet();
    private final AtomicLong revision = new AtomicLong(), completed = new AtomicLong();
    private final AtomicLong deviceNanos = new AtomicLong(), selectionApplyNanos = new AtomicLong();
    private final AtomicLong proposalNanos = new AtomicLong(), screenNanos = new AtomicLong(), applyNanos = new AtomicLong();
    private volatile String source;
    private volatile boolean failed, stopping, ready;
    private String compiled = "";
    private ComputeProgram evaluateProgram, selectionProgram;
    private CompletableFuture<Void> stopped;

    ArchivedGpuLayoutWorker(long window, String source) {
        this.window = window; this.source = source;
        executor.execute(() -> {
            try {
                long start = System.nanoTime();
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                if (!GL.getCapabilities().OpenGL43 || !GL.getCapabilities().GL_ARB_gpu_shader_int64)
                    throw new IllegalStateException("Compute shaders / GPU int64 unavailable");
                Barricade.LOGGER.info("Barricade layout GPU: {}", glGetString(GL_RENDERER));
                compile();
                ready = compiled.equals(this.source);
                Barricade.LOGGER.info("GPU layout environment initialized in {} ms", (System.nanoTime() - start) / 1_000_000.0);
            } catch (RuntimeException | LinkageError exception) {
                failed = true;
                Barricade.LOGGER.error("Could not initialize layout GPU context", exception);
            }
        });
    }
    boolean failed() { return failed; }
    boolean ready() { return ready && !failed; }
    long completed() { return completed.get(); }
    long deviceNanos() { return deviceNanos.get(); }
    long selectionApplyNanos() { return selectionApplyNanos.get(); }
    long proposalNanos() { return proposalNanos.get(); }
    long screenNanos() { return screenNanos.get(); }
    long applyNanos() { return applyNanos.get(); }
    void reload(String value) {
        source = value; cancel(); ready = false;
        if (stopping || failed || value.isBlank()) return;
        long ticket = revision.get();
        executor.execute(() -> {
            try {
                if (ticket != revision.get() || stopping) return;
                compile();
                ready = compiled.equals(source) && ticket == revision.get();
            } catch (RuntimeException | LinkageError exception) {
                failed = true;
                Barricade.LOGGER.error("Could not reload the required GPU layout shader", exception);
            }
        });
    }
    void cancel() {
        revision.incrementAndGet();
        requests.forEach(request -> request.completeExceptionally(new CancellationException("GPU inputs changed")));
    }
    CompletableFuture<int[]> submit(ArchivedBatchLayout plan) {
        CompletableFuture<int[]> result = new CompletableFuture<>();
        if (stopping || failed) return CompletableFuture.failedFuture(new IllegalStateException("GPU worker stopped"));
        long ticket = revision.get();
        requests.add(result);
        executor.execute(() -> {
            try {
                if (result.isDone() || ticket != revision.get())
                    throw new CancellationException("GPU inputs changed before submission");
                if (failed) throw new IllegalStateException("GPU context unavailable");
                compile();
                long limit = glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
                if ((long) plan.input.length * 4 > limit || (long) plan.initial.length * 4 > limit)
                    throw new IllegalArgumentException("Region exceeds GPU SSBO limit");
                long start = System.nanoTime();
                try (ArchivedLayoutGpuJob job = new ArchivedLayoutGpuJob(plan, evaluateProgram, selectionProgram)) {
                    int rounds;
                    while (!job.finished()) {
                        if (result.isDone() || ticket != revision.get() || stopping)
                            throw new CancellationException("GPU layout cancelled");
                        if (!job.awaitBatch()) continue;
                        if (job.finished()) break;
                        double ms = job.batchMillis();
                        rounds = job.nextPhase() != job.submittedPhase() || ms == 0 ? 1
                                : Math.max(1, Math.min(4, (int) (job.submittedRounds() * 2.0 / Math.max(.05, ms))));
                        job.submit(rounds);
                    }
                    int[] output = job.read();
                    int error = glGetError();
                    if (error != GL_NO_ERROR) throw new IllegalStateException("Layout GL error: " + error);
                    deviceNanos.addAndGet(Math.round(job.deviceMillis() * 1_000_000));
                    selectionApplyNanos.addAndGet(Math.round(job.deviceSelectionApplyMillis() * 1_000_000));
                    proposalNanos.addAndGet(Math.round(job.deviceProposalMillis() * 1_000_000));
                    screenNanos.addAndGet(Math.round(job.deviceScreenMillis() * 1_000_000));
                    applyNanos.addAndGet(Math.round(job.deviceApplyMillis() * 1_000_000));
                    if (result.complete(output)) {
                        completed.incrementAndGet();
                        Barricade.LOGGER.debug(
                                "GPU layout: wall={} ms, device={} ms (proposal={} ms, conflict screen={} ms, apply={} ms), {} candidates, {} new objects, {} MiB buffers",
                                (System.nanoTime() - start) / 1_000_000.0, job.deviceMillis(), job.deviceProposalMillis(),
                                job.deviceScreenMillis(), job.deviceApplyMillis(),
                                plan.candidates.size(), output[0], (plan.input.length + (long) plan.initial.length) * 4 / 1048576);
                    }
                }
            } catch (CancellationException exception) {
                result.completeExceptionally(exception);
            } catch (IllegalArgumentException exception) {
                result.completeExceptionally(exception);
            } catch (RuntimeException | LinkageError exception) {
                failed = true;
                result.completeExceptionally(exception);
                Barricade.LOGGER.error("GPU layout worker disabled for this session", exception);
            } finally {
                requests.remove(result);
            }
        });
        return result;
    }
    private void compile() {
        String next = source;
        if (evaluateProgram != null && compiled.equals(next)) return;
        ComputeProgram evaluate = compileStage(next, 0), selection;
        try { selection = compileStage(next, 1); }
        catch (RuntimeException exception) { evaluate.delete(); throw exception; }
        glUseProgram(0);
        if (evaluateProgram != null) evaluateProgram.delete();
        if (selectionProgram != null) selectionProgram.delete();
        evaluateProgram = evaluate; selectionProgram = selection; compiled = next;
    }
    private static ComputeProgram compileStage(String text, int stage) {
        int versionEnd = text.indexOf('\n');
        if (versionEnd < 0) throw new IllegalArgumentException("Layout shader is missing its version line");
        String specialized = text.substring(0, versionEnd + 1) + "#define BARRICADE_STAGE " + stage + "\n"
                + text.substring(versionEnd + 1);
        ComputeShader shader = new ComputeShader();
        ComputeProgram replacement = new ComputeProgram(BarrierFlags.getFlags(BarrierFlags.SHADER_STORAGE));
        try {
            shader.setShaderSource(specialized); shader.compileShader();
            if (!shader.isCompiled()) throw new IllegalStateException(shader.getInfoLog());
            replacement.attachShader(shader); replacement.linkProgram();
            if (!replacement.isLinked()) throw new IllegalStateException(replacement.getInfoLog());
        } catch (RuntimeException exception) {
            replacement.delete();
            throw exception;
        } finally { shader.delete(); }
        return replacement;
    }
    synchronized CompletableFuture<Void> stop() {
        if (stopped != null) return stopped;
        stopping = true; cancel();
        stopped = new CompletableFuture<>();
        executor.execute(() -> {
            RuntimeException failure = null;
            try {
                glUseProgram(0);
                if (evaluateProgram != null) evaluateProgram.delete();
                if (selectionProgram != null) selectionProgram.delete();
            } catch (RuntimeException exception) { failure = exception; }
            finally {
                GL.setCapabilities(null);
                glfwMakeContextCurrent(0);
            }
            if (failure == null) stopped.complete(null);
            else stopped.completeExceptionally(failure);
        });
        executor.shutdown();
        return stopped;
    }
}
