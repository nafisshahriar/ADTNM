/*
 * EddTelemetrySampler.java
 * Samples strongly-typed Externally Defined Data (EDD) across physical, link,
 * energy, and mobility subsystems conforming to RFC 9675 / RFC 7575.
 *
 * Hardened telemetry sampler:
 * - EWMA smoothing for Queue Drift, Node Speed, Energy Drain, and Mobility EDDs.
 * - Decentralized local-only & contact-handshake neighbor sensing via DtnmaContactBeacon.
 */
package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import core.Connection;
import core.Coord;
import core.DTNHost;
import core.Settings;
import core.SimClock;
import core.SimScenario;

public class EddTelemetrySampler {

    // Standardized EDD ARIs
    public static final String EDD_QUEUE_RATIO = "EDD_QUEUE_RATIO";
    public static final String EDD_CRIT_QUEUE_RATIO = "EDD_CRIT_QUEUE_RATIO";
    public static final String EDD_QUEUE_DRIFT = "EDD_QUEUE_DRIFT";
    public static final String EDD_NODE_DENSITY = "EDD_NODE_DENSITY";
    public static final String EDD_ACTIVE_CONNECTIONS = "EDD_ACTIVE_CONNECTIONS";
    public static final String EDD_NODE_SPEED = "EDD_NODE_SPEED";
    public static final String EDD_ENERGY_SOC = "EDD_ENERGY_SOC";
    public static final String EDD_ENERGY_DRAIN = "EDD_ENERGY_DRAIN";
    public static final String EDD_NEIGHBOR_MIN_QUEUE = "EDD_NEIGHBOR_MIN_QUEUE";
    public static final String EDD_NEIGHBOR_QUEUE_GRAD = "EDD_NEIGHBOR_QUEUE_GRAD";
    public static final String EDD_DEST_PROB = "EDD_DEST_PROB";
    public static final String EDD_ENCOUNTER_RATE = "EDD_ENCOUNTER_RATE";
    public static final String EDD_CONTACT_DURATION_MEAN = "EDD_CONTACT_DURATION_MEAN";
    public static final String EDD_INTER_CONTACT_TIME = "EDD_INTER_CONTACT_TIME";
    public static final String EDD_ENERGY_TTD_RATIO = "EDD_ENERGY_TTD_RATIO";

    // EWMA Smoothing Constants
    private static final double ALPHA_DRIFT = 0.30;
    private static final double ALPHA_SPEED = 0.25;
    private static final double ALPHA_DRAIN = 0.20;
    private static final double ALPHA_MOBILITY = 0.30;

    // PRoPHET Predictability Constants (RFC 6693)
    public static final double P_INIT = 0.75;
    public static final double DEFAULT_BETA = 0.25;
    public static final double DEFAULT_GAMMA = 0.98;
    public static final double SECONDS_IN_TIME_UNIT = 30.0;

    private DTNHost host;
    private DtnmaManagedRouter router;

    // Smoothing & Drift tracking
    private double lastSampleTime = 0.0;
    private double lastQueueRatio = 0.0;
    private double queueDrift = 0.0;

    // Mobility tracking (EWMA-smoothed speed calculation)
    private Coord lastLocation = null;
    private double nodeSpeed = 0.0;

    // Energy tracking (normalized SoC in [0, 1])
    private double lastEnergySoc = 1.0;
    private double energyDrainRate = 0.0;

    // Contact Density & First-Class Mobility Lifecycle Tracking
    private static final double DENSITY_WINDOW_SEC = 300.0; // 5-minute sliding window
    private Map<Integer, Double> contactTimestamps;
    private Map<Integer, Double> activeContactStarts;
    private List<Double> recentEncounterStarts;
    private double encounterRatePerMin = 0.0;
    private double contactDurationMeanSec = 20.0;
    private double interContactTimeSec = 60.0;
    private double lastAnyContactTime = 0.0;
    private boolean hadCompletedContact = false;
    private boolean hadInterContactGap = false;
    private double cachedScenarioEndTime = -1.0;

    // PRoPHET Delivery Predictability Table
    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    public EddTelemetrySampler(DTNHost host, DtnmaManagedRouter router) {
        this.host = host;
        this.router = router;
        this.contactTimestamps = new HashMap<Integer, Double>();
        this.activeContactStarts = new HashMap<Integer, Double>();
        this.recentEncounterStarts = new ArrayList<Double>();
        this.preds = new HashMap<DTNHost, Double>();
        this.lastAgeUpdate = SimClock.getTime();
        if (host != null && host.getLocation() != null) {
            this.lastLocation = host.getLocation().clone();
        }
        if (router != null) {
            this.lastEnergySoc = router.getEnergySoc();
        }
    }

    public void updateHost(DTNHost host) {
        this.host = host;
        if (host != null && host.getLocation() != null && this.lastLocation == null) {
            this.lastLocation = host.getLocation().clone();
        }
    }

    /**
     * Records a contact encounter for sliding-window density, ICT/encounter-rate estimation, and PRoPHET updates.
     */
    public void recordContact(DTNHost peer) {
        if (peer != null) {
            double now = SimClock.getTime();
            int addr = peer.getAddress();
            contactTimestamps.put(addr, now);
            boolean wasDisconnected = activeContactStarts.isEmpty();
            if (!activeContactStarts.containsKey(addr)) {
                activeContactStarts.put(addr, now);
                recentEncounterStarts.add(now);
            }
            if (wasDisconnected && lastAnyContactTime > 0.0 && (now - lastAnyContactTime) > 0.5) {
                double rawIct = now - lastAnyContactTime;
                if (!hadInterContactGap) {
                    this.interContactTimeSec = rawIct;
                    this.hadInterContactGap = true;
                } else {
                    this.interContactTimeSec = ALPHA_MOBILITY * rawIct + (1.0 - ALPHA_MOBILITY) * this.interContactTimeSec;
                }
            }
            this.lastAnyContactTime = now;
            updateDeliveryPredFor(peer);
            updateTransitivePreds(peer);
        }
    }

    /**
     * Records link teardown to maintain EWMA contact duration and silent gap timing.
     */
    public void recordContactEnd(DTNHost peer) {
        if (peer == null) return;
        double now = SimClock.getTime();
        Double started = activeContactStarts.remove(peer.getAddress());
        if (started != null) {
            double dur = Math.max(0.5, now - started.doubleValue());
            if (!hadCompletedContact) {
                this.contactDurationMeanSec = dur;
                this.hadCompletedContact = true;
            } else {
                this.contactDurationMeanSec = ALPHA_MOBILITY * dur + (1.0 - ALPHA_MOBILITY) * this.contactDurationMeanSec;
            }
        }
        this.lastAnyContactTime = now;
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
        if (peer.getRouter() instanceof DtnmaManagedRouter) {
            DtnmaManagedRouter pr = (DtnmaManagedRouter) peer.getRouter();
            if (pr.getDtnmaAgent() != null && pr.getDtnmaAgent().getEddSampler() != null) {
                othersPreds = pr.getDtnmaAgent().getEddSampler().getDeliveryPreds();
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
     * Refreshes dynamic EDDs across all subsystems with EWMA filtering.
     */
    public void sampleNow() {
        double now = SimClock.getTime();
        double dt = now - lastSampleTime;

        if (dt >= 0.5) {
            // 1. Queue Ratio & EWMA-smoothed Drift
            double currentQueueRatio = router != null ? router.getBufferOccupancyRatio() : 0.0;
            double rawDrift = (currentQueueRatio - lastQueueRatio) / dt;
            rawDrift = clamp(rawDrift, -0.25, 0.25);
            this.queueDrift = ALPHA_DRIFT * rawDrift + (1.0 - ALPHA_DRIFT) * this.queueDrift;
            this.lastQueueRatio = currentQueueRatio;

            // 2. EWMA-smoothed Universal Speed calculation (m/s)
            if (host != null && host.getLocation() != null) {
                Coord curLoc = host.getLocation();
                if (lastLocation != null) {
                    double dist = curLoc.distance(lastLocation);
                    double instSpeed = dist / dt;
                    this.nodeSpeed = ALPHA_SPEED * instSpeed + (1.0 - ALPHA_SPEED) * this.nodeSpeed;
                    this.lastLocation = curLoc.clone();
                } else {
                    this.lastLocation = curLoc.clone();
                }
            }

            // 3. Normalized Energy SoC [0, 1] & EWMA-smoothed Drain Rate
            double curEnergySoc = (router != null) ? router.getEnergySoc() : 1.0;
            double rawDrain = Math.max(0.0, (lastEnergySoc - curEnergySoc) / dt);
            this.energyDrainRate = (this.energyDrainRate <= 0.0)
                    ? rawDrain
                    : (ALPHA_DRAIN * rawDrain + (1.0 - ALPHA_DRAIN) * this.energyDrainRate);
            this.lastEnergySoc = curEnergySoc;

            // 4. Sliding-window encounter arrival rate (contacts/min over DENSITY_WINDOW_SEC)
            double cutoff = now - DENSITY_WINDOW_SEC;
            Iterator<Double> it = recentEncounterStarts.iterator();
            while (it.hasNext()) {
                if (it.next().doubleValue() < cutoff) {
                    it.remove();
                } else {
                    break;
                }
            }
            double instEncRate = recentEncounterStarts.size() / (DENSITY_WINDOW_SEC / 60.0);
            this.encounterRatePerMin = ALPHA_MOBILITY * instEncRate + (1.0 - ALPHA_MOBILITY) * this.encounterRatePerMin;

            this.lastSampleTime = now;
        }

        // Clean up sliding-window contact history
        contactTimestamps.entrySet().removeIf(entry -> (now - entry.getValue()) > DENSITY_WINDOW_SEC);
    }

    /**
     * Classifies the node's mobility regime for Contact Beacon telemetry:
     * 0 = Pedestrian / Slow (< 2.2 m/s)
     * 1 = Vehicle / Standard (2.2 to 9.5 m/s)
     * 2 = Tram / Fast Backbone (>= 9.5 m/s or multi-interface relay)
     */
    public int getMobilityClass() {
        if (host != null && host.getInterfaces().size() > 1) {
            return 2;
        }
        if (this.nodeSpeed >= 9.5) {
            return 2;
        }
        if (this.nodeSpeed < 2.2) {
            return 0;
        }
        return 1;
    }

    /**
     * Retrieves numeric value for a named EDD identifier.
     */
    public double getEddValue(String eddName) {
        if (EDD_QUEUE_RATIO.equals(eddName)) {
            return router != null ? router.getBufferOccupancyRatio() : 0.0;
        } else if (EDD_CRIT_QUEUE_RATIO.equals(eddName)) {
            if (router == null) return 0.0;
            DtnmaAgent ag = router.getDtnmaAgent();
            return ag != null ? ag.getCachedCriticalOccupancyRatio() : router.getCriticalOccupancyRatio();
        } else if (EDD_QUEUE_DRIFT.equals(eddName)) {
            return this.queueDrift;
        } else if (EDD_NODE_DENSITY.equals(eddName)) {
            return getNodeDensity();
        } else if (EDD_ACTIVE_CONNECTIONS.equals(eddName)) {
            return (host != null) ? host.getConnections().size() : 0.0;
        } else if (EDD_NODE_SPEED.equals(eddName)) {
            return this.nodeSpeed;
        } else if (EDD_ENERGY_SOC.equals(eddName)) {
            return (router != null) ? router.getEnergySoc() : 1.0;
        } else if (EDD_ENERGY_DRAIN.equals(eddName)) {
            return this.energyDrainRate;
        } else if (EDD_NEIGHBOR_MIN_QUEUE.equals(eddName)) {
            return getMinNeighborQueueRatio();
        } else if (EDD_NEIGHBOR_QUEUE_GRAD.equals(eddName)) {
            double q = router != null ? router.getBufferOccupancyRatio() : 0.0;
            return q - getMinNeighborQueueRatio();
        } else if (EDD_DEST_PROB.equals(eddName)) {
            ageDeliveryPreds();
            double maxP = 0.0;
            for (double p : preds.values()) {
                if (p > maxP) maxP = p;
            }
            return maxP;
        } else if (EDD_ENCOUNTER_RATE.equals(eddName)) {
            return getEncounterRatePerMin();
        } else if (EDD_CONTACT_DURATION_MEAN.equals(eddName)) {
            return getContactDurationMeanSec();
        } else if (EDD_INTER_CONTACT_TIME.equals(eddName)) {
            return getEffectiveInterContactTimeSec();
        } else if (EDD_ENERGY_TTD_RATIO.equals(eddName)) {
            return getEnergyTtdRatio(0.0);
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

    /**
     * Computes minimum queue ratio across currently connected peers strictly from
     * explicit Contact Beacons (decentralized contact-handshake awareness).
     */
    public double getMinNeighborQueueRatio() {
        if (host == null || host.getConnections().isEmpty() || router == null) {
            return 1.0;
        }

        double minRatio = 1.0;
        for (Connection c : host.getConnections()) {
            DTNHost other = c.getOtherNode(host);
            if (other != null) {
                double otherRatio = router.getBeaconNeighborQueueRatio(other);
                if (otherRatio < minRatio) {
                    minRatio = otherRatio;
                }
            }
        }
        return minRatio;
    }

    public double getNodeSpeed() {
        return nodeSpeed;
    }

    public double getQueueDrift() {
        return queueDrift;
    }

    public double getEncounterRatePerMin() {
        return encounterRatePerMin;
    }

    public double getContactDurationMeanSec() {
        return contactDurationMeanSec;
    }

    public double getEffectiveInterContactTimeSec() {
        double now = SimClock.getTime();
        if (activeContactStarts.isEmpty() && lastAnyContactTime > 0.0) {
            return Math.max(interContactTimeSec, now - lastAnyContactTime);
        }
        return interContactTimeSec;
    }

    public double getEnergyDrainRate() {
        return energyDrainRate;
    }

    private double resolveScenarioEndTime() {
        if (cachedScenarioEndTime > 0.0) return cachedScenarioEndTime;
        try {
            Settings s = new Settings(SimScenario.SCENARIO_NS);
            if (s.contains(SimScenario.END_TIME_S)) {
                cachedScenarioEndTime = s.getDouble(SimScenario.END_TIME_S);
                return cachedScenarioEndTime;
            }
        } catch (Exception ignored) {
        }
        cachedScenarioEndTime = 43200.0;
        return cachedScenarioEndTime;
    }

    public double getEnergyTtdRatio(double configuredHorizonSec) {
        double soc = (router != null) ? clamp(router.getEnergySoc(), 0.0, 1.0) : 1.0;
        double remHorizon;
        if (configuredHorizonSec > 0.0) {
            remHorizon = configuredHorizonSec;
        } else {
            double endT = resolveScenarioEndTime();
            remHorizon = Math.max(600.0, endT - SimClock.getTime());
        }
        if (this.energyDrainRate <= 1e-8) {
            return 2.0;
        }
        double ttdSec = soc / this.energyDrainRate;
        return clamp(ttdSec / Math.max(1.0, remHorizon), 0.0, 2.0);
    }

    public long estimateStateBytes() {
        return 240L
                + 32L * (contactTimestamps != null ? contactTimestamps.size() : 0)
                + 24L * (activeContactStarts != null ? activeContactStarts.size() : 0)
                + 16L * (recentEncounterStarts != null ? recentEncounterStarts.size() : 0)
                + 40L * (preds != null ? preds.size() : 0);
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
