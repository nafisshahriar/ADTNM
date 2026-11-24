/*
 * DtnmaAgent.java
 * Composable RFC 9675 / RFC 7575 Autonomic DTN Management Plane Agent.
 *
 * Extracted from monolithic DtnmaRouter so any stock DTN router (Spray-and-Wait,
 * PRoPHETv2, MaxProp, Epidemic) can own an identical management plane by
 * composition and run unmodified declarative SRL rule files, Safety Shield
 * invariants, Shielded CMDP RL, Delayed ACK pruning, and Density-Adaptive Gossip.
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

public class DtnmaAgent {

    private static final int MAX_FWD_CREDIT_CACHE = 256;

    private static final class ForwardedCreditRecord {
        final double[] stateVec;
        final int actionIndex;
        final double forwardTime;

        ForwardedCreditRecord(double[] stateVec, int actionIndex, double forwardTime) {
            this.stateVec = stateVec;
            this.actionIndex = actionIndex;
            this.forwardTime = forwardTime;
        }
    }

    // Owning router contract
    private final DtnmaManagedRouter owner;

    // Configuration & Modes
    protected DtnmaConfig config;
    protected DtnmaRouter.IntelligenceMode intelligenceMode = DtnmaRouter.IntelligenceMode.SHIELDED_CMDP_RL;
    protected boolean gossipEnabled = true;
    protected boolean ackFeedbackEnabled = true;
    protected double rlEpochInterval = 15.0;
    protected long scenarioRngSeed = 0L;

    // DTNMA Subsystems (per-host isolated)
    private EddTelemetrySampler eddSampler;
    private List<StateBasedRule> ruleDatabase;
    private DtnmaSafetyShield safetyShield;
    private ShieldedCmdpRlAgent rlAgent;

    // Decentralized Contact Beacons & Gossip State
    private Map<Integer, DtnmaContactBeacon> neighborBeacons;
    private Map<Integer, Double> gossipLastExchange;

    // Optional Delayed Delivery Receipt (ACK) Caches (LRU bounded)
    private Map<String, Boolean> deliveredAckSet;
    private Map<String, ForwardedCreditRecord> forwardedCreditMap;

    // Audit & Explainability State
    private String activeRuleAri = "ARI://DTNMA/SRL/BOOTSTRAP";
    private String lastExplanation = "INITIAL_STATE";
    private int activeRegime = DtnmaRouter.REGIME_SPARSE_NOMINAL;
    private int lastProposedProfile = 4;
    private int lastExecutedProfile = 4;
    private int lastRlFallbackProfile = 4;
    private int lastSafeMaskBits = 0xFF;
    private boolean lastShieldIntervened = false;
    private String shieldTriggerAri = "ARI://DTNMA/SHIELD/PASS";
    private long[] profileExecCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];
    private double maxBufferOccupancy = 0.0;
    private long criticalCreationRejected = 0L;
    private long bulkCreationRejected = 0L;

    // RFC 9675 In-Band Downlink Manager Control State & Per-Decision CPU Accounting
    private int appliedControlVersion = 0;
    private double lastControlAdoptTime = -1.0;
    private double lastControlCreatedTime = -1.0;
    private String lastControlAri = "NONE";
    private long decisionCpuNanosTotal = 0L;
    private long decisionCpuCount = 0L;

    // Exact Decision-Time Input Snapshot (for deterministic audit replay, RQ4)
    private double lastDecisionTime = -1.0;
    private double lastDecisionQ = 0.0;
    private double lastDecisionCritQ = 0.0;
    private double lastDecisionDq = 0.0;
    private double lastDecisionDensity = 0.0;
    private double lastDecisionSpeed = 0.0;
    private double lastDecisionSoc = 1.0;
    private int lastDecisionActiveConn = 0;
    private int lastDecisionCtrlVer = 0;
    private double lastDecisionShieldEnergyMin = 0.20;
    private double lastDecisionShieldWarnCritQ = 0.35;

    // Dynamic Policy Knobs (actuated by SRL / Shield / RL / Downlink)
    private int currentSprayQuota = DtnmaRouter.DEFAULT_SPARSE_QUOTA;
    private int currentIngressMode = DtnmaRouter.INGRESS_ACCEPT_ALL;
    private double routineTelemetryInterval = 60.0;
    private boolean backpressureEnabled = true;
    private double currentBackpressureDelta = 0.15;
    private double currentFocusMinPred = 0.45;
    private double currentFocusMargin = 0.25;

    // Cached O(1) Critical Occupancy (recomputed only when a critical message enters/exits)
    private double cachedCritOcc = 0.0;
    private boolean critOccDirty = true;

    // Autonomy & RL Epoch Timing
    private double lastAutonomyEvalTime = -1.0;
    private double autonomyEvalInterval = 1.0;
    private double lastRlEpochTime = -1.0;
    private double[] prevEpochState = null;
    private int prevExecutedAction = -1;

    // Local Epoch Telemetry Accumulators
    private int epochDirectDeliveries = 0;
    private int epochAckDeliveries = 0;
    private double epochPredProgressSum = 0.0;
    private double epochCoverageGain = 0.0;
    private int epochRedundantForwards = 0;
    private int epochRelayTransfers = 0;
    private int epochBulkDrops = 0;
    private int epochCritDrops = 0;
    private int epochStarvationCount = 0;
    private int epochActivityCount = 0;

    // Protocol Control Overhead Breakdown
    private long beaconOverheadBytes = 0;
    private long gossipExchanges = 0;
    private long gossipOverheadBytes = 0;
    private long ackExchanges = 0;
    private long ackOverheadBytes = 0;

    /**
     * Prototype constructor reading rules and DTNMA settings.
     */
    public DtnmaAgent(DtnmaManagedRouter owner, Settings s) {
        this.owner = owner;
        Settings dtnmaSettings = new Settings(DtnmaRouter.DTNMA_NS);

        String rFile = null;
        if (s != null && s.contains(DtnmaRouter.RULES_FILE_SETTING)) {
            rFile = s.getSetting(DtnmaRouter.RULES_FILE_SETTING);
        } else if (dtnmaSettings.contains(DtnmaRouter.RULES_FILE_SETTING)) {
            rFile = dtnmaSettings.getSetting(DtnmaRouter.RULES_FILE_SETTING);
        }

        this.ruleDatabase = new ArrayList<StateBasedRule>();
        loadRulesFromFile(rFile);

        this.config = DtnmaConfig.load(s, dtnmaSettings);
        this.intelligenceMode = this.config.intelligenceMode;
        this.gossipEnabled = this.config.gossipEnabled;
        this.ackFeedbackEnabled = this.config.ackFeedbackEnabled;
        this.rlEpochInterval = this.config.rlEpochInterval;

        applyConfiguredBaselineKnobs();

        Settings mmSettings = new Settings("MovementModel");
        if (mmSettings.contains("rngSeed")) {
            try {
                this.scenarioRngSeed = mmSettings.getInt("rngSeed");
            } catch (Exception ignored) {
                this.scenarioRngSeed = 0L;
            }
        }
    }

    /**
     * Per-host replication constructor.
     */
    public DtnmaAgent(DtnmaManagedRouter owner, DtnmaAgent proto) {
        this.owner = owner;
        this.ruleDatabase = new ArrayList<StateBasedRule>(proto.ruleDatabase);
        this.config = new DtnmaConfig(proto.config);
        this.intelligenceMode = proto.intelligenceMode;
        this.gossipEnabled = proto.gossipEnabled;
        this.ackFeedbackEnabled = proto.ackFeedbackEnabled;
        this.rlEpochInterval = proto.rlEpochInterval;
        this.scenarioRngSeed = proto.scenarioRngSeed;

        this.currentSprayQuota = proto.currentSprayQuota;
        this.currentIngressMode = proto.currentIngressMode;
        this.routineTelemetryInterval = proto.routineTelemetryInterval;
        this.backpressureEnabled = proto.backpressureEnabled;
        this.currentBackpressureDelta = proto.currentBackpressureDelta;
        this.currentFocusMinPred = proto.currentFocusMinPred;
        this.currentFocusMargin = proto.currentFocusMargin;
        this.autonomyEvalInterval = proto.autonomyEvalInterval;
        this.activeRegime = proto.activeRegime;
        this.activeRuleAri = proto.activeRuleAri;
        this.lastExplanation = proto.lastExplanation;

        this.neighborBeacons = new HashMap<Integer, DtnmaContactBeacon>();
        this.gossipLastExchange = new HashMap<Integer, Double>();
        this.deliveredAckSet = createLruMap(this.config.ackCacheSize);
        this.forwardedCreditMap = createLruMap(MAX_FWD_CREDIT_CACHE);
        this.profileExecCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];
        this.maxBufferOccupancy = 0.0;
        this.criticalCreationRejected = 0L;
        this.bulkCreationRejected = 0L;
        this.lastRlFallbackProfile = 4;
        this.lastSafeMaskBits = 0xFF;
        this.appliedControlVersion = 0;
        this.lastControlAdoptTime = -1.0;
        this.lastControlCreatedTime = -1.0;
        this.lastControlAri = "NONE";
        this.decisionCpuNanosTotal = 0L;
        this.decisionCpuCount = 0L;
    }

    public void applyConfiguredBaselineKnobs() {
        if (this.config == null) return;
        DtnmaConfig.ManagementMode eff = this.config.getEffectiveManagementMode();
        if (eff == DtnmaConfig.ManagementMode.M0_STATIC
                || eff == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY
                || this.config.openLoopAuditOnly) {
            this.currentSprayQuota = this.config.staticQuota;
            this.currentIngressMode = this.config.staticIngressMode;
            this.backpressureEnabled = this.config.staticBackpressure;
            int mapped = mapQuotaToProfileIndex(
                    this.currentSprayQuota, this.currentIngressMode, this.backpressureEnabled);
            this.lastProposedProfile = mapped;
            this.lastExecutedProfile = mapped;
            this.lastRlFallbackProfile = mapped;
        }
    }

    private static <V> Map<String, V> createLruMap(final int maxSize) {
        return new LinkedHashMap<String, V>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxSize;
            }
        };
    }

    public void ensureSubsystemsInitialized() {
        DTNHost host = owner.getManagedHost();
        if (host == null) return;
        if (this.config == null) this.config = new DtnmaConfig();
        if (this.neighborBeacons == null) this.neighborBeacons = new HashMap<Integer, DtnmaContactBeacon>();
        if (this.gossipLastExchange == null) this.gossipLastExchange = new HashMap<Integer, Double>();
        if (this.deliveredAckSet == null) this.deliveredAckSet = createLruMap(this.config.ackCacheSize);
        if (this.forwardedCreditMap == null) this.forwardedCreditMap = createLruMap(MAX_FWD_CREDIT_CACHE);
        if (this.profileExecCounts == null) this.profileExecCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];

        if (this.eddSampler == null) {
            this.eddSampler = new EddTelemetrySampler(host, owner);
        }
        if (this.safetyShield == null) {
            this.safetyShield = new DtnmaSafetyShield(this.config);
        }
        if (this.rlAgent == null) {
            this.rlAgent = new ShieldedCmdpRlAgent(this.scenarioRngSeed, host.getAddress(), this.config);
        }
    }

    public final void loadRulesFromFile(String rFile) {
        this.ruleDatabase = new ArrayList<StateBasedRule>();
        if (rFile != null && !rFile.trim().isEmpty()) {
            try {
                this.ruleDatabase = DtnmaRuleParser.parseFile(rFile.trim());
            } catch (Exception e) {
                System.err.println("Warning: Failed to load DTNMA rules from " + rFile
                        + " (" + e.getMessage() + "), using defaults.");
                initDefaultRuleDatabase();
            }
        } else {
            initDefaultRuleDatabase();
        }
        DtnmaSafetyShield.sanitizeRules(this.ruleDatabase);
    }

    public final void loadRulesFromString(String ruleText) {
        this.ruleDatabase = new ArrayList<StateBasedRule>();
        if (ruleText != null && !ruleText.trim().isEmpty()) {
            try {
                this.ruleDatabase = DtnmaRuleParser.parseString(ruleText);
            } catch (Exception e) {
                initDefaultRuleDatabase();
            }
        } else {
            initDefaultRuleDatabase();
        }
        DtnmaSafetyShield.sanitizeRules(this.ruleDatabase);
    }

    private void initDefaultRuleDatabase() {
        PredicateExpression pCrit = new PredicateExpression(false);
        pCrit.addClause(EddTelemetrySampler.EDD_ENERGY_SOC, PredicateExpression.Operator.LESS_THAN, 0.20);
        pCrit.addClause(EddTelemetrySampler.EDD_CRIT_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, 0.50);
        StateBasedRule rCrit = new StateBasedRule("ARI://DTNMA/SRL/CRITICAL_CONGESTION",
                "Low energy survival guard or heavy critical telemetry pressure", 100, pCrit);
        rCrit.addAction(ControlAction.setSprayQuota(1));
        rCrit.addAction(ControlAction.setIngressMode(DtnmaRouter.INGRESS_CRITICAL_ONLY));
        rCrit.addAction(ControlAction.setTelemetryInterval(600.0));
        rCrit.addAction(ControlAction.setBackpressure(true));
        this.ruleDatabase.add(rCrit);

        PredicateExpression pSurge = new PredicateExpression(true);
        pSurge.addClause(EddTelemetrySampler.EDD_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, 0.88);
        pSurge.addClause(EddTelemetrySampler.EDD_QUEUE_DRIFT, PredicateExpression.Operator.GREATER_EQUAL, 0.008);
        StateBasedRule rSurge = new StateBasedRule("ARI://DTNMA/SRL/SURGE_THROTTLE",
                "Active positive buffer surge: damp bulk spray temporarily", 80, pSurge);
        rSurge.addAction(ControlAction.setSprayQuota(2));
        rSurge.addAction(ControlAction.setIngressMode(DtnmaRouter.INGRESS_THROTTLE_BULK));
        rSurge.addAction(ControlAction.setTelemetryInterval(300.0));
        rSurge.addAction(ControlAction.setBackpressure(true));
        this.ruleDatabase.add(rSurge);

        PredicateExpression pDense = new PredicateExpression(true);
        pDense.addClause(EddTelemetrySampler.EDD_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, 0.75);
        pDense.addClause(EddTelemetrySampler.EDD_NODE_DENSITY, PredicateExpression.Operator.GREATER_EQUAL, 4.0);
        StateBasedRule rDense = new StateBasedRule("ARI://DTNMA/SRL/DENSE_CLUSTER",
                "Congested dense cluster: Set balanced spray quota to 4 with backpressure", 60, pDense);
        rDense.addAction(ControlAction.setSprayQuota(4));
        rDense.addAction(ControlAction.setIngressMode(DtnmaRouter.INGRESS_ACCEPT_ALL));
        rDense.addAction(ControlAction.setTelemetryInterval(180.0));
        rDense.addAction(ControlAction.setBackpressure(true));
        this.ruleDatabase.add(rDense);

        PredicateExpression pSparse = new PredicateExpression(true);
        pSparse.addClause(EddTelemetrySampler.EDD_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, 0.0);
        StateBasedRule rSparse = new StateBasedRule("ARI://DTNMA/SRL/SPARSE_EXPLORATION",
                "Catch-all nominal release: restore exploratory spray quota 6", 10, pSparse);
        rSparse.addAction(ControlAction.setSprayQuota(DtnmaRouter.DEFAULT_SPARSE_QUOTA));
        rSparse.addAction(ControlAction.setIngressMode(DtnmaRouter.INGRESS_ACCEPT_ALL));
        rSparse.addAction(ControlAction.setTelemetryInterval(60.0));
        rSparse.addAction(ControlAction.setBackpressure(false));
        this.ruleDatabase.add(rSparse);

        Collections.sort(this.ruleDatabase, (r1, r2) -> Integer.compare(r2.getPriority(), r1.getPriority()));
    }

    public DtnmaConfig.ManagementMode getEffectiveManagementMode() {
        return config != null ? config.getEffectiveManagementMode() : DtnmaConfig.ManagementMode.M3_AUTONOMIC_RL;
    }

    public DtnmaConfig.InterventionTier getInterventionTier() {
        return (config != null && config.interventionTier != null)
                ? config.interventionTier : DtnmaConfig.InterventionTier.TIER3_FULL_WITH_REPLICA_CAPS;
    }

    public boolean isCriticalOnlyBulkSheddingActive() {
        return getInterventionTier() != DtnmaConfig.InterventionTier.TIER1_PROTECTION_ONLY
                && currentIngressMode == DtnmaRouter.INGRESS_CRITICAL_ONLY;
    }

    public int getEffectiveBulkSprayQuota(int routerDefaultQuota) {
        return (getInterventionTier() == DtnmaConfig.InterventionTier.TIER3_FULL_WITH_REPLICA_CAPS)
                ? this.currentSprayQuota : routerDefaultQuota;
    }

    public boolean isSafetyLockEnforced() {
        boolean modeProtects = (intelligenceMode != DtnmaRouter.IntelligenceMode.UNSHIELDED_RL);
        return modeProtects && (config == null || config.enablePriorityProtection);
    }

    public boolean isAutonomicControlActive() {
        DtnmaConfig.ManagementMode eff = getEffectiveManagementMode();
        return (eff == DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL
                || eff == DtnmaConfig.ManagementMode.M3_AUTONOMIC_RL)
                && (config == null || !config.openLoopAuditOnly);
    }

    public boolean isEpidemicControlPriority(Message m) {
        return config != null && config.epidemicControlFlooding && DtnmaRouter.isDownlinkControlMessage(m);
    }

    public boolean isAcked(Message m) {
        return ackFeedbackEnabled && deliveredAckSet != null && m != null && deliveredAckSet.containsKey(m.getId());
    }

    public double getEffectiveGossipCooldown() {
        if (config == null) return 120.0;
        if (!config.densityAdaptiveGossip) return config.gossipCooldown;
        double dens = (eddSampler != null) ? eddSampler.getNodeDensity() : 0.0;
        return config.gossipCooldown * Math.max(1.0, dens / 4.0);
    }

    public double getEffectiveBeaconRefreshInterval() {
        if (config == null) return 5.0;
        if (!config.densityAdaptiveGossip) return config.beaconRefreshInterval;
        double dens = (eddSampler != null) ? eddSampler.getNodeDensity() : 0.0;
        return config.beaconRefreshInterval * Math.max(1.0, dens / 5.0);
    }

    // ------------------------------------------------------------------
    // Lifecycle Hooks invoked by any DtnmaManagedRouter owner
    // ------------------------------------------------------------------

    public void onChangedConnection(Connection con) {
        if (con == null || !con.isUp()) return;
        ensureSubsystemsInitialized();
        DTNHost host = owner.getManagedHost();
        if (host == null) return;

        DTNHost other = con.getOtherNode(host);
        if (other == null) return;

        if (this.eddSampler != null) {
            this.eddSampler.recordContact(other);
        }

        if (other.getRouter() instanceof DtnmaManagedRouter) {
            DtnmaManagedRouter peerRouter = (DtnmaManagedRouter) other.getRouter();
            DtnmaAgent peer = peerRouter.getDtnmaAgent();
            if (peer == null) return;
            peer.ensureSubsystemsInitialized();
            double now = SimClock.getTime();

            // 1. Contact Beacon handshake (rate-guarded against ping-pong in dense swarms)
            DtnmaContactBeacon existingBeacon = this.neighborBeacons.get(other.getAddress());
            if (existingBeacon == null || (now - existingBeacon.getTimestamp()) >= 1.0) {
                DtnmaContactBeacon beacon = peer.getBeaconSnapshot(now);
                this.neighborBeacons.put(other.getAddress(), beacon);
                this.beaconOverheadBytes += DtnmaContactBeacon.BEACON_SIZE_BYTES;
            }

            // 2. Density-adaptive Same-Mobility-Class Symmetric Gossip (M3 RL only)
            DtnmaConfig.ManagementMode myMode = getEffectiveManagementMode();
            DtnmaConfig.ManagementMode peerMode = peer.getEffectiveManagementMode();
            if (this.gossipEnabled && this.intelligenceMode != DtnmaRouter.IntelligenceMode.STATIC_RULES
                    && myMode == DtnmaConfig.ManagementMode.M3_AUTONOMIC_RL
                    && peer.gossipEnabled && peer.intelligenceMode != DtnmaRouter.IntelligenceMode.STATIC_RULES
                    && peerMode == DtnmaConfig.ManagementMode.M3_AUTONOMIC_RL
                    && host.getAddress() < other.getAddress()) {
                int myAddr = host.getAddress();
                int peerAddr = other.getAddress();
                double myCooldown = getEffectiveGossipCooldown();
                double peerCooldown = peer.getEffectiveGossipCooldown();
                Double lastSelf = this.gossipLastExchange.get(peerAddr);
                Double lastPeer = peer.gossipLastExchange.get(myAddr);
                boolean readySelf = (lastSelf == null || (now - lastSelf) >= myCooldown);
                boolean readyPeer = (lastPeer == null || (now - lastPeer) >= peerCooldown);
                if (readySelf && readyPeer && this.rlAgent != null && peer.rlAgent != null
                        && this.eddSampler != null && peer.eddSampler != null) {
                    int myClass = this.eddSampler.getMobilityClass();
                    int peerClass = peer.eddSampler.getMobilityClass();
                    if (ShieldedCmdpRlAgent.gossipMergeSymmetric(
                            this.rlAgent, peer.rlAgent, myClass, peerClass)) {
                        this.gossipLastExchange.put(peerAddr, now);
                        this.gossipExchanges++;
                        this.gossipOverheadBytes += DtnmaContactBeacon.GOSSIP_PAYLOAD_BYTES;
                        peer.recordSymGossipExchange(myAddr, now);
                    }
                }
            }

            // 3. Delayed Delivery Receipt (ACK) Exchange
            if (this.ackFeedbackEnabled) {
                exchangeDeliveryAcks(peer);
            }
        }
    }

    private void recordSymGossipExchange(int peerAddr, double now) {
        if (this.gossipLastExchange != null) {
            this.gossipLastExchange.put(peerAddr, now);
        }
        this.gossipExchanges++;
        this.gossipOverheadBytes += DtnmaContactBeacon.GOSSIP_PAYLOAD_BYTES;
    }

    public DtnmaContactBeacon getBeaconSnapshot(double now) {
        ensureSubsystemsInitialized();
        DTNHost host = owner.getManagedHost();
        double qRatio = owner.getBufferOccupancyRatio();
        double drift = eddSampler != null ? eddSampler.getQueueDrift() : 0.0;
        double soc = owner.getEnergySoc();
        double speed = eddSampler != null ? eddSampler.getNodeSpeed() : 0.0;
        int mClass = eddSampler != null ? eddSampler.getMobilityClass() : 1;
        int deg = (host != null) ? host.getConnections().size() : 0;
        int addr = (host != null) ? host.getAddress() : -1;
        return new DtnmaContactBeacon(addr, now, qRatio, drift, soc, speed, mClass, deg);
    }

    public double getBeaconNeighborQueueRatio(DTNHost peer) {
        if (peer == null || neighborBeacons == null) return 0.5;
        DtnmaContactBeacon b = neighborBeacons.get(peer.getAddress());
        return (b != null) ? b.getQueueRatio() : 0.5;
    }

    public double getBeaconNeighborEnergySoc(DTNHost peer) {
        if (peer == null || neighborBeacons == null) return 1.0;
        DtnmaContactBeacon b = neighborBeacons.get(peer.getAddress());
        return (b != null) ? b.getEnergySoc() : 1.0;
    }

    public void refreshStaleBeacons(double now) {
        DTNHost host = owner.getManagedHost();
        if (host == null || neighborBeacons == null) return;
        double refreshInt = getEffectiveBeaconRefreshInterval();
        for (Connection con : owner.getManagedConnections()) {
            DTNHost other = con.getOtherNode(host);
            if (other != null && other.getRouter() instanceof DtnmaManagedRouter) {
                DtnmaContactBeacon existing = neighborBeacons.get(other.getAddress());
                if (existing == null || existing.isStale(now, refreshInt)) {
                    DtnmaAgent peer = ((DtnmaManagedRouter) other.getRouter()).getDtnmaAgent();
                    if (peer != null) {
                        neighborBeacons.put(other.getAddress(), peer.getBeaconSnapshot(now));
                        beaconOverheadBytes += DtnmaContactBeacon.BEACON_SIZE_BYTES;
                    }
                }
            }
        }
    }

    private void exchangeDeliveryAcks(DtnmaAgent peer) {
        if (peer == null || peer.deliveredAckSet == null || peer.deliveredAckSet.isEmpty()) return;
        int learned = 0;
        for (String msgId : peer.deliveredAckSet.keySet()) {
            if (!this.deliveredAckSet.containsKey(msgId)) {
                this.deliveredAckSet.put(msgId, Boolean.TRUE);
                learned++;
                if (this.forwardedCreditMap != null) {
                    ForwardedCreditRecord rec = this.forwardedCreditMap.remove(msgId);
                    if (rec != null) {
                        this.epochAckDeliveries++;
                        this.epochActivityCount++;
                        if (this.rlAgent != null && this.intelligenceMode != DtnmaRouter.IntelligenceMode.STATIC_RULES) {
                            this.rlAgent.applyDelayedAckCredit(
                                    rec.stateVec, rec.actionIndex, this.config.rewardAckDelivery);
                        }
                    }
                }
                if (owner.hasManagedMessage(msgId) && !owner.isManagedSending(msgId)) {
                    owner.deleteManagedMessage(msgId, false);
                }
                if (learned >= this.config.ackMaxPerContact) {
                    break;
                }
            }
        }
        if (learned > 0) {
            this.ackExchanges++;
            this.ackOverheadBytes += (long) learned * DtnmaContactBeacon.ACK_ID_BYTES;
        }
    }

    /**
     * Evaluates autonomic control loop (SRL / Shield / RL) with nanosecond CPU timing.
     */
    public void onBeforeUpdate(double now) {
        ensureSubsystemsInitialized();
        if (lastAutonomyEvalTime < 0 || (now - lastAutonomyEvalTime) >= autonomyEvalInterval) {
            refreshStaleBeacons(now);
            long cpuStart = System.nanoTime();
            evaluateAutonomyEngine(now);
            this.decisionCpuNanosTotal += Math.max(0L, System.nanoTime() - cpuStart);
            this.decisionCpuCount++;
            lastAutonomyEvalTime = now;
        }
    }

    private void evaluateAutonomyEngine(double now) {
        if (eddSampler == null || safetyShield == null) return;
        DTNHost host = owner.getManagedHost();
        eddSampler.sampleNow();

        // Exact decision-time snapshot for deterministic audit replay (RQ4)
        this.lastDecisionTime = now;
        this.lastDecisionQ = owner.getBufferOccupancyRatio();
        this.lastDecisionCritQ = getCachedCriticalOccupancyRatio();
        this.lastDecisionDq = eddSampler.getQueueDrift();
        this.lastDecisionDensity = eddSampler.getNodeDensity();
        this.lastDecisionSpeed = eddSampler.getNodeSpeed();
        this.lastDecisionSoc = owner.getEnergySoc();
        this.lastDecisionActiveConn = (host != null) ? host.getConnections().size() : 0;
        this.lastDecisionCtrlVer = this.appliedControlVersion;
        this.lastDecisionShieldEnergyMin = (this.config != null) ? this.config.shieldEnergyMin : 0.20;
        this.lastDecisionShieldWarnCritQ = (this.config != null) ? this.config.shieldWarnCritQueue : 0.35;

        DtnmaConfig.ManagementMode effMode = getEffectiveManagementMode();

        // M0: Static configuration only (no SRL or RL evaluation)
        if (effMode == DtnmaConfig.ManagementMode.M0_STATIC) {
            int mapped = mapQuotaToProfileIndex(
                    this.currentSprayQuota, this.currentIngressMode, this.backpressureEnabled);
            this.lastProposedProfile = mapped;
            this.lastExecutedProfile = mapped;
            this.lastRlFallbackProfile = mapped;
            this.lastSafeMaskBits = 0xFF;
            this.lastShieldIntervened = false;
            this.activeRuleAri = "ARI://DTNMA/M0/STATIC";
            this.shieldTriggerAri = "ARI://DTNMA/M0/STATIC";
            this.lastExplanation = "M0_STATIC(Quota=" + currentSprayQuota + ",Ingress=" + currentIngressMode + ")";
            if (profileExecCounts != null && mapped >= 0 && mapped < profileExecCounts.length) {
                profileExecCounts[mapped]++;
            }
            return;
        }

        // M1: Remote-only control hold (no local SRL reflex or RL)
        if (effMode == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY) {
            int mapped = mapQuotaToProfileIndex(
                    this.currentSprayQuota, this.currentIngressMode, this.backpressureEnabled);
            this.lastProposedProfile = mapped;
            this.lastExecutedProfile = mapped;
            this.lastRlFallbackProfile = mapped;
            this.lastSafeMaskBits = 0xFF;
            this.lastShieldIntervened = false;
            this.activeRuleAri = "NONE".equals(lastControlAri) ? "ARI://DTNMA/M1/REMOTE_INIT" : lastControlAri;
            this.shieldTriggerAri = "ARI://DTNMA/M1/REMOTE_ONLY";
            this.lastExplanation = "M1_REMOTE_HOLD(Quota=" + currentSprayQuota
                    + ",Ingress=" + currentIngressMode + ",CtrlVer=" + appliedControlVersion + ")";
            if (profileExecCounts != null && mapped >= 0 && mapped < profileExecCounts.length) {
                profileExecCounts[mapped]++;
            }
            return;
        }

        // M2: Autonomic Local SRLs (+ optional Remote Rules)
        if (effMode == DtnmaConfig.ManagementMode.M2_AUTONOMIC_SRL
                || intelligenceMode == DtnmaRouter.IntelligenceMode.STATIC_RULES) {
            evaluateStaticRulesOnly();
            if (config.openLoopAuditOnly) {
                applyConfiguredBaselineKnobs();
            }
            return;
        }

        // M3: Autonomic Shielded CMDP RL (+ Fast 1.0 Hz Safety Guard + SRL invariants)
        boolean runRlEpoch = (lastRlEpochTime < 0 || (now - lastRlEpochTime) >= rlEpochInterval);

        if (runRlEpoch) {
            double[] currentState = eddSampler.getNormalizedStateVector();
            StringBuilder maskReason = new StringBuilder();
            boolean[] nextMask = safetyShield.computeSafeActionMask(eddSampler, ruleDatabase, maskReason);
            boolean useMask = (intelligenceMode != DtnmaRouter.IntelligenceMode.UNSHIELDED_RL);
            double qNow = owner.getBufferOccupancyRatio();
            this.lastSafeMaskBits = useMask ? encodeMaskBits(nextMask) : 0xFF;

            if (prevEpochState != null && prevExecutedAction >= 0 && rlAgent != null) {
                if (epochActivityCount > 0 || qNow >= 0.75 || prevExecutedAction <= 1) {
                    double reward = computeLocalEpochReward(qNow);
                    double cost = computeLocalEpochCost(qNow);
                    rlAgent.updateTransition(prevEpochState, prevExecutedAction, reward, cost,
                            currentState, nextMask, useMask, now);
                }
                resetEpochAccumulators();
            }

            int proposed = (rlAgent != null) ? rlAgent.proposeRawAction(currentState, now) : 4;
            int greedy = (rlAgent != null) ? rlAgent.proposeGreedyAction(currentState) : proposed;
            int executed = proposed;
            int fallbackUsed = proposed;
            boolean intervened = false;
            String trigAri = "ARI://DTNMA/RL/DIRECT";
            String expl = "RL_PROPOSED(" + DtnmaPolicyProfile.get(proposed).getName() + ")";

            if (intelligenceMode != DtnmaRouter.IntelligenceMode.UNSHIELDED_RL) {
                int rlSafeFallback = (rlAgent != null)
                        ? rlAgent.selectBestSafeAction(currentState, nextMask, now, false)
                        : 0;
                fallbackUsed = rlSafeFallback;
                DtnmaSafetyShield.ShieldDecision dec = safetyShield.enforce(
                        proposed, greedy, rlSafeFallback, eddSampler, ruleDatabase);
                executed = dec.getExecutedAction();
                intervened = dec.isIntervened();
                trigAri = intervened ? dec.getTriggerAri() : DtnmaPolicyProfile.get(executed).getAri();
                expl = dec.getExplanation();

                if (intervened && intelligenceMode == DtnmaRouter.IntelligenceMode.SHIELDED_CMDP_RL
                        && rlAgent != null && !config.openLoopAuditOnly) {
                    rlAgent.applyShieldPenaltyUpdate(currentState, proposed, executed);
                }
            }

            this.lastRlFallbackProfile = fallbackUsed;
            if (!config.openLoopAuditOnly) {
                applyPolicyProfile(executed);
            } else {
                applyConfiguredBaselineKnobs();
            }
            if (profileExecCounts != null && executed >= 0 && executed < profileExecCounts.length) {
                profileExecCounts[executed]++;
            }
            updateAuditState(proposed, executed, intervened, trigAri, expl);
            this.prevEpochState = currentState;
            this.prevExecutedAction = executed;
            this.lastRlEpochTime = now;
        } else if (intelligenceMode != DtnmaRouter.IntelligenceMode.UNSHIELDED_RL && prevExecutedAction >= 0) {
            StringBuilder maskReason = new StringBuilder();
            boolean[] safeMask = safetyShield.computeSafeActionMask(eddSampler, ruleDatabase, maskReason);
            this.lastSafeMaskBits = encodeMaskBits(safeMask);
            if (!safeMask[prevExecutedAction]) {
                double[] currentState = eddSampler.getNormalizedStateVector();
                int greedy = (rlAgent != null) ? rlAgent.proposeGreedyAction(currentState) : prevExecutedAction;
                int rlSafeFallback = (rlAgent != null)
                        ? rlAgent.selectBestSafeAction(currentState, safeMask, now, false)
                        : 0;
                this.lastRlFallbackProfile = rlSafeFallback;
                DtnmaSafetyShield.ShieldDecision dec = safetyShield.enforce(
                        prevExecutedAction, greedy, rlSafeFallback, eddSampler, ruleDatabase);
                int safeAct = dec.getExecutedAction();
                if (intelligenceMode == DtnmaRouter.IntelligenceMode.SHIELDED_CMDP_RL
                        && rlAgent != null && !config.openLoopAuditOnly) {
                    rlAgent.applyShieldPenaltyUpdate(currentState, prevExecutedAction, safeAct);
                }
                if (!config.openLoopAuditOnly) {
                    applyPolicyProfile(safeAct);
                }
                if (profileExecCounts != null && safeAct >= 0 && safeAct < profileExecCounts.length) {
                    profileExecCounts[safeAct]++;
                }
                updateAuditState(prevExecutedAction, safeAct, true, dec.getTriggerAri(), dec.getExplanation());
                this.prevExecutedAction = safeAct;
            } else if (lastShieldIntervened && !DtnmaSafetyShield.isConstrained(safeMask) && rlAgent != null) {
                double[] currentState = eddSampler.getNormalizedStateVector();
                int recovered = rlAgent.selectBestSafeAction(currentState, safeMask, now, false);
                this.lastRlFallbackProfile = recovered;
                if (!config.openLoopAuditOnly) {
                    applyPolicyProfile(recovered);
                }
                updateAuditState(recovered, recovered, false,
                        DtnmaPolicyProfile.get(recovered).getAri(),
                        "SHIELD_RELEASE(" + DtnmaPolicyProfile.get(recovered).getName() + ")");
                this.prevExecutedAction = recovered;
            }
        }
    }

    private void evaluateStaticRulesOnly() {
        for (StateBasedRule rule : ruleDatabase) {
            if (rule.isTriggered(eddSampler)) {
                this.activeRuleAri = rule.getAri();
                this.lastExplanation = rule.getLastExplanation();
                this.lastShieldIntervened = false;
                this.shieldTriggerAri = "ARI://DTNMA/SRL/STATIC";
                rule.execute(owner);

                if (rule.getPriority() >= 100) {
                    this.activeRegime = DtnmaRouter.REGIME_CONGESTED_SAFE;
                    this.currentBackpressureDelta = 0.08;
                    this.currentFocusMinPred = 0.45;
                    this.currentFocusMargin = 0.25;
                } else if (rule.getPriority() >= 50) {
                    this.activeRegime = DtnmaRouter.REGIME_DENSE_BALANCED;
                    this.currentBackpressureDelta = 0.15;
                    this.currentFocusMinPred = 0.45;
                    this.currentFocusMargin = 0.25;
                } else {
                    this.activeRegime = DtnmaRouter.REGIME_SPARSE_NOMINAL;
                    this.currentBackpressureDelta = 0.15;
                    this.currentFocusMinPred = 0.45;
                    this.currentFocusMargin = 0.25;
                }
                int mapped = mapQuotaToProfileIndex(
                        this.currentSprayQuota, this.currentIngressMode, this.backpressureEnabled);
                this.lastProposedProfile = mapped;
                this.lastExecutedProfile = mapped;
                this.lastRlFallbackProfile = mapped;
                this.lastSafeMaskBits = 0xFF;
                if (profileExecCounts != null && mapped >= 0 && mapped < profileExecCounts.length) {
                    profileExecCounts[mapped]++;
                }
                break;
            }
        }
    }

    private static int encodeMaskBits(boolean[] mask) {
        if (mask == null) return 0xFF;
        int bits = 0;
        for (int i = 0; i < mask.length && i < 8; i++) {
            if (mask[i]) bits |= (1 << i);
        }
        return bits;
    }

    private static int mapQuotaToProfileIndex(int quota, int ingressMode, boolean backpressure) {
        if (quota <= 1 || ingressMode >= DtnmaRouter.INGRESS_CRITICAL_ONLY) return 0;
        if (quota <= 2 || ingressMode == DtnmaRouter.INGRESS_THROTTLE_BULK) return 1;
        if (quota <= 4) return 2;
        if (quota <= 6) return backpressure ? 3 : 4;
        if (quota <= 8) return 5;
        if (quota <= 10) return 6;
        return 7;
    }

    public void applyPolicyProfile(int profileIndex) {
        DtnmaPolicyProfile prof = DtnmaPolicyProfile.get(profileIndex);
        this.currentSprayQuota = prof.getSprayQuota();
        this.currentBackpressureDelta = prof.getBackpressureDelta();
        this.currentFocusMargin = prof.getFocusPredMargin();
        this.currentFocusMinPred = prof.getFocusMinPred();
        this.currentIngressMode = prof.getIngressMode();
        this.backpressureEnabled = prof.isBackpressureEnabled();
    }

    private void updateAuditState(int proposed, int executed, boolean intervened, String ari, String expl) {
        this.lastProposedProfile = proposed;
        this.lastExecutedProfile = executed;
        this.lastShieldIntervened = intervened;
        this.shieldTriggerAri = ari;
        this.activeRuleAri = ari;
        this.lastExplanation = expl;

        DtnmaPolicyProfile prof = DtnmaPolicyProfile.get(executed);
        double density = eddSampler != null ? eddSampler.getNodeDensity() : 0.0;
        if (prof.getIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY || (intervened && prof.getSprayQuota() <= 1)) {
            this.activeRegime = DtnmaRouter.REGIME_CONGESTED_SAFE;
        } else if (prof.getIngressMode() == DtnmaRouter.INGRESS_THROTTLE_BULK || prof.getSprayQuota() <= 2 || density >= 4.0) {
            this.activeRegime = DtnmaRouter.REGIME_DENSE_BALANCED;
        } else {
            this.activeRegime = DtnmaRouter.REGIME_SPARSE_NOMINAL;
        }
    }

    private double computeLocalEpochReward(double qNow) {
        double critQ = getCachedCriticalOccupancyRatio();
        double soc = owner.getEnergySoc();
        double drift = (eddSampler != null) ? eddSampler.getQueueDrift() : 0.0;
        boolean localHazard = (soc < config.shieldEnergyMin)
                || (critQ >= config.shieldWarnCritQueue)
                || (qNow >= config.shieldSurgeQueue && drift >= config.shieldSurgeDrift);

        double bufPenalty = 0.50 * Math.pow(Math.max(0.0, critQ - 0.20), 2)
                + 0.25 * Math.pow(Math.max(0.0, qNow - 0.90), 2);
        double starvePenalty = 0.35 * Math.min(3, epochStarvationCount);
        if (!localHazard && prevExecutedAction == 0) {
            starvePenalty += 1.50;
        } else if (!localHazard && prevExecutedAction == 1) {
            starvePenalty += 0.45;
        }
        int cappedBulkDrops = Math.min(3, epochBulkDrops);
        return config.rewardPredProgress * epochPredProgressSum
             + config.rewardCoverageGain * epochCoverageGain
             - config.penaltyBulkDrop * cappedBulkDrops
             - 2.5 * epochCritDrops
             - 0.20 * epochRedundantForwards
             - config.penaltyRelayCost * epochRelayTransfers
             - bufPenalty
             - starvePenalty;
    }

    private double computeLocalEpochCost(double qNow) {
        double dropCost = 0.15 * epochBulkDrops + 1.0 * epochCritDrops;
        double bufCost = 0.80 * Math.max(0.0, qNow - 0.90) / 0.10;
        double redCost = 0.20 * epochRedundantForwards + 0.01 * epochRelayTransfers;
        return Math.min(2.0, dropCost + bufCost + redCost);
    }

    private void resetEpochAccumulators() {
        this.epochDirectDeliveries = 0;
        this.epochAckDeliveries = 0;
        this.epochPredProgressSum = 0.0;
        this.epochCoverageGain = 0.0;
        this.epochRedundantForwards = 0;
        this.epochRelayTransfers = 0;
        this.epochBulkDrops = 0;
        this.epochCritDrops = 0;
        this.epochStarvationCount = 0;
        this.epochActivityCount = 0;
    }

    // ------------------------------------------------------------------
    // Admission, Buffer Protection, Eviction & Transfer Lifecycle
    // ------------------------------------------------------------------

    public boolean prepareRoomForNewMessage(Message msg, String copiesPropKey) {
        ActiveRouter ar = owner.getManagedActiveRouter();
        boolean isCrit = DtnmaRouter.isCriticalMessage(msg);
        long reserved = DtnmaRouter.computeReservedIncomingBytes(
                owner.getManagedHost(), owner.getManagedConnections(), owner.getManagedSendingConnections());
        long neededLong = (long) msg.getSize() + reserved;
        if (neededLong > owner.getManagedBufferSize()) {
            recordCreationRejected(isCrit);
            return false;
        }
        int needed = (int) neededLong;
        if (!ar.makeRoomForMessage(needed)) {
            boolean allowEmerg = isCrit && isSafetyLockEnforced();
            if (!allowEmerg || !reclaimCriticalEmergency(copiesPropKey, needed)) {
                recordCreationRejected(isCrit);
                return false;
            }
        }
        return true;
    }

    private void recordCreationRejected(boolean isCrit) {
        if (isCrit) {
            this.criticalCreationRejected++;
        } else {
            this.bulkCreationRejected++;
        }
    }

    public boolean reclaimCriticalEmergency(String copiesPropKey, int size) {
        Map<String, Boolean> ackMap = this.ackFeedbackEnabled ? this.deliveredAckSet : null;
        return DtnmaRouter.reclaimCriticalEmergency(
                owner.getManagedActiveRouter(), copiesPropKey, ackMap, size);
    }

    public void markCritOccDirty() {
        this.critOccDirty = true;
    }

    public double getCachedCriticalOccupancyRatio() {
        if (this.critOccDirty) {
            this.cachedCritOcc = owner.getCriticalOccupancyRatio();
            this.critOccDirty = false;
        }
        return this.cachedCritOcc;
    }

    public void onAfterCreateNewMessage(Message msg, int initialCopies) {
        boolean isCrit = DtnmaRouter.isCriticalMessage(msg);
        if (isCrit) {
            this.critOccDirty = true;
        }
        double qNow = owner.getBufferOccupancyRatio();
        if (!isCrit && qNow < 0.72) {
            this.epochCoverageGain += 0.50 * (1.0 - qNow) * Math.sqrt(Math.max(1, initialCopies));
        }
        if (!isCrit && this.ackFeedbackEnabled && this.forwardedCreditMap != null && eddSampler != null) {
            this.forwardedCreditMap.put(msg.getId(), new ForwardedCreditRecord(
                    eddSampler.getNormalizedStateVector(), this.lastExecutedProfile, SimClock.getTime()));
        }
        this.epochActivityCount++;
        this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, qNow);
    }

    public void onDeleteMessage(Message m, boolean drop) {
        if (m != null && DtnmaRouter.isCriticalMessage(m)) {
            this.critOccDirty = true;
        }
        if (drop && m != null && m.getTtl() > 0) {
            this.epochActivityCount++;
            if (DtnmaRouter.isCriticalMessage(m)) {
                this.epochCritDrops++;
            } else {
                this.epochBulkDrops++;
            }
        }
    }

    /**
     * Returns a MessageRouter denial code, or null if management approves passing to super.checkReceiving().
     */
    public Integer evaluateCheckReceiving(Message m, DTNHost from, String copiesPropKey) {
        if (this.ackFeedbackEnabled && this.deliveredAckSet != null && this.deliveredAckSet.containsKey(m.getId())) {
            return MessageRouter.DENIED_OLD;
        }

        boolean isCrit = DtnmaRouter.isCriticalMessage(m);
        if (isSafetyLockEnforced() && isCrit) {
            return null;
        }

        if (getInterventionTier() == DtnmaConfig.InterventionTier.TIER1_PROTECTION_ONLY) {
            return null;
        }

        if (isCrit || currentIngressMode == DtnmaRouter.INGRESS_ACCEPT_ALL) {
            return null;
        }

        double occ = owner.getBufferOccupancyRatio();
        double critOcc = getCachedCriticalOccupancyRatio();
        Integer cProp = (copiesPropKey != null) ? (Integer) m.getProperty(copiesPropKey) : null;
        boolean singleCopyBulk = (cProp != null && cProp <= 1);
        int msgTtl = owner.getManagedMsgTtl();
        boolean finiteTtl = (msgTtl > 0 && msgTtl != Message.INFINITE_TTL);

        if (currentIngressMode == DtnmaRouter.INGRESS_CRITICAL_ONLY) {
            if (critOcc >= 0.40
                    || (occ > 0.92 && (singleCopyBulk || (finiteTtl && m.getTtl() < 0.75 * msgTtl)))
                    || (isAutonomicControlActive() && owner.getEnergySoc() < config.shieldEnergyMin)) {
                return MessageRouter.DENIED_NO_SPACE;
            }
        } else if (currentIngressMode == DtnmaRouter.INGRESS_THROTTLE_BULK) {
            if (critOcc >= 0.55
                    || (occ > 0.94 && (singleCopyBulk || (finiteTtl && m.getTtl() < 0.50 * msgTtl)))) {
                return MessageRouter.DENIED_NO_SPACE;
            }
        }
        return null;
    }

    /**
     * Protects un-ACKed critical messages under safety lock, evicting ACKed copies first
     * and otherwise picking worst bulk candidate by tieBreaker (null = FIFO oldest receiveTime).
     */
    public Message selectNextMessageToRemove(boolean excludeMsgBeingSent, Comparator<Message> bulkTieBreaker) {
        Collection<Message> messages = owner.getManagedMessageCollection();
        boolean enforceSafetyLock = isSafetyLockEnforced();

        if (this.ackFeedbackEnabled && this.deliveredAckSet != null && !this.deliveredAckSet.isEmpty()) {
            for (Message m : messages) {
                if (excludeMsgBeingSent && owner.isManagedSending(m.getId())) continue;
                if (this.deliveredAckSet.containsKey(m.getId())) {
                    return m;
                }
            }
        }

        Message candidate = null;
        for (Message m : messages) {
            if (excludeMsgBeingSent && owner.isManagedSending(m.getId())) continue;
            if (enforceSafetyLock && DtnmaRouter.isCriticalMessage(m)) continue;

            if (candidate == null) {
                candidate = m;
            } else if (bulkTieBreaker != null) {
                if (bulkTieBreaker.compare(m, candidate) > 0) {
                    candidate = m;
                }
            } else if (m.getReceiveTime() < candidate.getReceiveTime()) {
                candidate = m;
            }
        }
        return candidate;
    }

    public void onMessageTransferred(Message m, DTNHost from, String copiesPropKey) {
        if (m == null) return;
        if (DtnmaRouter.isCriticalMessage(m)) {
            this.critOccDirty = true;
        }
        this.epochActivityCount++;
        Map<String, Boolean> ackMap = this.ackFeedbackEnabled ? this.deliveredAckSet : null;
        DtnmaRouter.clampPostTransferDeficit(owner.getManagedActiveRouter(), m, copiesPropKey, ackMap);
        this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, owner.getBufferOccupancyRatio());
        if (DtnmaRouter.isGroupControlMessage(m)) {
            applyDownlinkControlBundle(m);
        } else if (m.getTo() == owner.getManagedHost()) {
            if (this.deliveredAckSet != null) {
                this.deliveredAckSet.put(m.getId(), Boolean.TRUE);
            }
            if (DtnmaRouter.isDownlinkControlMessage(m)) {
                applyDownlinkControlBundle(m);
            }
        }
    }

    public void onTransferDone(Connection con, Message m, String copiesPropKey, int actualSentCopies) {
        if (m == null) return;
        this.epochActivityCount++;
        DTNHost host = owner.getManagedHost();
        DTNHost recipient = (con != null && host != null) ? con.getOtherNode(host) : null;

        if (m.getTo() == recipient && !DtnmaRouter.isGroupControlMessage(m)) {
            this.epochDirectDeliveries++;
            if (this.deliveredAckSet != null) {
                this.deliveredAckSet.put(m.getId(), Boolean.TRUE);
            }
            return;
        }
        this.epochRelayTransfers++;

        if (eddSampler != null && recipient != null) {
            double myPred = eddSampler.getPredFor(m.getTo());
            double peerPred = 0.0;
            if (recipient.getRouter() instanceof DtnmaManagedRouter) {
                DtnmaAgent peerAgent = ((DtnmaManagedRouter) recipient.getRouter()).getDtnmaAgent();
                if (peerAgent != null && peerAgent.getEddSampler() != null) {
                    peerPred = peerAgent.getEddSampler().getPredFor(m.getTo());
                }
            } else if (recipient.getRouter() instanceof ProphetRouter) {
                peerPred = ((ProphetRouter) recipient.getRouter()).getPredFor(m.getTo());
            } else if (recipient.getRouter() instanceof ProphetV2Router) {
                peerPred = ((ProphetV2Router) recipient.getRouter()).getPredFor(m.getTo());
            }

            double deltaP = Math.max(0.0, peerPred - myPred);
            double peerQ = getBeaconNeighborQueueRatio(recipient);

            if (peerQ < 0.92) {
                this.epochCoverageGain += 0.35 * (1.0 - 0.50 * peerQ) * Math.sqrt(Math.max(1, actualSentCopies));
            } else if (!DtnmaRouter.isCriticalMessage(m) && deltaP <= 0.02) {
                this.epochRedundantForwards++;
            }
            if (deltaP > 0.05) {
                this.epochPredProgressSum += deltaP;
            }
        }

        if (this.ackFeedbackEnabled && this.forwardedCreditMap != null
                && !DtnmaRouter.isCriticalMessage(m) && eddSampler != null) {
            this.forwardedCreditMap.put(m.getId(), new ForwardedCreditRecord(
                    eddSampler.getNormalizedStateVector(), this.lastExecutedProfile, SimClock.getTime()));
        }
    }

    /**
     * Common non-direct bulk forwarding governor for wrapped stock routers
     * (PRoPHETv2, MaxProp, Epidemic). Critical/control messages always return true.
     */
    public boolean allowNonDirectBulkForward(Message m, DTNHost peer, double myPred, double peerPred) {
        if (m == null || peer == null) return false;
        return allowNonDirectBulkForward(
                m,
                owner.getBufferOccupancyRatio(),
                getBeaconNeighborQueueRatio(peer),
                getBeaconNeighborEnergySoc(peer),
                peer.getRouter() instanceof DtnmaManagedRouter,
                myPred,
                peerPred);
    }

    /**
     * Zero-lookup inner-loop overload where callers hoist myQ, peerQ, peerSoc, and peerIsManaged
     * outside per-message loops.
     */
    public boolean allowNonDirectBulkForward(
            Message m, double myQ, double peerQ, double peerSoc, boolean peerIsManaged,
            double myPred, double peerPred) {
        if (m == null) return false;
        if (DtnmaRouter.isCriticalMessage(m) || DtnmaRouter.isDownlinkControlMessage(m)) {
            return true;
        }
        if (isAcked(m)) {
            return false;
        }
        DtnmaConfig.InterventionTier tier = getInterventionTier();
        if (tier == DtnmaConfig.InterventionTier.TIER1_PROTECTION_ONLY) {
            return true;
        }
        if (currentIngressMode == DtnmaRouter.INGRESS_CRITICAL_ONLY) {
            return false;
        }
        if (peerIsManaged) {
            if (isAutonomicControlActive() && (peerQ >= 0.94 || peerSoc < config.shieldEnergyMin)) {
                return false;
            }
            if (backpressureEnabled && peerQ >= 0.72 && peerQ >= myQ + currentBackpressureDelta) {
                return false;
            }
        }
        if (tier == DtnmaConfig.InterventionTier.TIER3_FULL_WITH_REPLICA_CAPS
                && currentIngressMode == DtnmaRouter.INGRESS_THROTTLE_BULK) {
            if (peerQ >= 0.85) {
                return false;
            }
            if (myPred >= 0.0 && peerPred >= 0.0) {
                double reqGain = Math.max(0.05, 0.25 / Math.max(1, currentSprayQuota));
                if (peerPred < myPred + reqGain || peerPred < 0.12) {
                    return false;
                }
            } else if (m.getHopCount() >= Math.max(1, currentSprayQuota)) {
                return false;
            }
        }
        return true;
    }

    public boolean isPeerSelectableForBulk(double myQ, double peerQ, double peerSoc, boolean peerIsManaged) {
        DtnmaConfig.InterventionTier tier = getInterventionTier();
        if (tier == DtnmaConfig.InterventionTier.TIER1_PROTECTION_ONLY) {
            return true;
        }
        if (currentIngressMode == DtnmaRouter.INGRESS_CRITICAL_ONLY) {
            return false;
        }
        if (peerIsManaged) {
            if (isAutonomicControlActive() && (peerQ >= 0.94 || peerSoc < config.shieldEnergyMin)) {
                return false;
            }
            if (backpressureEnabled && peerQ >= 0.72 && peerQ >= myQ + currentBackpressureDelta) {
                return false;
            }
        }
        if (tier == DtnmaConfig.InterventionTier.TIER3_FULL_WITH_REPLICA_CAPS
                && currentIngressMode == DtnmaRouter.INGRESS_THROTTLE_BULK && peerQ >= 0.85) {
            return false;
        }
        return true;
    }

    public void recordStarvationCheck(double peerQueueRatio, int toSend, int naturalSplit) {
        boolean needlessThrottle = (currentIngressMode > DtnmaRouter.INGRESS_ACCEPT_ALL)
                && getCachedCriticalOccupancyRatio() < config.shieldWarnCritQueue
                && owner.getEnergySoc() >= config.shieldEnergyMin
                && (eddSampler == null || eddSampler.getQueueDrift() < config.shieldSurgeDrift);
        if ((owner.getBufferOccupancyRatio() < 0.65 && peerQueueRatio < 0.65 && toSend < naturalSplit)
                || (needlessThrottle && toSend < naturalSplit)) {
            this.epochStarvationCount++;
            this.epochActivityCount++;
        }
    }

    // ------------------------------------------------------------------
    // RFC 9675 In-Band Downlink Manager Control Adoption (M1 / M2 / M3)
    // ------------------------------------------------------------------

    public void applyDownlinkControlBundle(Message m) {
        if (m == null || m.getId() == null) return;
        String id = m.getId();
        double now = SimClock.getTime();
        DtnmaConfig.ManagementMode effMode = getEffectiveManagementMode();
        boolean mutateAllowed = owner.supportsDynamicControlReconfig()
                && effMode != DtnmaConfig.ManagementMode.M0_STATIC
                && (config == null || !config.openLoopAuditOnly);

        if (id.startsWith("CTRL_UPDATE_V1")) {
            if (this.appliedControlVersion < 1 || m.getCreationTime() > this.lastControlCreatedTime) {
                this.appliedControlVersion = Math.max(1, this.appliedControlVersion);
                this.lastControlAdoptTime = now;
                this.lastControlCreatedTime = Math.max(this.lastControlCreatedTime, m.getCreationTime());
                this.lastControlAri = "ARI://DTNMA/CTRL/DOWNLINK_V1_ENERGY_GUARD";
                if (mutateAllowed) {
                    if (effMode == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY) {
                        applyPolicyProfile(1);
                    } else {
                        this.config.shieldEnergyMin = Math.max(this.config.shieldEnergyMin, 0.25);
                        this.config.shieldWarnCritQueue = Math.min(this.config.shieldWarnCritQueue, 0.30);
                        upsertDownlinkOverlayRule(
                                "ARI://DTNMA/SRL/DOWNLINK_V1_ENERGY_GUARD",
                                "Manager-pushed V1 Energy and Critical Guard",
                                110, 0.25, 0.40, 1, DtnmaRouter.INGRESS_CRITICAL_ONLY);
                    }
                    this.activeRuleAri = this.lastControlAri;
                    this.lastExplanation = "DOWNLINK_CTRL_ADOPTED(V1:SoC>=0.25,Q>=0.90)";
                }
            }
        } else if (id.startsWith("CTRL_UPDATE_V2")) {
            if (this.appliedControlVersion < 2 || m.getCreationTime() > this.lastControlCreatedTime) {
                this.appliedControlVersion = Math.max(2, this.appliedControlVersion);
                this.lastControlAdoptTime = now;
                this.lastControlCreatedTime = Math.max(this.lastControlCreatedTime, m.getCreationTime());
                this.lastControlAri = "ARI://DTNMA/CTRL/DOWNLINK_V2_RECOVERY";
                if (mutateAllowed) {
                    if (effMode == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY) {
                        applyConfiguredBaselineKnobs();
                    } else {
                        this.config.shieldEnergyMin = 0.20;
                        this.config.shieldWarnCritQueue = 0.35;
                        this.config.shieldCongestionQueue = 0.90;
                        removeDownlinkOverlayRules();
                    }
                    this.activeRuleAri = this.lastControlAri;
                    this.lastExplanation = "DOWNLINK_CTRL_ADOPTED(V2:NOMINAL_RECOVERY)";
                }
            }
        } else if (id.contains("CTRL_UPDATE_THROTTLE") || id.contains("CTRL_GROUP_THROTTLE")) {
            if (m.getCreationTime() > this.lastControlCreatedTime || this.appliedControlVersion < 1) {
                this.appliedControlVersion++;
                this.lastControlAdoptTime = now;
                this.lastControlCreatedTime = Math.max(this.lastControlCreatedTime, m.getCreationTime());
                boolean severe = id.contains("SEVERE") || id.contains("EMERGENCY") || id.contains("BLACKOUT");
                this.lastControlAri = severe
                        ? "ARI://DTNMA/CTRL/DOWNLINK_THROTTLE_SEVERE"
                        : "ARI://DTNMA/CTRL/DOWNLINK_THROTTLE";
                if (mutateAllowed) {
                    if (effMode == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY) {
                        applyPolicyProfile(severe ? 0 : 1);
                    } else {
                        this.config.shieldWarnCritQueue = Math.min(this.config.shieldWarnCritQueue, 0.30);
                        upsertDownlinkOverlayRule(
                                "ARI://DTNMA/SRL/DOWNLINK_THROTTLE",
                                "Manager-pushed burst hazard guard",
                                110, 0.25, 0.35,
                                severe ? 1 : 2,
                                severe ? DtnmaRouter.INGRESS_CRITICAL_ONLY : DtnmaRouter.INGRESS_THROTTLE_BULK);
                    }
                    this.activeRuleAri = this.lastControlAri;
                    this.lastExplanation = "DOWNLINK_CTRL_ADOPTED(THROTTLE)";
                }
            }
        } else if (id.contains("CTRL_UPDATE_NOMINAL") || id.contains("CTRL_GROUP_NOMINAL")) {
            if (m.getCreationTime() > this.lastControlCreatedTime) {
                this.appliedControlVersion++;
                this.lastControlAdoptTime = now;
                this.lastControlCreatedTime = Math.max(this.lastControlCreatedTime, m.getCreationTime());
                this.lastControlAri = "ARI://DTNMA/CTRL/DOWNLINK_NOMINAL";
                if (mutateAllowed) {
                    if (effMode == DtnmaConfig.ManagementMode.M1_REMOTE_ONLY) {
                        applyConfiguredBaselineKnobs();
                    } else {
                        this.config.shieldEnergyMin = 0.20;
                        this.config.shieldWarnCritQueue = 0.35;
                        this.config.shieldCongestionQueue = 0.90;
                        removeDownlinkOverlayRules();
                    }
                    this.activeRuleAri = this.lastControlAri;
                    this.lastExplanation = "DOWNLINK_CTRL_ADOPTED(NOMINAL)";
                }
            }
        } else if (id.startsWith("CTRL_UPDATE")) {
            if (this.appliedControlVersion < 1) {
                this.appliedControlVersion = 1;
                this.lastControlAdoptTime = now;
                this.lastControlCreatedTime = Math.max(this.lastControlCreatedTime, m.getCreationTime());
                this.lastControlAri = "ARI://DTNMA/CTRL/DOWNLINK_GENERIC";
            }
        }
    }

    private void upsertDownlinkOverlayRule(String ari, String desc, int priority,
                                           double energyMin, double critQueueMin,
                                           int targetQuota, int targetIngress) {
        removeDownlinkOverlayRules();
        PredicateExpression pred = new PredicateExpression(false);
        pred.addClause(EddTelemetrySampler.EDD_ENERGY_SOC, PredicateExpression.Operator.LESS_THAN, energyMin);
        pred.addClause(EddTelemetrySampler.EDD_CRIT_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, critQueueMin);
        StateBasedRule overlay = new StateBasedRule(ari, desc, priority, pred);
        overlay.addAction(ControlAction.setSprayQuota(targetQuota));
        overlay.addAction(ControlAction.setIngressMode(targetIngress));
        overlay.addAction(ControlAction.setTelemetryInterval(600.0));
        overlay.addAction(ControlAction.setBackpressure(true));
        this.ruleDatabase.add(overlay);
        DtnmaSafetyShield.sanitizeRules(this.ruleDatabase);
        Collections.sort(this.ruleDatabase, (r1, r2) -> Integer.compare(r2.getPriority(), r1.getPriority()));
    }

    private void removeDownlinkOverlayRules() {
        if (this.ruleDatabase == null) return;
        this.ruleDatabase.removeIf(r -> r != null && r.getAri() != null && r.getAri().startsWith("ARI://DTNMA/SRL/DOWNLINK_"));
    }

    // ------------------------------------------------------------------
    // Actuators & Getters
    // ------------------------------------------------------------------

    public void setDynamicSprayQuota(int quota) { this.currentSprayQuota = Math.max(1, quota); }
    public void setIngressMode(int mode) { this.currentIngressMode = mode; }
    public void setRoutineTelemetryInterval(double intervalSec) { this.routineTelemetryInterval = Math.max(1.0, intervalSec); }
    public void setBackpressureEnabled(boolean enabled) { this.backpressureEnabled = enabled; }

    public int getLastActionIndex() { return this.activeRegime; }

    public String getActionDescription(int action) {
        switch (action) {
            case DtnmaRouter.REGIME_SPARSE_NOMINAL: return "SPARSE_NOMINAL(Quota=" + currentSprayQuota + ")";
            case DtnmaRouter.REGIME_DENSE_BALANCED: return "DENSE_BALANCED(Quota=" + currentSprayQuota + ")";
            case DtnmaRouter.REGIME_CONGESTED_SAFE: return "CONGESTED_SAFE(Quota=" + currentSprayQuota + ")";
            default: return "REGIME_" + action;
        }
    }

    public String getActiveRuleAri() { return this.activeRuleAri; }
    public String getLastExplanation() { return this.lastExplanation; }
    public EddTelemetrySampler getEddSampler() { return this.eddSampler; }
    public DtnmaRouter.IntelligenceMode getIntelligenceMode() { return intelligenceMode; }
    public boolean isGossipEnabled() { return gossipEnabled; }
    public boolean isAckFeedbackEnabled() { return ackFeedbackEnabled; }
    public int getLastProposedProfile() { return lastProposedProfile; }
    public int getLastExecutedProfile() { return lastExecutedProfile; }
    public boolean isLastShieldIntervened() { return lastShieldIntervened; }
    public String getShieldTriggerAri() { return shieldTriggerAri; }
    public DtnmaSafetyShield getSafetyShield() { return safetyShield; }
    public ShieldedCmdpRlAgent getRlAgent() { return rlAgent; }
    public long getBeaconOverheadBytes() { return beaconOverheadBytes; }
    public long getGossipExchanges() { return gossipExchanges; }
    public long getGossipOverheadBytes() { return gossipOverheadBytes; }
    public long getAckExchanges() { return ackExchanges; }
    public long getAckOverheadBytes() { return ackOverheadBytes; }
    public double getMaxBufferOccupancy() { return maxBufferOccupancy; }
    public void updateMaxBufferOccupancy(double occ) { this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, occ); }
    public long getCriticalCreationRejected() { return criticalCreationRejected; }
    public long getBulkCreationRejected() { return bulkCreationRejected; }
    public long getLocalCreationRejected() { return criticalCreationRejected + bulkCreationRejected; }
    public long[] getProfileExecCounts() { return profileExecCounts; }
    public int getCurrentSprayQuota() { return currentSprayQuota; }
    public int getCurrentIngressMode() { return currentIngressMode; }
    public boolean isBackpressureEnabled() { return backpressureEnabled; }
    public double getCurrentBackpressureDelta() { return currentBackpressureDelta; }
    public double getCurrentFocusMinPred() { return currentFocusMinPred; }
    public double getCurrentFocusMargin() { return currentFocusMargin; }
    public List<StateBasedRule> getRuleDatabase() { return Collections.unmodifiableList(ruleDatabase); }
    public int getLastRlFallbackProfile() { return lastRlFallbackProfile; }
    public int getLastSafeMaskBits() { return lastSafeMaskBits; }
    public int getAppliedControlVersion() { return appliedControlVersion; }
    public double getLastControlAdoptTime() { return lastControlAdoptTime; }
    public String getLastControlAri() { return lastControlAri; }
    public long getDecisionCpuNanosTotal() { return decisionCpuNanosTotal; }
    public long getDecisionCpuCount() { return decisionCpuCount; }
    public double getLastDecisionTime() { return lastDecisionTime; }
    public double getLastDecisionQ() { return lastDecisionQ; }
    public double getLastDecisionCritQ() { return lastDecisionCritQ; }
    public double getLastDecisionDq() { return lastDecisionDq; }
    public double getLastDecisionDensity() { return lastDecisionDensity; }
    public double getLastDecisionSpeed() { return lastDecisionSpeed; }
    public double getLastDecisionSoc() { return lastDecisionSoc; }
    public int getLastDecisionActiveConn() { return lastDecisionActiveConn; }
    public int getLastDecisionCtrlVer() { return lastDecisionCtrlVer; }
    public double getLastDecisionShieldEnergyMin() { return lastDecisionShieldEnergyMin; }
    public double getLastDecisionShieldWarnCritQ() { return lastDecisionShieldWarnCritQ; }
    public DtnmaConfig getConfig() { return config; }
    public Map<String, Boolean> getDeliveredAckSet() { return deliveredAckSet; }
}
