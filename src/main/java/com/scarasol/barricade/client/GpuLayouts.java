package com.scarasol.barricade.client;

import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.FieldBatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;

final class GpuLayouts {
    private static volatile GpuLayoutWorker worker;
    private static volatile boolean available;
    private static String source = "";
    private static long contextWindow;
    private static final CompletableFuture<GpuLayoutWorker> initialized = new CompletableFuture<>();

    static void resourcesReloaded(ResourceManager manager) {
        try (var reader = manager.openAsReader(new ResourceLocation(Barricade.MOD_ID, "shaders/packing.compute"))) {
            source = org.apache.commons.io.IOUtils.toString(reader);
        } catch (java.io.IOException exception) {
            source = "";
            Barricade.LOGGER.error("Regional packing shader unavailable", exception);
        }
        GpuLayoutWorker target = worker;
        if (target != null && !source.isEmpty()) target.reload(source);
        available = false;
    }

    static int[] generate(FieldBatch plan) {
        try {
            GpuLayoutWorker target = initialized.get(60, TimeUnit.SECONDS);
            return await(target.submit(plan));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("GPU layout cancelled");
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out waiting for the GPU layout worker", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CancellationException cancellation) throw cancellation;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("GPU layout failed", cause);
        }
    }
    static <T> T prepare(Callable<T> work) {
        try { return await(initialized.get(60, TimeUnit.SECONDS).prepare(work)); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Field preparation cancelled");
        } catch (TimeoutException exception) { throw new IllegalStateException("Timed out waiting for field preparation", exception); }
        catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Field preparation failed", cause);
        }
    }
    private static <T> T await(CompletableFuture<T> request) throws InterruptedException, ExecutionException, TimeoutException {
        try { return request.get(60, TimeUnit.SECONDS); }
        catch (InterruptedException | TimeoutException exception) { request.cancel(false); throw exception; }
    }

    static void frame() {
        if (worker != null) {
            available = worker.ready();
            return;
        }
        if (source.isEmpty()) return;
        if (!GL.getCapabilities().OpenGL43) {
            IllegalStateException failure = new IllegalStateException(
                    "Barricade spatial fields require OpenGL 4.3");
            initialized.completeExceptionally(failure);
            available = false;
            return;
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(1, 1, "Barricade compute", 0, 0);
        glfwDefaultWindowHints();
        if (window == 0) {
            initialized.completeExceptionally(new IllegalStateException("Could not create the GPU layout context"));
            available = false;
            return;
        }
        contextWindow = window;
        worker = new GpuLayoutWorker(window, source);
        initialized.complete(worker);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { worker.stop().get(3, TimeUnit.SECONDS); }
            catch (Exception exception) { Barricade.LOGGER.debug("GPU worker shutdown: {}", exception.toString()); }
        }, "Barricade GPU shutdown"));
        available = worker.ready();
    }

    static void reset() {
        if (worker != null) worker.cancel();
    }

    static void shutdown() {
        available = false;
        if (worker == null) return;
        try {
            worker.stop().get(60, TimeUnit.SECONDS);
            if (contextWindow != 0) { glfwDestroyWindow(contextWindow); contextWindow = 0; }
        } catch (Exception exception) {
            Barricade.LOGGER.debug("GPU shutdown did not complete before game exit: {}", exception.toString());
        }
    }

    static String stats() {
        return "gpu_layouts=" + (worker == null ? 0 : worker.completed())
                + ", gpu_available=" + available + ", gpu_failed=" + (worker != null && worker.failed());
    }
}
