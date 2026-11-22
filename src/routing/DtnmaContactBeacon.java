/*
 * DtnmaContactBeacon.java
 * Compact quantized 16-byte peer telemetry snapshot exchanged explicitly on opportunistic contact
 * (Decentralized local-only / explicit contact-handshake neighbor state awareness).
 */
package routing;

public class DtnmaContactBeacon {

    /**
     * Explicit over-the-air protocol payload sizes for honest overhead accounting:
     * - Beacon (16 B): uint16 hostId [2B] + float32 timestamp [4B] + uint16 qRatio [2B]
     *   + int16 qDrift [2B] + uint16 energySoc [2B] + uint16 speed [2B]
     *   + uint8 mobilityClass [1B] + uint8 activeDegree [1B] = 16 bytes.
     * - Gossip (648 B): single reward critic (8 actions x 20 fp32 weights = 160 floats = 640 B) + 8 B epoch/class header.
     * - ACK ID (4 B): uint32 message digest per acknowledged bundle ID.
     */
    public static final int BEACON_SIZE_BYTES = 16;
    public static final int GOSSIP_PAYLOAD_BYTES =
            (DtnmaPolicyProfile.NUM_ACTIONS * ShieldedCmdpRlAgent.NUM_FEATURES) * 4 + 8; // 648 B
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
     * Constructs a quantized 16-byte contact telemetry beacon snapshot matching its wire encoding.
     */
    public DtnmaContactBeacon(int hostAddress, double timestamp, double queueRatio,
                              double queueDriftEwma, double energySoc, double nodeSpeed,
                              int mobilityClass, int activeDegree) {
        // 2B uint16 hostId
        this.hostAddress = (hostAddress < 0) ? 0xFFFF : Math.min(0xFFFE, hostAddress);
        // 4B float32 timestamp
        this.timestamp = (double) ((float) timestamp);
        // 2B uint16 qRatio in [0.0, 1.0]
        int qRatioRaw = (int) Math.round(clamp(queueRatio, 0.0, 1.0) * 65535.0);
        this.queueRatio = qRatioRaw / 65535.0;
        // 2B int16 qDrift in [-0.25, 0.25] (symmetric so 0.0 encodes to exact 0)
        int qDriftRaw = (int) Math.round((clamp(queueDriftEwma, -0.25, 0.25) / 0.25) * 32767.0);
        this.queueDriftEwma = (qDriftRaw / 32767.0) * 0.25;
        // 2B uint16 energySoc in [0.0, 1.0]
        int socRaw = (int) Math.round(clamp(energySoc, 0.0, 1.0) * 65535.0);
        this.energySoc = socRaw / 65535.0;
        // 2B uint16 speed in [0.0, 65.535] m/s (1 mm/s precision)
        int speedRaw = (int) Math.round(clamp(nodeSpeed, 0.0, 65.535) * 1000.0);
        this.nodeSpeed = speedRaw / 1000.0;
        // 1B uint8 mobilityClass & 1B uint8 activeDegree
        this.mobilityClass = Math.max(0, Math.min(255, mobilityClass));
        this.activeDegree = Math.max(0, Math.min(255, activeDegree));
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
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
