package com.scarasol.barricade.client;

import com.github.argon4w.acceleratedrendering.core.backends.programs.*;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;

/** Owns the current regional packing stages and their shared GL context. */
final class GpuLayoutWorker {
    private final long window;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Barricade spatial field GPU"); thread.setDaemon(true); return thread;
    });
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
    private final AtomicLong revision = new AtomicLong(), completed = new AtomicLong(), deviceNanos = new AtomicLong();
    private final AtomicLong dispatches = new AtomicLong();
    private final AtomicLong allocatedBytes = new AtomicLong();
    private volatile String source;
    private volatile boolean failed, stopping, ready;
    private String compiled = "";
    private ComputeProgram evaluate, select;
    private PackingBuffers buffers;
    private CompletableFuture<Void> stopped;
    private volatile Thread owner;
    private CompletableFuture<?> activePreparation;
    private long preparationRevision;
    GpuLayoutWorker(long window, String source) {
        this.window = window; this.source = source;
        executor.execute(() -> {
            try {
                owner = Thread.currentThread();
                glfwMakeContextCurrent(window); GL.createCapabilities();
                if (!GL.getCapabilities().OpenGL43 || !GL.getCapabilities().GL_ARB_gpu_shader_int64)
                    throw new IllegalStateException("OpenGL 4.3 and GPU int64 required");
                Barricade.LOGGER.info("Barricade spatial field GPU: {}", glGetString(GL_RENDERER));
                compile(); buffers = new PackingBuffers(); ready = true;
            } catch (RuntimeException | LinkageError exception) {
                failed = true; Barricade.LOGGER.error("Could not initialize required spatial field GPU", exception);
            }
        });
    }
    boolean failed() { return failed; }
    boolean ready() { return ready && !failed && !stopping; }
    long completed() { return completed.get(); }
    long deviceNanos() { return deviceNanos.get(); }
    long dispatches() { return dispatches.get(); }
    long allocatedBytes() { return allocatedBytes.get(); }
    void cancel() {
        revision.incrementAndGet();
        requests.forEach(request -> request.completeExceptionally(new CancellationException("Field inputs changed")));
    }
    void reload(String value) {
        source = value; ready = false; cancel();
        if (stopping || failed || value.isBlank()) return;
        executor.execute(() -> {
            try { compile(); ready = true; }
            catch (RuntimeException | LinkageError exception) {
                failed = true; Barricade.LOGGER.error("Could not reload spatial field shader", exception);
            }
        });
    }
    CompletableFuture<int[]> submit(FieldBatch batch) {
        if (failed || stopping) return CompletableFuture.failedFuture(new IllegalStateException("Required field GPU unavailable"));
        CompletableFuture<int[]> result = new CompletableFuture<>();
        boolean inline = Thread.currentThread() == owner;
        long ticket = inline && activePreparation != null ? preparationRevision : revision.get();
        requests.add(result);
        Runnable task = () -> {
            try {
                if (cancelled(result, ticket)) throw new CancellationException("Field job superseded");
                compile();
                long limit = glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
                if (batch.input.length * 4L > limit || batch.initial.length * 4L > limit)
                    throw new FieldBatch.CapacityException("Complete dependency job exceeds GPU SSBO; subdivide targets");
                try (FieldGpuJob job = new PackingGpuJob(batch, evaluate, select, buffers)) {
                    allocatedBytes.set(buffers.bytes());
                    while (!job.finished()) {
                        if (cancelled(result, ticket))
                            throw new CancellationException("Spatial field GPU cancelled");
                        if (job.awaitBatch()) job.submit();
                    }
                    int[] accepted = job.read();
                    int error = glGetError();
                    if (error != GL_NO_ERROR) throw new IllegalStateException("Spatial field GL error " + error);
                    deviceNanos.addAndGet(Math.round(job.deviceMillis() * 1e6)); dispatches.addAndGet(job.dispatches());
                    if (cancelled(result, ticket)) throw new CancellationException("Stale field result");
                    if (result.complete(accepted)) completed.incrementAndGet();
                }
            } catch (CancellationException | IllegalArgumentException exception) { result.completeExceptionally(exception); }
            catch (RuntimeException | LinkageError exception) {
                failed = true; result.completeExceptionally(exception);
                Barricade.LOGGER.error("Required spatial field GPU failed; no CPU fallback", exception);
            } finally { requests.remove(result); }
        };
        if (inline) task.run(); else executor.execute(task);
        return result;
    }
    /** A whole field query makes one queue crossing; nested GPU jobs stay on the owning GL thread. */
    <T> CompletableFuture<T> prepare(Callable<T> work) {
        if (failed || stopping) return CompletableFuture.failedFuture(new IllegalStateException("Required field GPU unavailable"));
        CompletableFuture<T> result = new CompletableFuture<>();
        long ticket = revision.get(); requests.add(result);
        executor.execute(() -> {
            try {
                if (failed) throw new IllegalStateException("Required field GPU unavailable");
                if (cancelled(result, ticket)) throw new CancellationException("Field preparation superseded");
                activePreparation = result; preparationRevision = ticket;
                T value = work.call();
                if (cancelled(result, ticket)) throw new CancellationException("Stale field preparation");
                result.complete(value);
            } catch (Throwable exception) { result.completeExceptionally(exception); }
            finally { activePreparation = null; requests.remove(result); }
        });
        return result;
    }
    private boolean cancelled(CompletableFuture<?> result, long ticket) {
        return result.isDone() || ticket != revision.get() || stopping
                || activePreparation != null && activePreparation.isDone();
    }
    private void compile() {
        String next = source;
        if (evaluate != null && next.equals(compiled)) return;
        ComputeProgram proposal = null, selection;
        try {
            int line = next.indexOf('\n');
            if (line < 0) throw new IllegalArgumentException("Packing shader has no version line");
            proposal = compileSource(next.substring(0, line + 1) + "#define BARRICADE_STAGE 0\n" + next.substring(line + 1));
            selection = compileSource(next.substring(0, line + 1) + "#define BARRICADE_STAGE 1\n" + next.substring(line + 1));
        } catch (RuntimeException | LinkageError exception) {
            if (proposal != null) proposal.delete(); throw exception;
        }
        glUseProgram(0);
        if (evaluate != null) evaluate.delete();
        if (select != null) select.delete();
        evaluate = proposal; select = selection; compiled = next;
    }
    private static ComputeProgram compileSource(String text) {
        ComputeShader shader = new ComputeShader();
        ComputeProgram replacement = new ComputeProgram(BarrierFlags.getFlags(BarrierFlags.SHADER_STORAGE));
        try {
            shader.setShaderSource(text); shader.compileShader();
            if (!shader.isCompiled()) throw new IllegalStateException(shader.getInfoLog());
            replacement.attachShader(shader); replacement.linkProgram();
            if (!replacement.isLinked()) throw new IllegalStateException(replacement.getInfoLog());
        } catch (RuntimeException exception) { replacement.delete(); throw exception; }
        finally { shader.delete(); }
        return replacement;
    }
    synchronized CompletableFuture<Void> stop() {
        if (stopped != null) return stopped;
        stopping = true; cancel(); stopped = new CompletableFuture<>();
        executor.execute(() -> {
            RuntimeException failure = null;
            try {
                glUseProgram(0);
                if (evaluate != null) evaluate.delete();
                if (select != null) select.delete();
                if (buffers != null) buffers.close(); allocatedBytes.set(0);
            }
            catch (RuntimeException exception) { failure = exception; }
            finally { GL.setCapabilities(null); glfwMakeContextCurrent(0); }
            if (failure == null) stopped.complete(null); else stopped.completeExceptionally(failure);
        });
        executor.shutdown(); return stopped;
    }
}
