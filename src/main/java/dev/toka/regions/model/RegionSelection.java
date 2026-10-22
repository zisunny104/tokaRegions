package dev.toka.regions.model;

/** One block-aligned part of a region. Corners are normalized so min never exceeds max. */
public record RegionSelection(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public RegionSelection {
        int x1 = Math.min(minX, maxX), x2 = Math.max(minX, maxX);
        int y1 = Math.min(minY, maxY), y2 = Math.max(minY, maxY);
        int z1 = Math.min(minZ, maxZ), z2 = Math.max(minZ, maxZ);
        minX = x1; maxX = x2; minY = y1; maxY = y2; minZ = z1; maxZ = z2;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(RegionSelection other) {
        return minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
