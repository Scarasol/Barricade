package com.scarasol.barricade.window;

public record BoardCell(int u, int v) implements Comparable<BoardCell> {
    public long packed() { return (long) u << 32 | v & 0xffffffffL; }
    public static BoardCell unpack(long value) { return new BoardCell((int) (value >> 32), (int) value); }
    public BoardCell offset(int du, int dv) { return new BoardCell(u + du, v + dv); }
    @Override public int compareTo(BoardCell other) {
        int result = Integer.compare(v, other.v);
        return result == 0 ? Integer.compare(u, other.u) : result;
    }
}
