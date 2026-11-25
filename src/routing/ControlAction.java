/*
 * ControlAction.java
 * Represents an RFC 9675 Control (CTRL) executed by the DTNMA Autonomy Engine.
 */
package routing;

public class ControlAction {

    public enum ControlType {
        SET_SPRAY_QUOTA,
        SET_INGRESS_MODE,
        SET_TELEMETRY_INTERVAL,
        SET_BACKPRESSURE
    }

    private ControlType type;
    private double numericParam;
    private int intParam;
    private boolean boolParam;

    public static ControlAction setSprayQuota(int quota) {
        ControlAction ca = new ControlAction();
        ca.type = ControlType.SET_SPRAY_QUOTA;
        ca.intParam = quota;
        return ca;
    }

    public static ControlAction setIngressMode(int mode) {
        ControlAction ca = new ControlAction();
        ca.type = ControlType.SET_INGRESS_MODE;
        ca.intParam = mode;
        return ca;
    }

    public static ControlAction setTelemetryInterval(double intervalSec) {
        ControlAction ca = new ControlAction();
        ca.type = ControlType.SET_TELEMETRY_INTERVAL;
        ca.numericParam = intervalSec;
        return ca;
    }

    public static ControlAction setBackpressure(boolean enabled) {
        ControlAction ca = new ControlAction();
        ca.type = ControlType.SET_BACKPRESSURE;
        ca.boolParam = enabled;
        return ca;
    }

    public ControlType getType() {
        return type;
    }

    public int getIntParam() {
        return intParam;
    }

    public double getNumericParam() {
        return numericParam;
    }

    public boolean getBoolParam() {
        return boolParam;
    }

    /**
     * Clamps rule parameters into physically and mathematically valid bounds
     * so user-authored declarative rules cannot destabilize the router.
     */
    public void sanitize() {
        switch (type) {
            case SET_SPRAY_QUOTA:
                this.intParam = Math.max(1, Math.min(16, this.intParam));
                break;
            case SET_INGRESS_MODE:
                this.intParam = Math.max(0, Math.min(2, this.intParam));
                break;
            case SET_TELEMETRY_INTERVAL:
                this.numericParam = Math.max(1.0, Math.min(3600.0, this.numericParam));
                break;
            case SET_BACKPRESSURE:
                break;
        }
    }

    public void execute(DtnmaManagedRouter router) {
        switch (type) {
            case SET_SPRAY_QUOTA:
                router.setDynamicSprayQuota(intParam);
                break;
            case SET_INGRESS_MODE:
                router.setIngressMode(intParam);
                break;
            case SET_TELEMETRY_INTERVAL:
                router.setRoutineTelemetryInterval(numericParam);
                break;
            case SET_BACKPRESSURE:
                router.setBackpressureEnabled(boolParam);
                break;
        }
    }

    @Override
    public String toString() {
        switch (type) {
            case SET_SPRAY_QUOTA: return "CTRL_SET_SPRAY_QUOTA(" + intParam + ")";
            case SET_INGRESS_MODE: return "CTRL_SET_INGRESS_MODE(" + intParam + ")";
            case SET_TELEMETRY_INTERVAL: return "CTRL_SET_TELEMETRY_INTERVAL(" + numericParam + "s)";
            case SET_BACKPRESSURE: return "CTRL_SET_BACKPRESSURE(" + boolParam + ")";
            default: return "CTRL_UNKNOWN()";
        }
    }
}
