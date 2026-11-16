package com.scarasol.barricade.pile;

public record GridPos(int x, int y, int z) implements Comparable<GridPos> {
    public GridPos offset(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }

    @Override
    public int compareTo(GridPos other) {
        int result = Integer.compare(y, other.y);
        if (result == 0) {
            result = Integer.compare(z, other.z);
        }
        return result == 0 ? Integer.compare(x, other.x) : result;
    }
}
