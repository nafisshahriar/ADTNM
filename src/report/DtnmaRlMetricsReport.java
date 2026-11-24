/*
 * DtnmaRlMetricsReport.java
 * OppNDA-compatible key:value summary reporter for Autonomic DTNMA (RFC 9675 / RFC 7575):
 * - Tier 1 Guarantees: unique_critical_lost, critical_delivery_prob, critical_latency_avg/p95,
 *   Manager-Agent Age of Information (aoi_mean_sec, aoi_p95_sec, aoi_peak_sec), max_buffer_occupancy
 * - Tier 2 Efficiency: bulk_delivery_prob, bulk_latency_avg, downlink_t90_sec, downlink_consistency_ratio
 * - Tier 3 Autonomy & Self-Healing: shield rates, pre/in/post-fault delivery, recovery_time_to_95pct_sec,
 *   first_shield_or_rule_response_sec, alive_nodes_2h/4h/end
 * - Tier 4 Cost: control_bytes_total, control_to_payload_ratio, decision_cpu_us_mean
 */
package report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;
import core.SimScenario;
import core.UpdateListener;
import routing.DtnmaAgent;
import routing.DtnmaConfig;
import routing.DtnmaManagedRouter;
import routing.DtnmaPolicyProfile;
import routing.DtnmaRouter;
import routing.DtnmaSafetyShield;
import routing.EnergyAwareEpidemicRouter;
import routing.PriorityMaxPropRouter;
import routing.PrioritySprayAndWaitRouter;
import routing.ShieldedCmdpRlAgent;
import routing.util.EnergyModel;

public class DtnmaRlMetricsReport extends Report implements MessageListener, UpdateListener {

    private static final double FAULT_ONSET_SEC = 7200.0;
    private static final double FAULT_END_SEC = 14400.0;
    private static final double AOI_SAMPLE_INTERVAL_SEC = 10.0;

    private static final class MsgLifecycleRecord {
        final double createTime;
        final boolean isCrit;
        final boolean isCtrl;
        int eventMask = 0;
        boolean delivered = false;
        double delivTime = -1.0;

        MsgLifecycleRecord(double createTime, boolean isCrit, boolean isCtrl) {
            this.createTime = createTime;
            this.isCrit = isCrit;
            this.isCtrl = isCtrl;
        }
    }

    private static final class EventWindowState {
        final String label;
        final int bit;
        final double tStart;
        final double tEnd;
        final double horizon;

        int critCreatedAccepted = 0;
        int bulkCreatedAccepted = 0;
        long critRejectedSrc = 0L;
        long bulkRejectedSrc = 0L;

        Set<String> critEvictedIds = new HashSet<String>();
        Set<String> critEvictedAckedIds = new HashSet<String>();
        Set<String> bulkEvictedIds = new HashSet<String>();
        Set<String> critTtlIds = new HashSet<String>();
        Set<String> bulkTtlIds = new HashSet<String>();

        double peakOcc = 0.0;
        double nodeSecQ90 = 0.0;
        double energyJ = 0.0;
        double ctrlTxJ = 0.0;
        int depletedNew = 0;

        double atRiskNodeSec = 0.0;
        double atRiskThrottledNodeSec = 0.0;
        double staleThrottleNodeSec = 0.0;
        Map<Integer, Double> firstAtRiskTime = new HashMap<Integer, Double>();
        Map<Integer, Double> firstThrottleAfterRisk = new HashMap<Integer, Double>();

        EventWindowState(String label, int bit, double tStart, double tEnd, double horizon) {
            this.label = label;
            this.bit = bit;
            this.tStart = tStart;
            this.tEnd = tEnd;
            this.horizon = horizon;
        }
    }

    private int criticalCreated = 0;
    private int criticalDelivered = 0;
    private int criticalBufferDropped = 0;
    private int criticalBufferDroppedAcked = 0;
    private int criticalTtlExpired = 0;

    private int bulkCreated = 0;
    private int bulkDelivered = 0;
    private int bulkBufferDropped = 0;
    private int bulkTtlExpired = 0;

    private Set<String> criticalDeliveredIds = new HashSet<String>();
    private Set<String> criticalBufferDroppedIds = new HashSet<String>();
    private Map<Integer, Double> initialEnergyByHost = new HashMap<Integer, Double>();
    private boolean capturedInitialEnergy = false;
    private long deliveredPayloadBytes = 0;

    // Split Latency & Lifecycle Tracking
    private Map<String, Double> msgCreationTimes = new HashMap<String, Double>();
    private Map<String, MsgLifecycleRecord> msgRecords = new HashMap<String, MsgLifecycleRecord>();
    private List<Double> criticalLatencies = new ArrayList<Double>();
    private List<Double> bulkLatencies = new ArrayList<Double>();

    // Manager-Agent Age of Information (AoI) Tracking
    private Set<Integer> uplinkSourceHosts = new HashSet<Integer>();
    private Map<Integer, Double> uplinkFirstGenTime = new HashMap<Integer, Double>();
    private Map<Integer, Double> latestDeliveredGenTimeBySource = new HashMap<Integer, Double>();
    private List<Double> aoiSamples = new ArrayList<Double>();
    private double lastAoiSampleTime = -100.0;

    // Downlink Control Broadcast Convergence Tracking (CTRL_UPDATE_*)
    private int downlinkCreated = 0;
    private int downlinkDelivered = 0;
    private Map<String, Double> waveStartTime = new HashMap<String, Double>();
    private Map<String, Set<Integer>> waveTargets = new HashMap<String, Set<Integer>>();
    private Map<String, Set<Integer>> waveDeliveredTargets = new HashMap<String, Set<Integer>>();
    private Map<String, List<Double>> waveDelivOffsets = new HashMap<String, List<Double>>();
    private String latestWaveKey = null;

    // Self-Healing & Energy Blackout Tracking
    private int aliveNodes2h = -1;
    private int aliveNodes4h = -1;
    private double firstShieldOrRuleTimeAfter7200 = -1.0;

    // Threshold-crossing autonomic reaction latency tracking (from t >= 3600s)
    private Map<Integer, Double> pendingHazardCrossingTime = new HashMap<Integer, Double>();
    private Set<Integer> prevHazardHosts = new HashSet<Integer>();
    private List<Double> localReactionDelaysSec = new ArrayList<Double>();

    // Settings-driven Per-Event Cohort & Control Timing Tracking
    private List<EventWindowState> eventWindows = new ArrayList<EventWindowState>();
    private double groupTxEnergy = 0.0;
    private double lastUpdateTime = -1.0;
    private double lastControlSampleTime = -1.0;
    private double totalEnergyJ = 0.0;
    private double totalCtrlTxJ = 0.0;
    private Map<Integer, Double> prevEnergyByHost = new HashMap<Integer, Double>();
    private Map<Integer, Double> prev1HzQByHost = new HashMap<Integer, Double>();
    private Map<Integer, Long> prevCritRejByHost = new HashMap<Integer, Long>();
    private Map<Integer, Long> prevBulkRejByHost = new HashMap<Integer, Long>();

    public DtnmaRlMetricsReport() {
        super();
        init();
    }

    @Override
    protected void init() {
        super.init();
        this.criticalCreated = 0;
        this.criticalDelivered = 0;
        this.criticalBufferDropped = 0;
        this.criticalBufferDroppedAcked = 0;
        this.criticalTtlExpired = 0;
        this.bulkCreated = 0;
        this.bulkDelivered = 0;
        this.bulkBufferDropped = 0;
        this.bulkTtlExpired = 0;

        if (this.criticalDeliveredIds == null) this.criticalDeliveredIds = new HashSet<String>();
        else this.criticalDeliveredIds.clear();
        if (this.criticalBufferDroppedIds == null) this.criticalBufferDroppedIds = new HashSet<String>();
        else this.criticalBufferDroppedIds.clear();
        if (this.initialEnergyByHost == null) this.initialEnergyByHost = new HashMap<Integer, Double>();
        else this.initialEnergyByHost.clear();
        this.capturedInitialEnergy = false;
        this.deliveredPayloadBytes = 0;

        if (this.msgCreationTimes == null) this.msgCreationTimes = new HashMap<String, Double>();
        else this.msgCreationTimes.clear();
        if (this.msgRecords == null) this.msgRecords = new HashMap<String, MsgLifecycleRecord>();
        else this.msgRecords.clear();
        if (this.criticalLatencies == null) this.criticalLatencies = new ArrayList<Double>();
        else this.criticalLatencies.clear();
        if (this.bulkLatencies == null) this.bulkLatencies = new ArrayList<Double>();
        else this.bulkLatencies.clear();

        if (this.uplinkSourceHosts == null) this.uplinkSourceHosts = new HashSet<Integer>();
        else this.uplinkSourceHosts.clear();
        if (this.uplinkFirstGenTime == null) this.uplinkFirstGenTime = new HashMap<Integer, Double>();
        else this.uplinkFirstGenTime.clear();
        if (this.latestDeliveredGenTimeBySource == null) this.latestDeliveredGenTimeBySource = new HashMap<Integer, Double>();
        else this.latestDeliveredGenTimeBySource.clear();
        if (this.aoiSamples == null) this.aoiSamples = new ArrayList<Double>();
        else this.aoiSamples.clear();
        this.lastAoiSampleTime = -100.0;

        this.downlinkCreated = 0;
        this.downlinkDelivered = 0;
        if (this.waveStartTime == null) this.waveStartTime = new HashMap<String, Double>();
        else this.waveStartTime.clear();
        if (this.waveTargets == null) this.waveTargets = new HashMap<String, Set<Integer>>();
        else this.waveTargets.clear();
        if (this.waveDeliveredTargets == null) this.waveDeliveredTargets = new HashMap<String, Set<Integer>>();
        else this.waveDeliveredTargets.clear();
        if (this.waveDelivOffsets == null) this.waveDelivOffsets = new HashMap<String, List<Double>>();
        else this.waveDelivOffsets.clear();
        this.latestWaveKey = null;

        this.aliveNodes2h = -1;
        this.aliveNodes4h = -1;
        this.firstShieldOrRuleTimeAfter7200 = -1.0;

        if (this.pendingHazardCrossingTime == null) this.pendingHazardCrossingTime = new HashMap<Integer, Double>();
        else this.pendingHazardCrossingTime.clear();
        if (this.prevHazardHosts == null) this.prevHazardHosts = new HashSet<Integer>();
        else this.prevHazardHosts.clear();
        if (this.localReactionDelaysSec == null) this.localReactionDelaysSec = new ArrayList<Double>();
        else this.localReactionDelaysSec.clear();

        if (this.eventWindows == null) this.eventWindows = new ArrayList<EventWindowState>();
        else this.eventWindows.clear();
        if (this.prevEnergyByHost == null) this.prevEnergyByHost = new HashMap<Integer, Double>();
        else this.prevEnergyByHost.clear();
        if (this.prev1HzQByHost == null) this.prev1HzQByHost = new HashMap<Integer, Double>();
        else this.prev1HzQByHost.clear();
        if (this.prevCritRejByHost == null) this.prevCritRejByHost = new HashMap<Integer, Long>();
        else this.prevCritRejByHost.clear();
        if (this.prevBulkRejByHost == null) this.prevBulkRejByHost = new HashMap<Integer, Long>();
        else this.prevBulkRejByHost.clear();
        this.groupTxEnergy = 0.0;
        this.lastUpdateTime = -1.0;
        this.lastControlSampleTime = -1.0;
        this.totalEnergyJ = 0.0;
        this.totalCtrlTxJ = 0.0;

        try {
            Settings grpSettings = new Settings("Group");
            if (grpSettings.contains("transmitEnergy")) {
                this.groupTxEnergy = grpSettings.getDouble("transmitEnergy");
            }
            Settings repSettings = getSettings();
            double horizon = repSettings.contains("eventHorizon")
                    ? repSettings.getDouble("eventHorizon") : 3600.0;
            String[] labels = new String[] { "A", "B", "C" };
            for (int i = 0; i < labels.length; i++) {
                String key = "event" + labels[i];
                if (repSettings.contains(key)) {
                    double[] bounds = repSettings.getCsvDoubles(key, 2);
                    this.eventWindows.add(new EventWindowState(
                            labels[i], 1 << i, bounds[0], bounds[1], horizon));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractWaveKey(String msgId) {
        if (msgId == null) return "V0";
        if (msgId.startsWith("CTRL_UPDATE_V1")) return "V1";
        if (msgId.startsWith("CTRL_UPDATE_V2")) return "V2";
        if (msgId.startsWith("CTRL_UPDATE_V3")) return "V3";
        int idx = msgId.length();
        while (idx > 0 && Character.isDigit(msgId.charAt(idx - 1))) {
            idx--;
        }
        return idx > 0 ? msgId.substring(0, idx) : "V0";
    }

    private static double getHostQueueRatio(DTNHost h) {
        if (h.getRouter() instanceof DtnmaManagedRouter) {
            return ((DtnmaManagedRouter) h.getRouter()).getBufferOccupancyRatio();
        }
        if (h.getRouter() instanceof EnergyAwareEpidemicRouter) {
            return ((EnergyAwareEpidemicRouter) h.getRouter()).getBufferOccupancyRatio();
        }
        return Math.max(0.0, h.getBufferOccupancy() / 100.0);
    }

    private double getHostEnergySoc(DTNHost h) {
        if (h.getRouter() instanceof DtnmaManagedRouter) {
            return ((DtnmaManagedRouter) h.getRouter()).getEnergySoc();
        }
        if (h.getRouter() instanceof EnergyAwareEpidemicRouter) {
            return ((EnergyAwareEpidemicRouter) h.getRouter()).getEnergySoc();
        }
        Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
        if (eObj instanceof Double) {
            double curE = (Double) eObj;
            Double initE = (this.initialEnergyByHost != null) ? this.initialEnergyByHost.get(h.getAddress()) : null;
            if (initE != null && initE > 0.0) {
                return Math.max(0.0, Math.min(1.0, curE / initE));
            }
            return curE > 0.0 ? 1.0 : 0.0;
        }
        return 1.0;
    }

    private static boolean isHostThrottleActive(DTNHost h) {
        if (h.getRouter() instanceof DtnmaManagedRouter) {
            DtnmaAgent dr = ((DtnmaManagedRouter) h.getRouter()).getDtnmaAgent();
            if (dr == null) return false;
            return dr.getCurrentIngressMode() >= DtnmaRouter.INGRESS_THROTTLE_BULK
                    || dr.getCurrentSprayQuota() <= 2;
        }
        if (h.getRouter() instanceof EnergyAwareEpidemicRouter) {
            return ((EnergyAwareEpidemicRouter) h.getRouter()).isThrottleActive();
        }
        return false;
    }

    public static boolean isHostKnownAcked(DTNHost where, Message m) {
        if (where == null || m == null) return false;
        if (where.getRouter() instanceof DtnmaManagedRouter) {
            DtnmaAgent dr = ((DtnmaManagedRouter) where.getRouter()).getDtnmaAgent();
            return dr != null && dr.isAcked(m);
        }
        if (where.getRouter() instanceof EnergyAwareEpidemicRouter) {
            return ((EnergyAwareEpidemicRouter) where.getRouter()).isAcked(m);
        }
        return false;
    }

    private static int countAliveHosts(List<DTNHost> hosts) {
        if (hosts == null) return 0;
        int alive = 0;
        for (DTNHost h : hosts) {
            Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
            if (!(eObj instanceof Double) || ((Double) eObj) > 1e-9) {
                alive++;
            }
        }
        return alive;
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return;
        }
        double now = getSimTime();

        if (!this.capturedInitialEnergy) {
            boolean foundAny = false;
            for (DTNHost h : hosts) {
                Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                if (eObj instanceof Double) {
                    this.initialEnergyByHost.put(h.getAddress(), (Double) eObj);
                    foundAny = true;
                }
            }
            if (foundAny || now > 2.0) {
                this.capturedInitialEnergy = true;
            }
        }

        // Snapshot alive nodes at fault onset (2h) and fault end (4h)
        if (this.aliveNodes2h < 0 && now >= FAULT_ONSET_SEC) {
            this.aliveNodes2h = countAliveHosts(hosts);
        }
        if (this.aliveNodes4h < 0 && now >= FAULT_END_SEC) {
            this.aliveNodes4h = countAliveHosts(hosts);
        }

        // Track earliest autonomic shield/safe-rule activation after fault onset (t >= 7200)
        // and threshold-crossing reaction latencies (t >= 3600) across alive autonomic nodes
        for (DTNHost h : hosts) {
            if (!(h.getRouter() instanceof DtnmaManagedRouter)) continue;
            DtnmaManagedRouter mr = (DtnmaManagedRouter) h.getRouter();
            DtnmaAgent dr = mr.getDtnmaAgent();
            if (dr == null) continue;

            if (now >= FAULT_ONSET_SEC && this.firstShieldOrRuleTimeAfter7200 < 0.0
                    && dr.getEffectiveManagementMode() != DtnmaConfig.ManagementMode.M0_STATIC
                    && (dr.isLastShieldIntervened()
                        || dr.getLastActionIndex() == DtnmaRouter.REGIME_CONGESTED_SAFE
                        || dr.getCurrentIngressMode() >= DtnmaRouter.INGRESS_THROTTLE_BULK)) {
                this.firstShieldOrRuleTimeAfter7200 = now;
            }

            if (now >= 3600.0 && dr.isAutonomicControlActive()) {
                Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                if (!h.isRadioActive() || (eObj instanceof Double && ((Double) eObj) <= 1e-9)) {
                    this.pendingHazardCrossingTime.remove(h.getAddress());
                    this.prevHazardHosts.remove(h.getAddress());
                    continue;
                }
                DtnmaConfig cfg = dr.getConfig();
                double soc = mr.getEnergySoc();
                double q = mr.getBufferOccupancyRatio();
                double critQ = dr.getCachedCriticalOccupancyRatio();
                double dq = (dr.getEddSampler() != null) ? dr.getEddSampler().getQueueDrift() : 0.0;
                double eMin = (cfg != null) ? cfg.shieldEnergyMin : 0.20;
                double wCritQ = (cfg != null) ? cfg.shieldWarnCritQueue : 0.35;

                boolean hazardNow = (soc < eMin) || (critQ >= wCritQ) || (q >= 0.88 && dq >= 0.008);
                boolean inThrottleOrSafe = dr.isLastShieldIntervened()
                        || dr.getLastActionIndex() == DtnmaRouter.REGIME_CONGESTED_SAFE
                        || dr.getCurrentIngressMode() >= DtnmaRouter.INGRESS_THROTTLE_BULK;
                int addr = h.getAddress();

                if (hazardNow && !this.prevHazardHosts.contains(addr)) {
                    if (inThrottleOrSafe) {
                        this.localReactionDelaysSec.add(0.0);
                    } else {
                        this.pendingHazardCrossingTime.put(addr, now);
                    }
                } else if (this.pendingHazardCrossingTime.containsKey(addr)) {
                    if (inThrottleOrSafe) {
                        double tStart = this.pendingHazardCrossingTime.remove(addr);
                        this.localReactionDelaysSec.add(Math.max(0.0, now - tStart));
                    } else if (!hazardNow) {
                        this.pendingHazardCrossingTime.remove(addr);
                    }
                }
                if (hazardNow) {
                    this.prevHazardHosts.add(addr);
                } else {
                    this.prevHazardHosts.remove(addr);
                }
            }
        }

        // Sample Manager-side Age of Information (AoI) periodically across reporting agents
        if ((now - this.lastAoiSampleTime) >= AOI_SAMPLE_INTERVAL_SEC) {
            this.lastAoiSampleTime = now;
            for (Integer src : this.uplinkSourceHosts) {
                Double latestGen = this.latestDeliveredGenTimeBySource.get(src);
                Double firstGen = this.uplinkFirstGenTime.get(src);
                double ref = (latestGen != null) ? latestGen : (firstGen != null ? firstGen : now);
                double instAoi = Math.max(0.0, now - ref);
                this.aoiSamples.add(instAoi);
            }
        }

        // Per-tick Energy, Ctrl TX Energy, Peak Q>=0.90 node-seconds, and Source Rejections
        double dt = (this.lastUpdateTime >= 0.0) ? Math.max(0.0, now - this.lastUpdateTime) : 0.0;
        this.lastUpdateTime = now;

        for (DTNHost h : hosts) {
            int addr = h.getAddress();
            Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
            boolean hasE = (eObj instanceof Double);
            double curE = hasE ? (Double) eObj : 0.0;
            boolean isAlive = !hasE || curE > 1e-9;

            double dE = 0.0;
            boolean newlyDepleted = false;
            if (hasE) {
                Double prevE = this.prevEnergyByHost.get(addr);
                if (prevE != null) {
                    dE = Math.max(0.0, prevE - curE);
                    newlyDepleted = (prevE > 1e-9 && curE <= 1e-9);
                }
                this.prevEnergyByHost.put(addr, curE);
                this.totalEnergyJ += dE;
            }

            double hostCtrlTxJ = 0.0;
            if (dE > 0.0 && dt > 0.0 && this.groupTxEnergy > 0.0) {
                int totalXfer = 0;
                int ctrlXfer = 0;
                for (Connection con : h.getConnections()) {
                    if (con.isTransferring() && con.getMessage() != null) {
                        totalXfer++;
                        if (DtnmaRouter.isDownlinkControlMessage(con.getMessage())) {
                            ctrlXfer++;
                        }
                    }
                }
                if (totalXfer > 0 && ctrlXfer > 0) {
                    hostCtrlTxJ = Math.min(dE, dt * this.groupTxEnergy) * ((double) ctrlXfer / totalXfer);
                    this.totalCtrlTxJ += hostCtrlTxJ;
                }
            }

            long curCritRej = 0L;
            long curBulkRej = 0L;
            if (h.getRouter() instanceof DtnmaManagedRouter) {
                DtnmaAgent dr = ((DtnmaManagedRouter) h.getRouter()).getDtnmaAgent();
                if (dr != null) {
                    curCritRej = dr.getCriticalCreationRejected();
                    curBulkRej = dr.getBulkCreationRejected();
                }
            } else if (h.getRouter() instanceof EnergyAwareEpidemicRouter) {
                EnergyAwareEpidemicRouter er = (EnergyAwareEpidemicRouter) h.getRouter();
                curCritRej = er.getCriticalCreationRejected();
                curBulkRej = er.getBulkCreationRejected();
            } else if (h.getRouter() instanceof PrioritySprayAndWaitRouter) {
                curBulkRej = ((PrioritySprayAndWaitRouter) h.getRouter()).getLocalCreationRejected();
            } else if (h.getRouter() instanceof PriorityMaxPropRouter) {
                curBulkRej = ((PriorityMaxPropRouter) h.getRouter()).getLocalCreationRejected();
            }
            long dCritRej = Math.max(0L, curCritRej - this.prevCritRejByHost.getOrDefault(addr, curCritRej));
            long dBulkRej = Math.max(0L, curBulkRej - this.prevBulkRejByHost.getOrDefault(addr, curBulkRej));
            this.prevCritRejByHost.put(addr, curCritRej);
            this.prevBulkRejByHost.put(addr, curBulkRej);

            if (!this.eventWindows.isEmpty() && dt > 0.0) {
                double q = isAlive ? getHostQueueRatio(h) : 0.0;
                for (EventWindowState w : this.eventWindows) {
                    if (now >= w.tStart && now <= w.tEnd) {
                        w.energyJ += dE;
                        w.ctrlTxJ += hostCtrlTxJ;
                        if (newlyDepleted) w.depletedNew++;
                        if (isAlive) {
                            if (q > w.peakOcc) w.peakOcc = q;
                            if (q >= 0.90) w.nodeSecQ90 += dt;
                        }
                        w.critRejectedSrc += dCritRej;
                        w.bulkRejectedSrc += dBulkRej;
                    }
                }
            }
        }

        // 1 Hz Per-Event Control-Timing Sampling (coverage, stale throttle, censored reaction)
        if (!this.eventWindows.isEmpty()
                && (this.lastControlSampleTime < 0.0 || (now - this.lastControlSampleTime) >= 1.0 - 1e-9)) {
            double sampleDt = (this.lastControlSampleTime < 0.0)
                    ? 1.0 : Math.min(2.0, Math.max(0.5, now - this.lastControlSampleTime));
            this.lastControlSampleTime = now;

            for (DTNHost h : hosts) {
                int addr = h.getAddress();
                Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                boolean isAlive = !(eObj instanceof Double) || ((Double) eObj) > 1e-9;
                if (!isAlive) continue;

                double q = getHostQueueRatio(h);
                Double prevQ = this.prev1HzQByHost.get(addr);
                double dQ1Hz = (prevQ != null) ? ((q - prevQ) / sampleDt) : 0.0;
                this.prev1HzQByHost.put(addr, q);

                double soc = getHostEnergySoc(h);
                boolean atRisk = (q >= 0.82) || (dQ1Hz > 0.015) || (soc < 0.25);
                boolean throttleActive = isHostThrottleActive(h);

                for (EventWindowState w : this.eventWindows) {
                    if (now >= w.tStart && now <= w.tEnd) {
                        if (atRisk) {
                            w.atRiskNodeSec += sampleDt;
                            if (throttleActive) {
                                w.atRiskThrottledNodeSec += sampleDt;
                            }
                            w.firstAtRiskTime.putIfAbsent(addr, now);
                        }
                    }
                    if (now >= w.tStart && now <= w.tEnd + w.horizon) {
                        if (w.firstAtRiskTime.containsKey(addr)
                                && !w.firstThrottleAfterRisk.containsKey(addr)
                                && throttleActive) {
                            w.firstThrottleAfterRisk.put(addr, now);
                        }
                    }
                    if (now > w.tEnd && now <= w.tEnd + w.horizon) {
                        if (throttleActive && !atRisk) {
                            w.staleThrottleNodeSec += sampleDt;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void newMessage(Message m) {
        if (isWarmup()) {
            addWarmupID(m.getId());
            return;
        }
        double now = getSimTime();
        boolean isCrit = DtnmaRouter.isCriticalMessage(m);
        boolean isCtrl = DtnmaRouter.isDownlinkControlMessage(m);
        this.msgCreationTimes.put(m.getId(), now);
        MsgLifecycleRecord rec = new MsgLifecycleRecord(now, isCrit, isCtrl);
        if (!isCtrl && !this.eventWindows.isEmpty()) {
            for (EventWindowState w : this.eventWindows) {
                if (now >= w.tStart && now <= w.tEnd) {
                    rec.eventMask |= w.bit;
                    if (isCrit) w.critCreatedAccepted++;
                    else w.bulkCreatedAccepted++;
                }
            }
        }
        this.msgRecords.put(m.getId(), rec);

        if (isCrit) {
            criticalCreated++;
        } else {
            bulkCreated++;
        }

        if (DtnmaRouter.isGroupControlMessage(m)) {
            String w = extractWaveKey(m.getId());
            this.latestWaveKey = w;
            if (!this.waveStartTime.containsKey(w)) {
                this.waveStartTime.put(w, now);
            }
            Set<Integer> tSet = this.waveTargets.computeIfAbsent(w, k -> new HashSet<Integer>());
            List<DTNHost> allHosts = null;
            try {
                allHosts = SimScenario.getInstance().getHosts();
            } catch (Exception ignored) {
            }
            if (allHosts != null && !allHosts.isEmpty()) {
                for (DTNHost h : allHosts) {
                    if (h != null && !h.toString().startsWith("mgr")) {
                        tSet.add(h.getAddress());
                    }
                }
            }
            if (tSet.isEmpty()) {
                for (int addr = 0; addr < 120; addr++) {
                    tSet.add(addr);
                }
            }
            downlinkCreated += tSet.size();
        } else if (DtnmaRouter.isDownlinkControlMessage(m) && m.getTo() != null) {
            downlinkCreated++;
            String w = extractWaveKey(m.getId());
            this.latestWaveKey = w;
            if (!this.waveStartTime.containsKey(w)) {
                this.waveStartTime.put(w, now);
            }
            this.waveTargets.computeIfAbsent(w, k -> new HashSet<Integer>()).add(m.getTo().getAddress());
        } else if (DtnmaRouter.isTelemetryOrAlarmMessage(m) && m.getFrom() != null) {
            int src = m.getFrom().getAddress();
            this.uplinkSourceHosts.add(src);
            if (!this.uplinkFirstGenTime.containsKey(src)) {
                this.uplinkFirstGenTime.put(src, now);
            }
        }
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
        if (isWarmupID(m.getId())) {
            return;
        }
        if (finalTarget) {
            double now = getSimTime();
            deliveredPayloadBytes += m.getSize();
            Double cTime = this.msgCreationTimes.get(m.getId());
            double lat = (cTime != null) ? Math.max(0.0, now - cTime) : Math.max(0.0, now - m.getCreationTime());

            MsgLifecycleRecord rec = this.msgRecords.get(m.getId());
            if (rec != null && !rec.delivered) {
                rec.delivered = true;
                rec.delivTime = now;
            }

            if (DtnmaRouter.isCriticalMessage(m)) {
                criticalDelivered++;
                criticalDeliveredIds.add(m.getId());
                this.criticalLatencies.add(lat);
            } else {
                bulkDelivered++;
                this.bulkLatencies.add(lat);
            }

            if (DtnmaRouter.isDownlinkControlMessage(m) && !DtnmaRouter.isGroupControlMessage(m) && to != null) {
                downlinkDelivered++;
                String w = extractWaveKey(m.getId());
                double wStart = this.waveStartTime.getOrDefault(w, now);
                this.waveDelivOffsets.computeIfAbsent(w, k -> new ArrayList<Double>()).add(Math.max(0.0, now - wStart));
                this.waveDeliveredTargets.computeIfAbsent(w, k -> new HashSet<Integer>()).add(to.getAddress());
            } else if (DtnmaRouter.isTelemetryOrAlarmMessage(m) && m.getFrom() != null) {
                int src = m.getFrom().getAddress();
                double genT = (cTime != null) ? cTime : m.getCreationTime();
                Double prev = this.latestDeliveredGenTimeBySource.get(src);
                if (prev == null || genT > prev) {
                    this.latestDeliveredGenTimeBySource.put(src, genT);
                }
            }
        } else if (DtnmaRouter.isGroupControlMessage(m) && to != null) {
            double now = getSimTime();
            String w = extractWaveKey(m.getId());
            Set<Integer> tSet = this.waveTargets.get(w);
            if (tSet != null && tSet.contains(to.getAddress())) {
                Set<Integer> dSet = this.waveDeliveredTargets.computeIfAbsent(w, k -> new HashSet<Integer>());
                if (dSet.add(to.getAddress())) {
                    downlinkDelivered++;
                    double wStart = this.waveStartTime.getOrDefault(w, now);
                    this.waveDelivOffsets.computeIfAbsent(w, k -> new ArrayList<Double>()).add(Math.max(0.0, now - wStart));
                }
            }
        }
    }

    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        if (isWarmupID(m.getId())) {
            return;
        }
        if (dropped) {
            double now = getSimTime();
            boolean isCrit = DtnmaRouter.isCriticalMessage(m);
            boolean isTtlExpired = (m.getTtl() <= 0);
            boolean ackedAtHost = isCrit && !isTtlExpired && isHostKnownAcked(where, m);
            MsgLifecycleRecord rec = this.msgRecords.get(m.getId());
            if (rec != null && !rec.isCtrl && rec.eventMask != 0) {
                for (EventWindowState w : this.eventWindows) {
                    if ((rec.eventMask & w.bit) != 0 && now >= w.tStart && now <= w.tEnd + w.horizon) {
                        if (isCrit) {
                            if (isTtlExpired) w.critTtlIds.add(m.getId());
                            else if (ackedAtHost) w.critEvictedAckedIds.add(m.getId());
                            else w.critEvictedIds.add(m.getId());
                        } else {
                            if (isTtlExpired) w.bulkTtlIds.add(m.getId());
                            else w.bulkEvictedIds.add(m.getId());
                        }
                    }
                }
            }
            if (isCrit) {
                if (isTtlExpired) {
                    criticalTtlExpired++;
                } else if (ackedAtHost) {
                    criticalBufferDroppedAcked++;
                } else {
                    criticalBufferDropped++;
                    criticalBufferDroppedIds.add(m.getId());
                }
            } else {
                if (isTtlExpired) bulkTtlExpired++;
                else bulkBufferDropped++;
            }
        }
    }

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {}

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {}

    private static double computeMean(List<Double> vals) {
        if (vals == null || vals.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double v : vals) sum += v;
        return sum / vals.size();
    }

    private static double computePercentile(List<Double> vals, double pct) {
        if (vals == null || vals.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<Double>(vals);
        Collections.sort(sorted);
        int idx = (int) Math.min(sorted.size() - 1, Math.max(0, Math.ceil(pct * sorted.size()) - 1));
        return sorted.get(idx);
    }

    private double computeCohortDelivery(double tStart, double tEnd, boolean criticalOnly, double maxHorizonSec) {
        int tot = 0;
        int del = 0;
        for (MsgLifecycleRecord r : this.msgRecords.values()) {
            if (r.createTime >= tStart && r.createTime < tEnd) {
                if (criticalOnly && !r.isCrit) continue;
                tot++;
                if (r.delivered && (maxHorizonSec <= 0.0 || (r.delivTime - r.createTime) <= maxHorizonSec)) {
                    del++;
                }
            }
        }
        return tot > 0 ? (double) del / tot : 0.0;
    }

    @Override
    public void done() {
        long rlDecisionsTotal = 0;
        long shieldInterventionsTotal = 0;
        double shieldEarlySum = 0.0;
        double shieldLateSum = 0.0;
        double shieldGreedySum = 0.0;
        double shieldGreedyEarlySum = 0.0;
        double shieldGreedyLateSum = 0.0;
        int shieldNodes = 0;

        double rlAvgRewardSum = 0.0;
        double rlAvgCostSum = 0.0;
        double dualLambdaSum = 0.0;
        int rlNodes = 0;

        long beaconOverheadBytes = 0;
        long gossipExchanges = 0;
        long gossipOverheadBytes = 0;
        long ackExchanges = 0;
        long ackOverheadBytes = 0;

        long cpuNanosTotal = 0;
        long cpuDecisionsTotal = 0;

        double energySocSum = 0.0;
        double allEnergySocSum = 0.0;
        int energyNodes = 0;
        long depletedNodes = 0;
        double maxBufferOccupancy = 0.0;
        long criticalCreationRejected = 0;
        long bulkCreationRejected = 0;
        long localCreationRejected = 0;
        int dtnmaNodes = 0;
        long[] profileCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];
        long totalProfileExecs = 0;

        Set<Integer> aliveHostIds = new HashSet<Integer>();
        int aliveDtnmaAgents = 0;
        int adoptedDtnmaAgents = 0;
        int expectedCtrlVer = (this.waveStartTime != null) ? this.waveStartTime.size() : 0;

        List<DTNHost> hosts = SimScenario.getInstance().getHosts();
        if (hosts != null) {
            for (DTNHost h : hosts) {
                Object eObjCheck = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                boolean isHostAlive = !(eObjCheck instanceof Double) || ((Double) eObjCheck) > 1e-9;
                if (isHostAlive) {
                    aliveHostIds.add(h.getAddress());
                }

                if (h.getRouter() instanceof DtnmaManagedRouter) {
                    DtnmaManagedRouter mr = (DtnmaManagedRouter) h.getRouter();
                    DtnmaAgent r = mr.getDtnmaAgent();
                    dtnmaNodes++;
                    energySocSum += mr.getEnergySoc();
                    if (r != null) {
                        if (r.getMaxBufferOccupancy() > maxBufferOccupancy) {
                            maxBufferOccupancy = r.getMaxBufferOccupancy();
                        }
                        criticalCreationRejected += r.getCriticalCreationRejected();
                        bulkCreationRejected += r.getBulkCreationRejected();
                        localCreationRejected += r.getLocalCreationRejected();
                        beaconOverheadBytes += r.getBeaconOverheadBytes();
                        gossipExchanges += r.getGossipExchanges();
                        gossipOverheadBytes += r.getGossipOverheadBytes();
                        ackExchanges += r.getAckExchanges();
                        ackOverheadBytes += r.getAckOverheadBytes();
                        cpuNanosTotal += r.getDecisionCpuNanosTotal();
                        cpuDecisionsTotal += r.getDecisionCpuCount();

                        if (isHostAlive && expectedCtrlVer > 0) {
                            aliveDtnmaAgents++;
                            if (r.getAppliedControlVersion() >= expectedCtrlVer) {
                                adoptedDtnmaAgents++;
                            }
                        }

                        long[] pCounts = r.getProfileExecCounts();
                        if (pCounts != null) {
                            for (int a = 0; a < DtnmaPolicyProfile.NUM_ACTIONS && a < pCounts.length; a++) {
                                profileCounts[a] += pCounts[a];
                                totalProfileExecs += pCounts[a];
                            }
                        }

                        DtnmaSafetyShield shield = r.getSafetyShield();
                        if (shield != null) {
                            rlDecisionsTotal += shield.getTotalDecisions();
                            shieldInterventionsTotal += shield.getTotalInterventions();
                            if (shield.getTotalDecisions() > 0) {
                                shieldEarlySum += shield.getEarlyInterventionRate();
                                shieldLateSum += shield.getLateInterventionRate();
                                shieldGreedySum += shield.getGreedyInterventionRate();
                                shieldGreedyEarlySum += shield.getGreedyEarlyInterventionRate();
                                shieldGreedyLateSum += shield.getGreedyLateInterventionRate();
                                shieldNodes++;
                            }
                        }

                        ShieldedCmdpRlAgent agent = r.getRlAgent();
                        if (agent != null) {
                            rlAvgRewardSum += agent.getAvgReward();
                            rlAvgCostSum += agent.getAvgCost();
                            dualLambdaSum += agent.getLambda();
                            rlNodes++;
                        }
                    }
                } else if (h.getRouter() instanceof EnergyAwareEpidemicRouter) {
                    EnergyAwareEpidemicRouter er = (EnergyAwareEpidemicRouter) h.getRouter();
                    if (er.getMaxBufferOccupancy() > maxBufferOccupancy) {
                        maxBufferOccupancy = er.getMaxBufferOccupancy();
                    }
                    criticalCreationRejected += er.getCriticalCreationRejected();
                    bulkCreationRejected += er.getBulkCreationRejected();
                    localCreationRejected += er.getLocalCreationRejected();
                } else if (h.getRouter() instanceof PrioritySprayAndWaitRouter) {
                    PrioritySprayAndWaitRouter pr = (PrioritySprayAndWaitRouter) h.getRouter();
                    if (pr.getMaxBufferOccupancy() > maxBufferOccupancy) {
                        maxBufferOccupancy = pr.getMaxBufferOccupancy();
                    }
                    localCreationRejected += pr.getLocalCreationRejected();
                } else if (h.getRouter() instanceof PriorityMaxPropRouter) {
                    PriorityMaxPropRouter pm = (PriorityMaxPropRouter) h.getRouter();
                    if (pm.getMaxBufferOccupancy() > maxBufferOccupancy) {
                        maxBufferOccupancy = pm.getMaxBufferOccupancy();
                    }
                    localCreationRejected += pm.getLocalCreationRejected();
                }

                Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                if (eObj instanceof Double) {
                    double curE = (Double) eObj;
                    energyNodes++;
                    if (curE <= 1e-9) {
                        depletedNodes++;
                    }
                    double soc;
                    if (h.getRouter() instanceof DtnmaManagedRouter) {
                        soc = ((DtnmaManagedRouter) h.getRouter()).getEnergySoc();
                    } else {
                        Double initE = (this.initialEnergyByHost != null) ? this.initialEnergyByHost.get(h.getAddress()) : null;
                        if (initE != null && initE > 0.0) {
                            soc = Math.max(0.0, Math.min(1.0, curE / initE));
                        } else {
                            soc = curE > 0.0 ? 1.0 : 0.0;
                        }
                    }
                    allEnergySocSum += soc;
                }
            }
        }

        int uniqueCriticalLost = 0;
        for (String droppedId : criticalBufferDroppedIds) {
            if (!criticalDeliveredIds.contains(droppedId)) {
                uniqueCriticalLost++;
            }
        }

        double simTime = getSimTime();
        double critDeliveryProb = criticalCreated > 0 ? (double) criticalDelivered / criticalCreated : 0.0;
        double bulkDeliveryProb = bulkCreated > 0 ? (double) bulkDelivered / bulkCreated : 0.0;
        double critLatAvg = computeMean(this.criticalLatencies);
        double critLatP95 = computePercentile(this.criticalLatencies, 0.95);
        double bulkLatAvg = computeMean(this.bulkLatencies);

        // AoI Stats
        double aoiMean = computeMean(this.aoiSamples);
        double aoiP95 = computePercentile(this.aoiSamples, 0.95);
        double aoiPeak = 0.0;
        for (double v : this.aoiSamples) {
            if (v > aoiPeak) aoiPeak = v;
        }

        // Downlink T90 & Consistency
        double downlinkDelivProb = downlinkCreated > 0 ? (double) downlinkDelivered / downlinkCreated : 0.0;
        double downlinkT90Sum = 0.0;
        int wavesReached90 = 0;
        int nWaves = this.waveStartTime.size();
        for (Map.Entry<String, Double> we : this.waveStartTime.entrySet()) {
            String wk = we.getKey();
            double wStart = we.getValue();
            int nTarg = this.waveTargets.containsKey(wk) ? this.waveTargets.get(wk).size() : 0;
            int need90 = Math.max(1, (int) Math.ceil(0.90 * nTarg));
            List<Double> offs = this.waveDelivOffsets.get(wk);
            if (offs != null && offs.size() >= need90) {
                List<Double> sorted = new ArrayList<Double>(offs);
                Collections.sort(sorted);
                downlinkT90Sum += sorted.get(need90 - 1);
                wavesReached90++;
            } else {
                downlinkT90Sum += Math.max(0.0, simTime - wStart);
            }
        }
        double downlinkT90Mean = nWaves > 0 ? (downlinkT90Sum / nWaves) : 0.0;
        double downlink90Reached = nWaves > 0 ? (double) wavesReached90 / nWaves : 0.0;

        double downlinkConsistency = 0.0;
        if (this.latestWaveKey != null && this.waveTargets.containsKey(this.latestWaveKey)) {
            Set<Integer> tSet = this.waveTargets.get(this.latestWaveKey);
            Set<Integer> dSet = this.waveDeliveredTargets.getOrDefault(this.latestWaveKey, Collections.<Integer>emptySet());
            int aliveTarg = 0;
            int delivAlive = 0;
            for (Integer addr : tSet) {
                if (aliveHostIds.isEmpty() || aliveHostIds.contains(addr)) {
                    aliveTarg++;
                    if (dSet.contains(addr)) delivAlive++;
                }
            }
            downlinkConsistency = aliveTarg > 0 ? (double) delivAlive / aliveTarg : 0.0;
        }
        double activeCtrlAdoptedFrac = aliveDtnmaAgents > 0 ? (double) adoptedDtnmaAgents / aliveDtnmaAgents : 0.0;

        // Self-Healing & Recovery Cohort Stats (horizon-matched at 3600s to eliminate right-censoring bias)
        double matchedHorizon = (simTime >= FAULT_END_SEC + 3600.0) ? 3600.0 : 0.0;
        double postEnd = (matchedHorizon > 0.0) ? (simTime - matchedHorizon) : (simTime + 1.0);
        double preFaultDeliv = computeCohortDelivery(1800.0, FAULT_ONSET_SEC, false, matchedHorizon);
        double inFaultDeliv = computeCohortDelivery(FAULT_ONSET_SEC, FAULT_END_SEC, false, matchedHorizon);
        double postFaultDeliv = computeCohortDelivery(FAULT_END_SEC, postEnd, false, matchedHorizon);
        double postFaultCritDeliv = computeCohortDelivery(FAULT_END_SEC, postEnd, true, matchedHorizon);
        double recoveryRatio = preFaultDeliv > 1e-6 ? (postFaultDeliv / preFaultDeliv) : 0.0;

        double recTime95 = 0.0;
        double maxWinEnd = (matchedHorizon > 0.0) ? (simTime - matchedHorizon) : simTime;
        if (maxWinEnd > FAULT_ONSET_SEC + 900.0 && preFaultDeliv > 1e-6) {
            double target = 0.95 * preFaultDeliv;
            boolean dipped = false;
            double dipStart = FAULT_ONSET_SEC;
            double recoveredAt = -1.0;
            for (double winStart = FAULT_ONSET_SEC; winStart + 900.0 <= maxWinEnd; winStart += 300.0) {
                int totW = 0;
                int delW = 0;
                for (MsgLifecycleRecord mr : this.msgRecords.values()) {
                    if (mr.createTime >= winStart && mr.createTime < winStart + 900.0) {
                        totW++;
                        if (mr.delivered && (matchedHorizon <= 0.0 || (mr.delivTime - mr.createTime) <= matchedHorizon)) {
                            delW++;
                        }
                    }
                }
                if (totW >= 5) {
                    double wRate = (double) delW / totW;
                    if (!dipped && wRate < target) {
                        dipped = true;
                        dipStart = winStart;
                    } else if (dipped && winStart > dipStart && wRate >= target) {
                        recoveredAt = winStart;
                        break;
                    }
                }
            }
            if (!dipped) {
                recTime95 = 0.0;
            } else if (recoveredAt >= FAULT_ONSET_SEC) {
                recTime95 = recoveredAt - FAULT_ONSET_SEC;
            } else {
                recTime95 = maxWinEnd - FAULT_ONSET_SEC;
            }
        }
        double firstRuleRespSec = (this.firstShieldOrRuleTimeAfter7200 >= FAULT_ONSET_SEC)
                ? (this.firstShieldOrRuleTimeAfter7200 - FAULT_ONSET_SEC) : -1.0;

        int aliveEnd = countAliveHosts(hosts);
        if (this.aliveNodes2h < 0) this.aliveNodes2h = aliveEnd;
        if (this.aliveNodes4h < 0) this.aliveNodes4h = aliveEnd;

        double shieldIntRate = rlDecisionsTotal > 0 ? (double) shieldInterventionsTotal / rlDecisionsTotal : 0.0;
        double shieldEarlyRate = shieldNodes > 0 ? shieldEarlySum / shieldNodes : 0.0;
        double shieldLateRate = shieldNodes > 0 ? shieldLateSum / shieldNodes : 0.0;
        double shieldGreedyRate = shieldNodes > 0 ? shieldGreedySum / shieldNodes : 0.0;
        double shieldGreedyEarlyRate = shieldNodes > 0 ? shieldGreedyEarlySum / shieldNodes : 0.0;
        double shieldGreedyLateRate = shieldNodes > 0 ? shieldGreedyLateSum / shieldNodes : 0.0;
        double rlAvgReward = rlNodes > 0 ? rlAvgRewardSum / rlNodes : 0.0;
        double rlAvgCost = rlNodes > 0 ? rlAvgCostSum / rlNodes : 0.0;
        double dualLambdaAvg = rlNodes > 0 ? dualLambdaSum / rlNodes : 0.0;
        double avgEnergySoc = energyNodes > 0
                ? (allEnergySocSum / energyNodes)
                : (dtnmaNodes > 0 ? energySocSum / dtnmaNodes : 1.0);
        long totalCtrlBytes = beaconOverheadBytes + gossipOverheadBytes + ackOverheadBytes;
        double ctrlToPayloadRatio = (double) totalCtrlBytes / Math.max(1.0, (double) deliveredPayloadBytes);
        double decisionCpuUsMean = cpuDecisionsTotal > 0
                ? ((double) cpuNanosTotal / 1000.0) / cpuDecisionsTotal : 0.0;

        StringBuilder sb = new StringBuilder();
        sb.append("RL metrics for scenario ").append(getScenarioName()).append("\n");
        sb.append(String.format(Locale.US, "sim_time: %.4f\n", simTime));
        sb.append("critical_created: ").append(criticalCreated).append("\n");
        sb.append("critical_delivered: ").append(criticalDelivered).append("\n");
        sb.append("critical_buffer_dropped: ").append(criticalBufferDropped).append("\n");
        sb.append("critical_buffer_dropped_acked: ").append(criticalBufferDroppedAcked).append("\n");
        sb.append("unique_critical_lost: ").append(uniqueCriticalLost).append("\n");
        sb.append("critical_ttl_expired: ").append(criticalTtlExpired).append("\n");
        sb.append(String.format(Locale.US, "critical_delivery_prob: %.4f\n", critDeliveryProb));
        sb.append(String.format(Locale.US, "critical_latency_avg: %.4f\n", critLatAvg));
        sb.append(String.format(Locale.US, "critical_latency_p95: %.4f\n", critLatP95));
        sb.append("bulk_created: ").append(bulkCreated).append("\n");
        sb.append("bulk_delivered: ").append(bulkDelivered).append("\n");
        sb.append("bulk_buffer_dropped: ").append(bulkBufferDropped).append("\n");
        sb.append("bulk_ttl_expired: ").append(bulkTtlExpired).append("\n");
        sb.append(String.format(Locale.US, "bulk_delivery_prob: %.4f\n", bulkDeliveryProb));
        sb.append(String.format(Locale.US, "bulk_latency_avg: %.4f\n", bulkLatAvg));
        sb.append(String.format(Locale.US, "aoi_mean_sec: %.4f\n", aoiMean));
        sb.append(String.format(Locale.US, "aoi_p95_sec: %.4f\n", aoiP95));
        sb.append(String.format(Locale.US, "aoi_peak_sec: %.4f\n", aoiPeak));
        sb.append("downlink_created: ").append(downlinkCreated).append("\n");
        sb.append("downlink_delivered: ").append(downlinkDelivered).append("\n");
        sb.append(String.format(Locale.US, "downlink_delivery_prob: %.4f\n", downlinkDelivProb));
        sb.append(String.format(Locale.US, "downlink_t90_sec: %.4f\n", downlinkT90Mean));
        sb.append(String.format(Locale.US, "downlink_90pct_reached: %.4f\n", downlink90Reached));
        sb.append(String.format(Locale.US, "downlink_consistency_ratio: %.4f\n", downlinkConsistency));
        sb.append(String.format(Locale.US, "control_version_adopted_frac: %.4f\n", activeCtrlAdoptedFrac));
        sb.append(String.format(Locale.US, "pre_fault_delivery_prob: %.4f\n", preFaultDeliv));
        sb.append(String.format(Locale.US, "in_fault_delivery_prob: %.4f\n", inFaultDeliv));
        sb.append(String.format(Locale.US, "post_fault_delivery_prob: %.4f\n", postFaultDeliv));
        sb.append(String.format(Locale.US, "post_fault_critical_delivery_prob: %.4f\n", postFaultCritDeliv));
        sb.append(String.format(Locale.US, "recovery_ratio: %.4f\n", recoveryRatio));
        sb.append(String.format(Locale.US, "recovery_time_to_95pct_sec: %.4f\n", recTime95));
        double localReactionMean = this.localReactionDelaysSec.isEmpty()
                ? -1.0 : computeMean(this.localReactionDelaysSec);
        double localReactionP95 = this.localReactionDelaysSec.isEmpty()
                ? -1.0 : computePercentile(this.localReactionDelaysSec, 0.95);
        sb.append(String.format(Locale.US, "first_shield_or_rule_response_sec: %.4f\n", firstRuleRespSec));
        sb.append("threshold_crossings_count: ").append(this.localReactionDelaysSec.size()).append("\n");
        sb.append(String.format(Locale.US, "local_reaction_delay_mean_sec: %.4f\n", localReactionMean));
        sb.append(String.format(Locale.US, "local_reaction_delay_p95_sec: %.4f\n", localReactionP95));
        sb.append("alive_nodes_2h: ").append(this.aliveNodes2h).append("\n");
        sb.append("alive_nodes_4h: ").append(this.aliveNodes4h).append("\n");
        sb.append("alive_nodes_end: ").append(aliveEnd).append("\n");
        sb.append("delivered_payload_bytes: ").append(deliveredPayloadBytes).append("\n");
        sb.append("critical_creation_rejected: ").append(criticalCreationRejected).append("\n");
        sb.append("bulk_creation_rejected: ").append(bulkCreationRejected).append("\n");
        sb.append("effective_critical_loss: ").append((long) uniqueCriticalLost + criticalCreationRejected).append("\n");
        sb.append("local_creation_rejected: ").append(localCreationRejected).append("\n");
        sb.append(String.format(Locale.US, "max_buffer_occupancy: %.4f\n", maxBufferOccupancy));
        sb.append("rl_decisions_total: ").append(rlDecisionsTotal).append("\n");
        sb.append("shield_interventions_total: ").append(shieldInterventionsTotal).append("\n");
        sb.append(String.format(Locale.US, "shield_intervention_rate: %.4f\n", shieldIntRate));
        sb.append(String.format(Locale.US, "shield_early_rate: %.4f\n", shieldEarlyRate));
        sb.append(String.format(Locale.US, "shield_late_rate: %.4f\n", shieldLateRate));
        sb.append(String.format(Locale.US, "shield_greedy_intervention_rate: %.4f\n", shieldGreedyRate));
        sb.append(String.format(Locale.US, "shield_greedy_early_rate: %.4f\n", shieldGreedyEarlyRate));
        sb.append(String.format(Locale.US, "shield_greedy_late_rate: %.4f\n", shieldGreedyLateRate));
        sb.append(String.format(Locale.US, "rl_avg_reward: %.4f\n", rlAvgReward));
        sb.append(String.format(Locale.US, "rl_avg_cost: %.4f\n", rlAvgCost));
        sb.append(String.format(Locale.US, "dual_lambda_avg: %.4f\n", dualLambdaAvg));
        sb.append("beacon_overhead_bytes: ").append(beaconOverheadBytes).append("\n");
        sb.append("gossip_exchanges: ").append(gossipExchanges).append("\n");
        sb.append("gossip_overhead_bytes: ").append(gossipOverheadBytes).append("\n");
        sb.append("ack_exchanges: ").append(ackExchanges).append("\n");
        sb.append("ack_overhead_bytes: ").append(ackOverheadBytes).append("\n");
        sb.append("control_bytes_total: ").append(totalCtrlBytes).append("\n");
        sb.append(String.format(Locale.US, "control_to_payload_ratio: %.6f\n", ctrlToPayloadRatio));
        sb.append(String.format(Locale.US, "decision_cpu_us_mean: %.4f\n", decisionCpuUsMean));
        for (int a = 0; a < DtnmaPolicyProfile.NUM_ACTIONS; a++) {
            double frac = totalProfileExecs > 0 ? (double) profileCounts[a] / totalProfileExecs : 0.0;
            sb.append(String.format(Locale.US, "profile_%d_frac: %.4f\n", a, frac));
        }
        sb.append(String.format(Locale.US, "avg_energy_soc: %.4f\n", avgEnergySoc));
        sb.append("depleted_nodes_count: ").append(depletedNodes).append("\n");
        sb.append(String.format(Locale.US, "avg_energy_remaining_soc: %.4f\n", avgEnergySoc));
        int totalHostsN = (hosts != null && !hosts.isEmpty()) ? hosts.size() : 1;
        sb.append("n_hosts: ").append(totalHostsN).append("\n");
        sb.append(String.format(Locale.US, "total_energy_J: %.4f\n", this.totalEnergyJ));
        sb.append(String.format(Locale.US, "total_ctrl_tx_J: %.4f", this.totalCtrlTxJ));

        for (EventWindowState w : this.eventWindows) {
            List<Double> critLats = new ArrayList<Double>();
            List<Double> bulkLats = new ArrayList<Double>();
            int critDelH = 0;
            int bulkDelH = 0;
            Set<String> critDeliveredByH = new HashSet<String>();
            double cutoff = w.tEnd + w.horizon;

            for (Map.Entry<String, MsgLifecycleRecord> me : this.msgRecords.entrySet()) {
                MsgLifecycleRecord rec = me.getValue();
                if ((rec.eventMask & w.bit) == 0 || rec.isCtrl) continue;
                if (rec.delivered && rec.delivTime <= cutoff) {
                    double lat = Math.max(0.0, rec.delivTime - rec.createTime);
                    if (rec.isCrit) {
                        critDelH++;
                        critDeliveredByH.add(me.getKey());
                        critLats.add(lat);
                    } else {
                        bulkDelH++;
                        bulkLats.add(lat);
                    }
                }
            }
            int critUniqueLostW = 0;
            for (String evictedId : w.critEvictedIds) {
                if (!critDeliveredByH.contains(evictedId)) {
                    critUniqueLostW++;
                }
            }
            int critCreatedTotal = w.critCreatedAccepted + (int) w.critRejectedSrc;
            int bulkCreatedTotal = w.bulkCreatedAccepted + (int) w.bulkRejectedSrc;
            double critDelivRatio = critCreatedTotal > 0 ? (double) critDelH / critCreatedTotal : 0.0;
            double bulkDelivRatio = bulkCreatedTotal > 0 ? (double) bulkDelH / bulkCreatedTotal : 0.0;
            double critLatMed = critLats.isEmpty() ? -1.0 : computePercentile(critLats, 0.50);
            double critLatP95W = critLats.isEmpty() ? -1.0 : computePercentile(critLats, 0.95);
            double bulkLatMed = bulkLats.isEmpty() ? -1.0 : computePercentile(bulkLats, 0.50);
            double bulkLatP95W = bulkLats.isEmpty() ? -1.0 : computePercentile(bulkLats, 0.95);

            double coverage = w.atRiskNodeSec > 1e-9 ? (w.atRiskThrottledNodeSec / w.atRiskNodeSec) : -1.0;
            double staleNodeSec = w.staleThrottleNodeSec / totalHostsN;
            List<Double> reactVals = new ArrayList<Double>(w.firstAtRiskTime.size());
            int censoredCnt = 0;
            for (Map.Entry<Integer, Double> re : w.firstAtRiskTime.entrySet()) {
                Double tAct = w.firstThrottleAfterRisk.get(re.getKey());
                if (tAct != null) {
                    reactVals.add(Math.max(0.0, tAct - re.getValue()));
                } else {
                    reactVals.add(Math.max(0.0, cutoff - re.getValue()));
                    censoredCnt++;
                }
            }
            double reactMed = reactVals.isEmpty() ? -1.0 : computePercentile(reactVals, 0.50);
            double censoredFrac = reactVals.isEmpty() ? -1.0 : ((double) censoredCnt / reactVals.size());

            String p = "event" + w.label + "_";
            sb.append("\n").append(p).append("crit_created: ").append(critCreatedTotal);
            sb.append("\n").append(p).append(String.format(Locale.US, "crit_deliv: %.4f", critDelivRatio));
            sb.append("\n").append(p).append(String.format(Locale.US, "crit_lat_med_s: %.4f", critLatMed));
            sb.append("\n").append(p).append(String.format(Locale.US, "crit_lat_p95_s: %.4f", critLatP95W));
            sb.append("\n").append(p).append("crit_evicted: ").append(w.critEvictedIds.size());
            sb.append("\n").append(p).append("crit_evicted_acked: ").append(w.critEvictedAckedIds.size());
            sb.append("\n").append(p).append("crit_unique_lost: ").append(critUniqueLostW);
            sb.append("\n").append(p).append("crit_ttl_expired: ").append(w.critTtlIds.size());
            sb.append("\n").append(p).append("crit_rejected_src: ").append(w.critRejectedSrc);

            sb.append("\n").append(p).append("bulk_created: ").append(bulkCreatedTotal);
            sb.append("\n").append(p).append(String.format(Locale.US, "bulk_deliv: %.4f", bulkDelivRatio));
            sb.append("\n").append(p).append(String.format(Locale.US, "bulk_lat_med_s: %.4f", bulkLatMed));
            sb.append("\n").append(p).append(String.format(Locale.US, "bulk_lat_p95_s: %.4f", bulkLatP95W));
            sb.append("\n").append(p).append("bulk_evicted: ").append(w.bulkEvictedIds.size());
            sb.append("\n").append(p).append("bulk_ttl_expired: ").append(w.bulkTtlIds.size());
            sb.append("\n").append(p).append("bulk_rejected_src: ").append(w.bulkRejectedSrc);

            sb.append("\n").append(p).append(String.format(Locale.US, "peak_occ: %.4f", w.peakOcc));
            sb.append("\n").append(p).append(String.format(Locale.US, "nodesec_q90: %.4f", w.nodeSecQ90 / totalHostsN));
            sb.append("\n").append(p).append(String.format(Locale.US, "energy_J: %.4f", w.energyJ));
            sb.append("\n").append(p).append(String.format(Locale.US, "ctrl_tx_J: %.4f", w.ctrlTxJ));
            sb.append("\n").append(p).append("depleted_new: ").append(w.depletedNew);

            sb.append("\n").append(p).append(String.format(Locale.US, "throttle_coverage: %.4f", coverage));
            sb.append("\n").append(p).append(String.format(Locale.US, "stale_throttle_nodesec: %.4f", staleNodeSec));
            sb.append("\n").append(p).append(String.format(Locale.US, "react_med_s: %.4f", reactMed));
            sb.append("\n").append(p).append(String.format(Locale.US, "react_censored_frac: %.4f", censoredFrac));
        }

        if (!this.waveStartTime.isEmpty()) {
            List<String> waveKeys = new ArrayList<String>(this.waveStartTime.keySet());
            Collections.sort(waveKeys, (a, b) -> {
                int cmp = Double.compare(this.waveStartTime.get(a), this.waveStartTime.get(b));
                return (cmp != 0) ? cmp : a.compareTo(b);
            });
            for (String wk : waveKeys) {
                double wStart = this.waveStartTime.get(wk);
                int nTarg = this.waveTargets.containsKey(wk) ? this.waveTargets.get(wk).size() : 0;
                List<Double> offs = this.waveDelivOffsets.get(wk);
                List<Double> sorted = (offs != null) ? new ArrayList<Double>(offs) : Collections.<Double>emptyList();
                if (!sorted.isEmpty()) {
                    Collections.sort(sorted);
                }
                int nDeliv = sorted.size();
                double[] pcts = new double[] { 0.10, 0.25, 0.50, 0.75, 0.90, 0.95 };
                double[] qVals = new double[pcts.length];
                for (int pi = 0; pi < pcts.length; pi++) {
                    int need = Math.max(1, (int) Math.ceil(pcts[pi] * nTarg));
                    qVals[pi] = (nTarg > 0 && nDeliv >= need) ? sorted.get(need - 1) : -1.0;
                }
                StringBuilder bins = new StringBuilder();
                int ptr = 0;
                int denom = Math.max(1, nTarg);
                for (int b = 0; b <= 60; b++) {
                    double limitSec = b * 60.0;
                    while (ptr < nDeliv && sorted.get(ptr) <= limitSec) {
                        ptr++;
                    }
                    if (b > 0) bins.append(',');
                    bins.append(String.format(Locale.US, "%.4f", (double) ptr / denom));
                }
                sb.append(String.format(Locale.US,
                        "\n# WAVE_ADOPTION_CDF\t%s\t%.1f\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%s",
                        wk, wStart, nTarg, nDeliv,
                        qVals[0], qVals[1], qVals[2], qVals[3], qVals[4], qVals[5], bins.toString()));
            }
        }

        write(sb.toString());
        super.done();
    }
}
