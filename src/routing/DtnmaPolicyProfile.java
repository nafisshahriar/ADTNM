/*
 * DtnmaPolicyProfile.java
 * Structured autonomic operating profiles for the Shielded CMDP RL action space.
 * Maps 8 balanced macro-epoch action indices (0..7) to RFC 9675 control actuators
 * and forwarding thresholds.
 */
package routing;

public class DtnmaPolicyProfile {

    public static final int NUM_ACTIONS = 8;

    private final int index;
    private final String ari;
    private final String name;
    private final int sprayQuota;
    private final double backpressureDelta;
    private final double focusPredMargin;
    private final double focusMinPred;
    private final int ingressMode;
    private final boolean backpressureEnabled;

    private static final DtnmaPolicyProfile[] PROFILES = new DtnmaPolicyProfile[] {
        new DtnmaPolicyProfile(0, "ARI://DTNMA/ACT/SAFE_EMERGENCY_HOLD",    "SAFE_EMERGENCY_HOLD",    1, 0.05, 0.35, 0.60, 2, true),
        new DtnmaPolicyProfile(1, "ARI://DTNMA/ACT/SAFE_THROTTLE_STRICT",   "SAFE_THROTTLE_STRICT",   2, 0.08, 0.30, 0.55, 1, true),
        new DtnmaPolicyProfile(2, "ARI://DTNMA/ACT/BALANCED_CONSERVATIVE",  "BALANCED_CONSERVATIVE",  4, 0.10, 0.28, 0.50, 0, true),
        new DtnmaPolicyProfile(3, "ARI://DTNMA/ACT/BALANCED_AGILE_FOCUS",   "BALANCED_AGILE_FOCUS",   6, 0.12, 0.25, 0.45, 0, true),
        new DtnmaPolicyProfile(4, "ARI://DTNMA/ACT/STANDARD_SPRAY_FOCUS",   "STANDARD_SPRAY_FOCUS",   6, 0.15, 0.25, 0.45, 0, false),
        new DtnmaPolicyProfile(5, "ARI://DTNMA/ACT/EXPLORE_AGILE_SPRAY",    "EXPLORE_AGILE_SPRAY",    8, 0.15, 0.20, 0.40, 0, true),
        new DtnmaPolicyProfile(6, "ARI://DTNMA/ACT/HIGH_SPRAY_BACKPRESSURE","HIGH_SPRAY_BACKPRESSURE",10, 0.15, 0.25, 0.45, 0, true),
        new DtnmaPolicyProfile(7, "ARI://DTNMA/ACT/HIGH_SPRAY_OPEN",        "HIGH_SPRAY_OPEN",        12, 0.30, 0.20, 0.40, 0, false)
    };

    private DtnmaPolicyProfile(int index, String ari, String name, int sprayQuota,
                               double backpressureDelta, double focusPredMargin,
                               double focusMinPred, int ingressMode, boolean backpressureEnabled) {
        this.index = index;
        this.ari = ari;
        this.name = name;
        this.sprayQuota = sprayQuota;
        this.backpressureDelta = backpressureDelta;
        this.focusPredMargin = focusPredMargin;
        this.focusMinPred = focusMinPred;
        this.ingressMode = ingressMode;
        this.backpressureEnabled = backpressureEnabled;
    }

    /**
     * Retrieves the policy profile for action index (clamped safely to 0..7).
     */
    public static DtnmaPolicyProfile get(int index) {
        int clamped = Math.max(0, Math.min(NUM_ACTIONS - 1, index));
        return PROFILES[clamped];
    }

    public int getIndex() {
        return index;
    }

    public String getAri() {
        return ari;
    }

    public String getName() {
        return name;
    }

    public int getSprayQuota() {
        return sprayQuota;
    }

    public double getBackpressureDelta() {
        return backpressureDelta;
    }

    public double getFocusPredMargin() {
        return focusPredMargin;
    }

    public double getFocusMinPred() {
        return focusMinPred;
    }

    public int getIngressMode() {
        return ingressMode;
    }

    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    @Override
    public String toString() {
        return name + "(L=" + sprayQuota + ",bp=" + backpressureDelta + ",ing=" + ingressMode + ")";
    }
}
