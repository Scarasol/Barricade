package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.FieldBatch;
import static org.lwjgl.opengl.GL43.*;

/** Reused SSBO storage and timestamps for the current packing worker. */
final class PackingBuffers implements AutoCloseable {
    final int input = glGenBuffers(), state = glGenBuffers(), start = glGenQueries(), end = glGenQueries();
    private long inputBytes, stateBytes;

    void upload(FieldBatch batch) {
        inputBytes = upload(input, batch.input, inputBytes, GL_DYNAMIC_DRAW);
        stateBytes = upload(state, batch.initial, stateBytes, GL_DYNAMIC_READ);
    }
    private static long upload(int buffer, int[] data, long capacity, int usage) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        long required = data.length * 4L;
        if (required > capacity) { glBufferData(GL_SHADER_STORAGE_BUFFER, required, usage); capacity = required; }
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, data);
        return capacity;
    }
    long bytes() { return inputBytes + stateBytes; }
    @Override public void close() {
        glDeleteQueries(start); glDeleteQueries(end); glDeleteBuffers(input); glDeleteBuffers(state);
    }
}
