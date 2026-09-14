package com.jeladastudios.ftsgeology.tectonics;

/**
 * The tectonic state of one column of the world, as computed by {@link TectonicMap}. Everything is
 * derived from the world seed (plus the world biome source), so the same column always yields the
 * same answer without anything being stored on disk.
 *
 * @param plateId        stable id of the plate this column sits on
 * @param plateKind      whether that plate is oceanic or continental
 * @param plateVelX      plate drift on X, in arbitrary "cm per year" units, range about -1..1
 * @param plateVelZ      plate drift on Z, same units
 * @param neighbourId    plate on the other side of the nearest boundary
 * @param neighbourKind  crust type of that neighbour
 * @param faultType      what the nearest boundary is doing
 * @param faultDistance  blocks to the nearest plate boundary (perpendicular)
 * @param convergence    closing rate across the boundary; positive converging, negative rifting
 * @param shear          sideways grinding rate across the boundary, always positive
 * @param faultNormalX   unit vector across the nearest boundary, X part (points at the neighbour)
 * @param faultNormalZ   unit vector across the nearest boundary, Z part
 * @param stress         0..1 tectonic activity here: high on an active boundary, 0 deep inside a plate
 */
public record PlateSample(
        long plateId,
        PlateKind plateKind,
        double plateVelX,
        double plateVelZ,
        long neighbourId,
        PlateKind neighbourKind,
        FaultType faultType,
        double faultDistance,
        double convergence,
        double shear,
        double faultNormalX,
        double faultNormalZ,
        double stress) {

    /** True when this column is close enough to a boundary for tectonic features to belong here. */
    public boolean onFault() {
        return faultType != FaultType.INTERIOR;
    }

    /**
     * What the nearest boundary is doing, whatever this column's distance from it. {@link #faultType()} is this
     * answer within the fault zone and {@link FaultType#INTERIOR} outside it; the terrain reaches further than the
     * zone (a mountain belt is wider than the fault that raised it) and needs the boundary's kind out there too.
     * The one place either question is decided.
     */
    public FaultType boundaryType() {
        if (Math.abs(convergence) >= shear) {
            if (convergence > 0) {
                // Dense oceanic crust always loses and dives under; two continents just crumple.
                boolean anyOceanic = plateKind.isOceanic() || neighbourKind.isOceanic();
                return anyOceanic ? FaultType.CONVERGENT_SUBDUCTION : FaultType.CONVERGENT_COLLISION;
            }
            return FaultType.DIVERGENT;
        }
        return FaultType.TRANSFORM;
    }

    /**
     * How strong the boundary's grip is here, over a zone {@code widths} fault widths across: 1 on the line,
     * 0 at the edge, and the same curve {@link #stress()} uses within the fault zone itself.
     */
    public double belt(double faultWidth, double widths) {
        double proximity = Math.sqrt(Math.max(0.0, 1.0 - Math.min(1.0, across(faultWidth) / widths)));
        double motion = Math.min(1.0, Math.max(Math.abs(convergence), shear) / 1.2);
        return proximity * (0.45 + 0.55 * motion);
    }

    /** Speed of the plate this column rides on. */
    public double plateSpeed() {
        return Math.sqrt(plateVelX * plateVelX + plateVelZ * plateVelZ);
    }

    /** Compass-style bearing of the plate drift, degrees clockwise from north (-Z). */
    public double plateBearing() {
        double deg = Math.toDegrees(Math.atan2(plateVelX, -plateVelZ));
        return deg < 0 ? deg + 360.0 : deg;
    }

    /**
     * Strike of the fault: the horizontal direction the boundary LINE runs, perpendicular to
     * {@link #faultNormalX}/{@link #faultNormalZ}. Earthquake ruptures propagate along this, so
     * fissures and offsets must be laid out along it rather than in a random direction.
     */
    public double faultStrikeX() {
        return -faultNormalZ;
    }

    public double faultStrikeZ() {
        return faultNormalX;
    }

    /**
     * On a convergent boundary, is this the plate that goes under? Oceanic crust always does; between two of a
     * kind the lower id does, so both sides of one line agree. Everything the arc does happens on the other plate.
     */
    public boolean downGoing() {
        boolean ours = plateKind.isOceanic();
        boolean theirs = neighbourKind.isOceanic();
        if (ours != theirs) return ours;
        return Long.compareUnsigned(plateId, neighbourId) < 0;
    }

    /** On a subduction margin, is this the plate that rides over: the one the arc stands on? */
    public boolean overriding() {
        return faultType == FaultType.CONVERGENT_SUBDUCTION && !downGoing();
    }

    /**
     * The same question without the fault zone's edge: which side of a subduction margin this column is on,
     * however far away it lies. The terrain reaches past the zone and has to keep its answer out there, or the
     * back-arc would end in a step exactly one fault width from the boundary.
     */
    public boolean overridingSide() {
        return boundaryType() == FaultType.CONVERGENT_SUBDUCTION && !downGoing();
    }

    /** How far across the fault zone this column lies, as a share of its width: 0 on the line, 1 at the edge. */
    public double across(double faultWidth) {
        return faultDistance / faultWidth;
    }

    /**
     * Is this where an arc's volcanoes stand: on the overriding plate, a set way back from the trench? The arc
     * sits where the slab is deep enough to melt, roughly a quarter to three quarters of the way across the zone.
     */
    public boolean onArc(double faultWidth) {
        double a = across(faultWidth);
        return overriding() && a >= 0.25 && a <= 0.75;
    }
}
