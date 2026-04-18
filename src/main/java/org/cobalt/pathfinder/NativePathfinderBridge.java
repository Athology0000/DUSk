package org.cobalt.pathfinder;

public class NativePathfinderBridge {

    static {
        try {
            String path = NativeLoader.extract("natives/windows/cobalt_pathfinder.dll");
            System.load(path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load cobalt_pathfinder.dll", e);
        }
    }

    public static native long    createEngine();
    public static native void    destroyEngine(long handle);

    /** waypoints: flat double[] of [x0,y0,z0, x1,y1,z1, ...] */
    public static native void    setRoute(long handle, double[] waypoints, boolean loop, int profile);

    public static native void    setTarget(long handle, double x, double y, double z);
    public static native void    setTargetWithRadius(long handle, double x, double y, double z, double radius);

    /**
     * Returns int[10]:
     * [0] forward  [1] back  [2] jump  [3] sneak  [4] sprint
     * [5] targetYaw   (Float.intBitsToFloat - raw, no GCD)
     * [6] targetPitch (Float.intBitsToFloat - raw, no GCD)
     * [7] PathStatus ordinal
     * [8] ActionType ordinal
     * [9] distanceToTarget (Float.intBitsToFloat)
     */
    public static native int[]   update(long handle, byte[] worldBuffer,
                                         int bx, int by, int bz,
                                         double px, double py, double pz,
                                         float yaw, float pitch, boolean onGround);

    public static native void    stop(long handle);
    public static native int     getStatus(long handle);

    /** Returns float[] packed as [x0,y0,z0, x1,y1,z1, ...] with block-center X/Z (+0.5 offset). Y is feet level (no offset). */
    public static native float[] getPathNodes(long handle);
}
