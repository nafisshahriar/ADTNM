/*
 * EddTelemetrySampler.java
 * Samples strongly-typed Externally Defined Data (EDD) across physical, link,
 * energy, and mobility subsystems conforming to RFC 9675 / RFC 7575.
 */
package routing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import core.Connection;
import core.Coord;
import core.DTNHost;
import core.SimClock;

public class EddTelemetrySampler {

    // Standardized EDD ARIs
    public static final String EDD_QUEUE_RATIO = "EDD_QUEUE_RATIO";
    public static final String EDD_QUEUE_DRIFT = "EDD_QUEUE_DRIFT";
    public static final String EDD_NODE_DENSITY = "EDD_NODE_DENSITY";
    public static final String EDD_ACTIVE_CONNECTIONS = "EDD_ACTIVE_CONNECTIONS";
    public static final String EDD_NODE_SPEED = "EDD_NODE_SPEED";
    public static final String EDD_ENERGY_SOC = "EDD_ENERGY_SOC";
    public static final String EDD_ENERGY_DRAIN = "EDD_ENERGY_DRAIN";
    public static final String EDD_NEIGHBOR_MIN_QUEUE = "EDD_NEIGHBOR_MIN_QUEUE";
    public static final String EDD_DEST_PROB = "EDD_DEST_PROB";

    // PRoPHET Predictability Constants (RFC 6693)
    public static final double P_INIT = 0.75;
    public static final double DEFAULT_BETA = 0.25;
    public static final double DEFAULT_GAMMA = 0.98;
    public static final double SECONDS_IN_TIME_UNIT = 30.0;

    private DTNHost host;
    private DtnmaRouter router;

    // Smoothing & Drift tracking
    private double lastSampleTime = 0.0;
    private double lastQueueRatio = 0.0;
    private double queueDrift = 0.0;

    // Mobility tracking (Universal speed calculation)
    private Coord lastLocation = null;
    private double nodeSpeed = 0.0;

    // Energy tracking
    private double lastEnergy = 1.0;
    private double energyDrainRate = 0.0;

    // Contact Density Sliding Window Tracking
    private static final double DENSITY_WINDOW_SEC = 300.0; // 5-minute sliding window
    private Map<Integer, Double> contactTimestamps;

    // PRoPHET Delivery Predictability Table
    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    public EddTelemetrySampler(DTNHost host, DtnmaRouter router) {
        this.host = host;
        this.router = router;
        this.contactTimestamps = new HashMap<Integer, Double>();
        this.preds = new HashMap<DTNHost, Double>();
        this.lastAgeUpdate = SimClock.getTime();
        if (host != null && host.getLocation() != null) {
            this.lastLocation = host.getLocation().clone();
        }
    }

    public void updateHost(DTNHost host) {
        this.host = host;
        if (host != null && host.getLocation() != null && this.lastLocation == null) {
            this.lastLocation = host.getLocation().clone();
        }
    }

    /**
     * Records a contact encounter for sliding-window density computation and PRoPHET updates.
     */
    public void recordContact(DTNHost peer) {
        if (peer != null) {
            contactTimestamps.put(peer.getAddress(), SimClock.getTime());
            updateDeliveryPredFor(peer);
            updateTransitivePreds(peer);
        }
    }

    /**
     * Updates direct encounter predictability with peer:
     * P(a, b) = P(a, b)_old + (1 - P(a, b)_old) * P_INIT
     */
    public void updateDeliveryPredFor(DTNHost peer) {
        if (peer == null || peer == host) return;
        double pOld = getPredFor(peer);
        double pNew = pOld + (1.0 - pOld) * P_INIT;
        preds.put(peer, pNew);
    }

    /**
     * Updates transitive predictability via peer:
     * P(a, c) = P(a, c)_old + (1 - P(a, c)_old) * P(a, b) * P(b, c) * BETA
     */
    public void updateTransitivePreds(DTNHost peer) {
        if (peer == null || peer.getRouter() == null) return;
        double pForHost = getPredFor(peer);
        Map<DTNHost, Double> othersPreds = null;
        if (peer.getRouter() instanceof DtnmaRouter) {
            DtnmaRouter pr = (DtnmaRouter) peer.getRouter();
            if (pr.getEddSampler() != null) {
                othersPreds = pr.getEddSampler().getDeliveryPreds();
            }
        } else if (peer.getRouter() instanceof ProphetRouter) {
            othersPreds = ((ProphetRouter) peer.getRouter()).getDeliveryPreds();
        }

        if (othersPreds != null) {
            for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
                DTNHost dest = e.getKey();
                if (dest == host || dest == peer) continue;
                double pOld = getPredFor(dest);
                double pNew = pOld + (1.0 - pOld) * pForHost * e.getValue() * DEFAULT_BETA;
                preds.put(dest, pNew);
            }
        }
    }

    /**
     * Ages delivery predictions over elapsed simulation time:
     * P(a, b) = P(a, b)_old * (GAMMA ^ (dt / secondsInTimeUnit))
     */
    private void ageDeliveryPreds() {
        double now = SimClock.getTime();
        double timeDiff = (now - this.lastAgeUpdate) / SECONDS_IN_TIME_UNIT;
        if (timeDiff <= 0.0) return;

        double mult = Math.pow(DEFAULT_GAMMA, timeDiff);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }
        this.lastAgeUpdate = now;
    }

    /**
     * Retrieves PRoPHET delivery predictability for target host.
     */
    public double getPredFor(DTNHost target) {
        ageDeliveryPreds();
        if (target == null) return 0.0;
        Double p = preds.get(target);
        return p != null ? p.doubleValue() : 0.0;
    }

    public Map<DTNHost, Double> getDeliveryPreds() {
        ageDeliveryPreds();
        return this.preds;
    }

    /**
     * Refreshes dynamic EDDs across all subsystems.
     */
    public void sampleNow() {
        double now = SimClock.getTime();
        double dt = now - lastSampleTime;

        if (dt >= 0.5) {
            // 1. Queue Ratio & Drift
            double currentQueueRatio = router != null ? router.getBufferOccupancyRatio() : 0.0;
            this.queueDrift = (currentQueueRatio - lastQueueRatio) / dt;
            this.lastQueueRatio = currentQueueRatio;

            // 2. Universal Speed calculation (m/s)
            if (host != null && host.getLocation() != null) {
                Coord curLoc = host.getLocation();
                if (lastLocation != null) {
                    double dist = curLoc.distance(lastLocation);
                    this.nodeSpeed = dist / dt;
                    this.lastLocation = curLoc.clone();
                } else {
                    this.lastLocation = curLoc.clone();
                }
            }

            // 3. Energy SoC & Drain Rate
            double curEnergy = (router != null) ? router.getEnergyLevel() : 1.0;
            this.energyDrainRate = Math.max(0.0, (lastEnergy - curEnergy) / dt);
            this.lastEnergy = curEnergy;

            this.lastSampleTime = now;
        }

        // Clean up sliding-window contact history
        contactTimestamps.entrySet().removeIf(entry -> (now - entry.getValue()) > DENSITY_WINDOW_SEC);
    }

    /**
     * Retrieves numeric value for a named EDD identifier.
     */
    public double getEddValue(String eddName) {
        if (EDD_QUEUE_RATIO.equals(eddName)) {
            return router != null ? router.getBufferOccupancyRatio() : 0.0;
        } else if (EDD_QUEUE_DRIFT.equals(eddName)) {
            return this.queueDrift;
        } else if (EDD_NODE_DENSITY.equals(eddName)) {
            return getNodeDensity();
        } else if (EDD_ACTIVE_CONNECTIONS.equals(eddName)) {
            return (host != null) ? host.getConnections().size() : 0.0;
        } else if (EDD_NODE_SPEED.equals(eddName)) {
            return this.nodeSpeed;
        } else if (EDD_ENERGY_SOC.equals(eddName)) {
            return (router != null) ? router.getEnergyLevel() : 1.0;
        } else if (EDD_ENERGY_DRAIN.equals(eddName)) {
            return this.energyDrainRate;
        } else if (EDD_NEIGHBOR_MIN_QUEUE.equals(eddName)) {
            return getMinNeighborQueueRatio();
        } else if (EDD_DEST_PROB.equals(eddName)) {
            ageDeliveryPreds();
            double maxP = 0.0;
            for (double p : preds.values()) {
                if (p > maxP) maxP = p;
            }
            return maxP;
        }
        return 0.0;
    }

    public double getEddValue(String eddName, DTNHost target) {
        if (EDD_DEST_PROB.equals(eddName)) {
            return getPredFor(target);
        }
        return getEddValue(eddName);
    }

    public double getNodeDensity() {
        Set<Integer> uniqueNodes = new HashSet<Integer>(contactTimestamps.keySet());
        if (host != null) {
            for (Connection c : host.getConnections()) {
                DTNHost other = c.getOtherNode(host);
                if (other != null) {
                    uniqueNodes.add(other.getAddress());
                }
            }
        }
        return uniqueNodes.size();
    }

    public double getMinNeighborQueueRatio() {
        if (host == null || host.getConnections().isEmpty()) {
            return 1.0;
        }

        double minRatio = 1.0;
        for (Connection c : host.getConnections()) {
            DTNHost other = c.getOtherNode(host);
            if (other != null && other.getRouter() instanceof DtnmaRouter) {
                DtnmaRouter otherRouter = (DtnmaRouter) other.getRouter();
                double otherRatio = otherRouter.getBufferOccupancyRatio();
                if (otherRatio < minRatio) {
                    minRatio = otherRatio;
                }
            } else {
                minRatio = 0.5;
            }
        }
        return minRatio;
    }
}
