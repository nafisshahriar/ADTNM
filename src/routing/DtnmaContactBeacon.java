/*
 * DtnmaContactBeacon.java
 * Compact peer telemetry snapshot exchanged explicitly on opportunistic contact
 * (Zero-Oracle neighbor state awareness for RFC 9675 DTNMA + Shielded CMDP RL).
 */
package routing;

public class DtnmaContactBeacon {

    /** Explicit over-the-air protocol payload sizes for honest overhead accounting. */
    public static final int BEACON_SIZE_BYTES = 16;
    public static final int GOSSIP_PAYLOAD_BYTES = 128;
    public static final int ACK_ID_BYTES = 4;

    private final int hostAddress;
    private final double timestamp;
    private final double queueRatio;
    private final double queueDriftEwma;
    private final double energySoc;
    private final double nodeSpeed;
    private final int mobilityClass;
    private final int activeDegree;

    /**
     * Constructs a compact contact telemetry beacon snapshot.
     */
    public DtnmaContactBeacon(int hostAddress, double timestamp, double queueRatio,
                              double queueDriftEwma, double energySoc, double nodeSpeed,
                              int mobilityClass, int activeDegree) {
        this.hostAddress = hostAddress;
        this.timestamp = timestamp;
        this.queueRatio = Math.max(0.0, Math.min(1.0, queueRatio));
        this.queueDriftEwma = queueDriftEwma;
        this.energySoc = Math.max(0.0, Math.min(1.0, energySoc));
        this.nodeSpeed = Math.max(0.0, nodeSpeed);
        this.mobilityClass = mobilityClass;
        this.activeDegree = Math.max(0, activeDegree);
    }

    /**
     * Returns true if this beacon is older than maxAgeSec at current simulation time.
     */
    public boolean isStale(double now, double maxAgeSec) {
        return (now - this.timestamp) > maxAgeSec;
    }

    public int getHostAddress() {
        return hostAddress;
    }

    public double getTimestamp() {
        return timestamp;
    }

    public double getQueueRatio() {
        return queueRatio;
    }

    public double getQueueDriftEwma() {
        return queueDriftEwma;
    }

    public double getEnergySoc() {
        return energySoc;
    }

    public double getNodeSpeed() {
        return nodeSpeed;
    }

    public int getMobilityClass() {
        return mobilityClass;
    }

    public int getActiveDegree() {
        return activeDegree;
    }
}
