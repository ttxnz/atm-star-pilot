package dev.atmpilot;

/** Pure, bounded steering decisions shared by local controllers. Units are blocks and ticks. */
public final class MotionPolicy {
    private MotionPolicy() {}
    public static double preview(double remaining, double velocity, double intent) {
        if (!Double.isFinite(remaining) || !Double.isFinite(velocity) || !Double.isFinite(intent))
            throw new IllegalArgumentException("Non-finite motion sample");
        double predicted = velocity * 4 + intent;
        if (predicted * remaining >= 0)
            return Math.copySign(Math.min(Math.abs(predicted), Math.abs(remaining)), predicted);
        return Math.max(-1, Math.min(1, predicted));
    }
    public static boolean replan(long now, long last, double displacementSquared, boolean blocked) {
        return last < 0 || (now - last >= 4 && (displacementSquared > .64 || blocked));
    }
}
