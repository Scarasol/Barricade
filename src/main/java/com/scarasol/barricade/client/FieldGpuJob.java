package com.scarasol.barricade.client;

interface FieldGpuJob extends AutoCloseable {
    void submit();
    boolean awaitBatch();
    boolean finished();
    int[] read();
    double deviceMillis();
    int dispatches();
    @Override void close();
}
