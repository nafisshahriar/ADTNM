/*
 * DtnmaRouter.java
 * Phase 1 Autonomic DTNMA Router implementing RFC 9675-inspired declarative policy
 * management & RFC 7575 autonomic closed-loop principles fused with Two-Tier
 * Shielded CMDP Reinforcement Learning & Opportunistic Peer Gossip.
 *
 * Key Architecture & Ablation Support:
 * 1. Strongly-typed EDD Telemetry Sampler with EWMA smoothing & normalized Energy SoC.
 * 2. Decentralized local-only & explicit contact-handshake neighbor state via DtnmaContactBeacon.
 * 3. Primal-Dual Constrained Linear Expected SARSA RL Agent + Mobility-Class Gossip.
 * 4. Pre-Action & Post-Action Safety Shield with Hazard-Localized Counterfactual Shaping.
 * 5. Event-Gated SMDP transitions with local coverage utility & causal delayed-ACK credit.
 * 6. Optional Delayed Delivery ACK Feedback ("Vaccine" copy pruning & causal credit).
 * 7. Dual-Tier HoL Priority Scheduling & Physical-Cap Zero-Drop Protection (when shielded/static).
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
import util.Tuple;

public class DtnmaRouter extends ActiveRouter {

    public enum IntelligenceMode {
        STATIC_RULES,
        UNSHIELDED_RL,
        SHIELDED_RL_PASSIVE,
        SHIELDED_CMDP_RL
    }

    public static final String DTNMA_NS = "DtnmaRouter";
    public static final String MSG_COPIES_PROPERTY = "Dtnma_Copies";
    public static final String MSG_SPLIT_PROPERTY = "Dtnma_PendingSplit";

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

    // Ingress Control Modes
    public static final int INGRESS_ACCEPT_ALL = 0;
    public static final int INGRESS_THROTTLE_BULK = 1;
    public static final int INGRESS_CRITICAL_ONLY = 2;

    // Action / Regime States for Reporting & Analytics (backward-compatible with DtnmaDecisionReport)
    public static final int REGIME_SPARSE_NOMINAL = 0;
    public static final int REGIME_DENSE_BALANCED = 1;
    public static final int REGIME_CONGESTED_SAFE = 2;

    // Default Policy Bounds
    public static final int DEFAULT_SPARSE_QUOTA = 6;
    public static final int DEFAULT_DENSE_QUOTA = 2;
    public static final int DEFAULT_CRITICAL_QUOTA = 8;

    // Settings Identifiers
    public static final String RULES_FILE_SETTING = "rulesFile";
    public static final String INTELLIGENCE_MODE_SETTING = "intelligenceMode";
    public static final String GOSSIP_ENABLED_SETTING = "gossipEnabled";
    public static final String ACK_FEEDBACK_SETTING = "ackFeedbackEnabled";
    public static final String RL_EPOCH_INTERVAL_SETTING = "rlEpochInterval";

    // Control Plane Bounds
    private static final int MAX_FWD_CREDIT_CACHE = 256;

    // Configuration Fields (Default: Gossip + Delayed ACK Shielded CMDP RL)
    protected DtnmaConfig config;
    protected IntelligenceMode intelligenceMode = IntelligenceMode.SHIELDED_CMDP_RL;
    protected boolean gossipEnabled = true;
    protected boolean ackFeedbackEnabled = true;
    protected double rlEpochInterval = 15.0;
    protected long scenarioRngSeed = 0L;

    // DTNMA Subsystems (per-host isolated)
    private EddTelemetrySampler eddSampler;
    private List<StateBasedRule> ruleDatabase;
    private DtnmaSafetyShield safetyShield;
    private ShieldedCmdpRlAgent rlAgent;

    // Normalized Energy Reference (captured on host instantiation)
    private double initialEnergy = 1.0;

    // Decentralized Contact Beacons & Gossip State
    private Map<Integer, DtnmaContactBeacon> neighborBeacons;
    private Map<Integer, Double> gossipLastExchange;

    // Optional Delayed Delivery Receipt (ACK) Caches (LRU bounded)
    private Map<String, Boolean> deliveredAckSet;
    private Map<String, ForwardedCreditRecord> forwardedCreditMap;

    // Audit & Explainability State (defaults to healthy Profile 4: STANDARD_SPRAY_FOCUS, L=6)
    private String activeRuleAri = "ARI://DTNMA/SRL/BOOTSTRAP";
    private String lastExplanation = "INITIAL_STATE";
    private int activeRegime = REGIME_SPARSE_NOMINAL;
    private int lastProposedProfile = 4;
    private int lastExecutedProfile = 4;
    private boolean lastShieldIntervened = false;
    private String shieldTriggerAri = "ARI://DTNMA/SHIELD/PASS";
    private long[] profileExecCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];
    private double maxBufferOccupancy = 0.0;

    // Dynamic Policy Variables (Adjusted via Profile / Control Actions)
    private int currentSprayQuota = DEFAULT_SPARSE_QUOTA;
    private int currentIngressMode = INGRESS_ACCEPT_ALL;
    private double routineTelemetryInterval = 60.0;
    private boolean backpressureEnabled = true;
    private double currentBackpressureDelta = 0.15;
    private double currentFocusMinPred = 0.45;
    private double currentFocusMargin = 0.25;

    // Autonomy & RL Epoch Timing
    private double lastAutonomyEvalTime = -1.0;
    private double autonomyEvalInterval = 1.0; // Fast 1.0 Hz safety monitoring
    private double lastRlEpochTime = -1.0;
    private double[] prevEpochState = null;
    private int prevExecutedAction = -1;

    // Local Epoch Telemetry Accumulators (Local-Only SMDP Reward & Cost)
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

    // Protocol Control Overhead & Interaction Counters
    private long beaconOverheadBytes = 0;
    private long gossipExchanges = 0;
    private long gossipOverheadBytes = 0;
    private long ackExchanges = 0;
    private long ackOverheadBytes = 0;

    public DtnmaRouter(Settings s) {
        super(s);
        Settings dtnmaSettings = new Settings(DTNMA_NS);

        String rFile = null;
        if (s.contains(RULES_FILE_SETTING)) {
            rFile = s.getSetting(RULES_FILE_SETTING);
        } else if (dtnmaSettings.contains(RULES_FILE_SETTING)) {
            rFile = dtnmaSettings.getSetting(RULES_FILE_SETTING);
        }

        this.ruleDatabase = new ArrayList<StateBasedRule>();
        if (rFile != null) {
            try {
                this.ruleDatabase = DtnmaRuleParser.parseFile(rFile);
            } catch (Exception e) {
                System.err.println("Warning: Failed to load DTNMA rules from " + rFile + " (" + e.getMessage() + "), using defaults.");
                initDefaultRuleDatabase();
            }
        } else {
            initDefaultRuleDatabase();
        }
        DtnmaSafetyShield.sanitizeRules(this.ruleDatabase);

        this.config = DtnmaConfig.load(s, dtnmaSettings);
        this.intelligenceMode = this.config.intelligenceMode;
        this.gossipEnabled = this.config.gossipEnabled;
        this.ackFeedbackEnabled = this.config.ackFeedbackEnabled;
        this.rlEpochInterval = this.config.rlEpochInterval;

        Settings mmSettings = new Settings("MovementModel");
        if (mmSettings.contains("rngSeed")) {
            try {
                this.scenarioRngSeed = mmSettings.getInt("rngSeed");
            } catch (Exception ignored) {
                this.scenarioRngSeed = 0L;
            }
        }
    }

    protected DtnmaRouter(DtnmaRouter r) {
        super(r);
        this.ruleDatabase = new ArrayList<StateBasedRule>(r.ruleDatabase);
        this.config = new DtnmaConfig(r.config);
        this.intelligenceMode = r.intelligenceMode;
        this.gossipEnabled = r.gossipEnabled;
        this.ackFeedbackEnabled = r.ackFeedbackEnabled;
        this.rlEpochInterval = r.rlEpochInterval;
        this.scenarioRngSeed = r.scenarioRngSeed;

        this.currentSprayQuota = r.currentSprayQuota;
        this.currentIngressMode = r.currentIngressMode;
        this.routineTelemetryInterval = r.routineTelemetryInterval;
        this.backpressureEnabled = r.backpressureEnabled;
        this.currentBackpressureDelta = r.currentBackpressureDelta;
        this.currentFocusMinPred = r.currentFocusMinPred;
        this.currentFocusMargin = r.currentFocusMargin;
        this.autonomyEvalInterval = r.autonomyEvalInterval;
        this.activeRegime = r.activeRegime;
        this.activeRuleAri = r.activeRuleAri;
        this.lastExplanation = r.lastExplanation;

        // Capture normalized energy baseline for this replicated host
        if (this.energy != null && this.energy.getEnergy() > 0.0) {
            this.initialEnergy = this.energy.getEnergy();
        } else {
            this.initialEnergy = 1.0;
        }

        // Fresh per-host mutable maps & subsystems
        this.neighborBeacons = new HashMap<Integer, DtnmaContactBeacon>();
        this.gossipLastExchange = new HashMap<Integer, Double>();
        this.deliveredAckSet = createLruMap(this.config.ackCacheSize);
        this.forwardedCreditMap = createLruMap(MAX_FWD_CREDIT_CACHE);
        this.profileExecCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];
        this.maxBufferOccupancy = 0.0;
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

    private void ensureSubsystemsInitialized() {
        if (getHost() == null) return;
        if (this.config == null) this.config = new DtnmaConfig();
        if (this.neighborBeacons == null) this.neighborBeacons = new HashMap<Integer, DtnmaContactBeacon>();
        if (this.gossipLastExchange == null) this.gossipLastExchange = new HashMap<Integer, Double>();
        if (this.deliveredAckSet == null) this.deliveredAckSet = createLruMap(this.config.ackCacheSize);
        if (this.forwardedCreditMap == null) this.forwardedCreditMap = createLruMap(MAX_FWD_CREDIT_CACHE);
        if (this.profileExecCounts == null) this.profileExecCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];

        if (this.eddSampler == null) {
            this.eddSampler = new EddTelemetrySampler(getHost(), this);
        }
        if (this.safetyShield == null) {
            this.safetyShield = new DtnmaSafetyShield(this.config);
        }
        if (this.rlAgent == null) {
            this.rlAgent = new ShieldedCmdpRlAgent(this.scenarioRngSeed, getHost().getAddress(), this.config);
        }
    }

    /**
     * Initializes the default RFC 9675 State-Based Rule database.
     */
    private void initDefaultRuleDatabase() {
        PredicateExpression pCrit = new PredicateExpression(false); // OR
        pCrit.addClause(EddTelemetrySampler.EDD_QUEUE_RATIO, PredicateExpression.Operator.GREATER_EQUAL, 0.92);
        pCrit.addClause(EddTelemetrySampler.EDD_ENERGY_SOC, PredicateExpression.Operator.LESS_THAN, 0.20);
        StateBasedRule rCrit = new StateBasedRule("ARI://DTNMA/SRL/CRITICAL_CONGESTION",
                "Severe queue congestion or low energy: Restrict quota to 2 and enable backpressure", 100, pCrit);
        rCrit.addAction(ControlAction.setSprayQuota(2));
        rCrit.addAction(ControlAction.setIngressMode(INGRESS_THROTTLE_BULK));
        rCrit.addAction(ControlAction.setTelemetryInterval(600.0));
        rCrit.addAction(ControlAction.setBackpressure(true));
        this.ruleDatabase.add(rCrit);

        PredicateExpression pDense = new PredicateExpression(false);
        pDense.addClause(EddTelemetrySampler.EDD_NODE_DENSITY, PredicateExpression.Operator.GREATER_EQUAL, 4.0);
        StateBasedRule rDense = new StateBasedRule("ARI://DTNMA/SRL/DENSE_CLUSTER",
                "High density cluster: Set balanced spray quota to 3", 50, pDense);
        rDense.addAction(ControlAction.setSprayQuota(3));
        rDense.addAction(ControlAction.setIngressMode(INGRESS_ACCEPT_ALL));
        rDense.addAction(ControlAction.setTelemetryInterval(300.0));
        rDense.addAction(ControlAction.setBackpressure(true));
        this.ruleDatabase.add(rDense);

        PredicateExpression pSparse = new PredicateExpression(true); // AND
        pSparse.addClause(EddTelemetrySampler.EDD_QUEUE_RATIO, PredicateExpression.Operator.LESS_THAN, 0.85);
        pSparse.addClause(EddTelemetrySampler.EDD_NODE_DENSITY, PredicateExpression.Operator.LESS_THAN, 4.0);
        StateBasedRule rSparse = new StateBasedRule("ARI://DTNMA/SRL/SPARSE_EXPLORATION",
                "Sparse topology with healthy buffer: Use exploratory spray quota 6", 10, pSparse);
        rSparse.addAction(ControlAction.setSprayQuota(DEFAULT_SPARSE_QUOTA));
        rSparse.addAction(ControlAction.setIngressMode(INGRESS_ACCEPT_ALL));
        rSparse.addAction(ControlAction.setTelemetryInterval(60.0));
        rSparse.addAction(ControlAction.setBackpressure(false));
        this.ruleDatabase.add(rSparse);

        Collections.sort(this.ruleDatabase, (r1, r2) -> Integer.compare(r2.getPriority(), r1.getPriority()));
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        if (con.isUp()) {
            ensureSubsystemsInitialized();
            if (getHost() == null) return;

            DTNHost other = con.getOtherNode(getHost());
            if (other == null) return;

            if (this.eddSampler != null) {
                this.eddSampler.recordContact(other);
            }

            if (other.getRouter() instanceof DtnmaRouter) {
                DtnmaRouter peer = (DtnmaRouter) other.getRouter();
                double now = SimClock.getTime();

                // 1. Explicit Contact Beacon Exchange (Decentralized local-only handshake)
                DtnmaContactBeacon beacon = peer.getBeaconSnapshot(now);
                this.neighborBeacons.put(other.getAddress(), beacon);
                this.beaconOverheadBytes += DtnmaContactBeacon.BEACON_SIZE_BYTES;

                // 2. Opportunistic Same-Mobility-Class Symmetric Gossip Weight Sharing
                // Initiated once per contact pair (smaller host address) for order-independence
                if (this.gossipEnabled && this.intelligenceMode != IntelligenceMode.STATIC_RULES
                        && peer.gossipEnabled && peer.intelligenceMode != IntelligenceMode.STATIC_RULES
                        && getHost().getAddress() < other.getAddress()) {
                    peer.ensureSubsystemsInitialized();
                    int myAddr = getHost().getAddress();
                    int peerAddr = other.getAddress();
                    Double lastSelf = this.gossipLastExchange.get(peerAddr);
                    Double lastPeer = peer.gossipLastExchange.get(myAddr);
                    boolean readySelf = (lastSelf == null || (now - lastSelf) >= this.config.gossipCooldown);
                    boolean readyPeer = (lastPeer == null || (now - lastPeer) >= peer.config.gossipCooldown);
                    if (readySelf && readyPeer && this.rlAgent != null && peer.getRlAgent() != null
                            && this.eddSampler != null && peer.getEddSampler() != null) {
                        int myClass = this.eddSampler.getMobilityClass();
                        int peerClass = peer.getEddSampler().getMobilityClass();
                        if (ShieldedCmdpRlAgent.gossipMergeSymmetric(
                                this.rlAgent, peer.getRlAgent(), myClass, peerClass)) {
                            this.gossipLastExchange.put(peerAddr, now);
                            this.gossipExchanges++;
                            this.gossipOverheadBytes += DtnmaContactBeacon.GOSSIP_PAYLOAD_BYTES;
                            peer.recordSymGossipExchange(myAddr, now);
                        }
                    }
                }

                // 3. Optional Delayed Delivery Receipt (ACK) Exchange
                if (this.ackFeedbackEnabled) {
                    exchangeDeliveryAcks(peer);
                }
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

    /**
     * Generates this node's compact 16-byte beacon snapshot for connected peers.
     */
    public DtnmaContactBeacon getBeaconSnapshot(double now) {
        ensureSubsystemsInitialized();
        double qRatio = getBufferOccupancyRatio();
        double drift = eddSampler != null ? eddSampler.getQueueDrift() : 0.0;
        double soc = getEnergySoc();
        double speed = eddSampler != null ? eddSampler.getNodeSpeed() : 0.0;
        int mClass = eddSampler != null ? eddSampler.getMobilityClass() : 1;
        int deg = (getHost() != null) ? getHost().getConnections().size() : 0;
        int addr = (getHost() != null) ? getHost().getAddress() : -1;
        return new DtnmaContactBeacon(addr, now, qRatio, drift, soc, speed, mClass, deg);
    }

    /**
     * Returns peer queue ratio strictly from cached Contact Beacons (local handshake).
     */
    public double getBeaconNeighborQueueRatio(DTNHost peer) {
        if (peer == null || neighborBeacons == null) return 0.5;
        DtnmaContactBeacon b = neighborBeacons.get(peer.getAddress());
        return (b != null) ? b.getQueueRatio() : 0.5;
    }

    private void refreshStaleBeacons(double now) {
        if (getHost() == null || neighborBeacons == null) return;
        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            if (other != null && other.getRouter() instanceof DtnmaRouter) {
                DtnmaContactBeacon existing = neighborBeacons.get(other.getAddress());
                if (existing == null || existing.isStale(now, this.config.beaconRefreshInterval)) {
                    DtnmaRouter peer = (DtnmaRouter) other.getRouter();
                    neighborBeacons.put(other.getAddress(), peer.getBeaconSnapshot(now));
                    beaconOverheadBytes += DtnmaContactBeacon.BEACON_SIZE_BYTES;
                }
            }
        }
    }

    private void exchangeDeliveryAcks(DtnmaRouter peer) {
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
                        if (this.rlAgent != null && this.intelligenceMode != IntelligenceMode.STATIC_RULES) {
                            this.rlAgent.applyDelayedAckCredit(
                                    rec.stateVec, rec.actionIndex, this.config.rewardAckDelivery);
                        }
                    }
                }
                if (hasMessage(msgId) && !isSending(msgId)) {
                    deleteMessage(msgId, false);
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

    @Override
    public void update() {
        super.update();
        ensureSubsystemsInitialized();

        double now = SimClock.getTime();

        // 1. RUN RFC 9675 AUTONOMY & SHIELDED RL EVALUATION LOOP
        if (lastAutonomyEvalTime < 0 || (now - lastAutonomyEvalTime) >= autonomyEvalInterval) {
            evaluateAutonomyEngine(now);
            lastAutonomyEvalTime = now;
        }

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        refreshStaleBeacons(now);

        // 2. DELIVER LOCAL DELIVERABLE MESSAGES TO RECIPIENTS FIRST
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        // 3. POLICY-GOVERNED SPRAY & FOCUS FORWARDING
        tryPolicyGovernedForwarding();
    }

    /**
     * Evaluates RFC 9675 Autonomy Rules and Shielded CMDP RL Engine.
     * Safety checks run at 1.0 Hz while event-gated RL SMDP macro-epochs step every rlEpochInterval (15s).
     */
    private void evaluateAutonomyEngine(double now) {
        if (eddSampler == null || safetyShield == null) return;
        eddSampler.sampleNow();

        // Mode A: Baseline Declarative Static Rules
        if (intelligenceMode == IntelligenceMode.STATIC_RULES) {
            evaluateStaticRulesOnly();
            return;
        }

        // Mode B/C/D: RL Macro-Epoch Execution & Safety Shielding
        boolean runRlEpoch = (lastRlEpochTime < 0 || (now - lastRlEpochTime) >= rlEpochInterval);

        if (runRlEpoch) {
            double[] currentState = eddSampler.getNormalizedStateVector();
            StringBuilder maskReason = new StringBuilder();
            boolean[] nextMask = safetyShield.computeSafeActionMask(eddSampler, ruleDatabase, maskReason);
            boolean useMask = (intelligenceMode != IntelligenceMode.UNSHIELDED_RL);
            double qNow = getBufferOccupancyRatio();

            // 1. Event-Gated SMDP Update: skip pure idle empty-buffer epochs so weights aren't diluted to zero
            if (prevEpochState != null && prevExecutedAction >= 0 && rlAgent != null) {
                if (epochActivityCount > 0 || qNow >= 0.75) {
                    double reward = computeLocalEpochReward(qNow);
                    double cost = computeLocalEpochCost(qNow);
                    rlAgent.updateTransition(prevEpochState, prevExecutedAction, reward, cost,
                            currentState, nextMask, useMask, now);
                }
                resetEpochAccumulators();
            }

            // 2. Propose new macro-epoch profile
            int proposed = (rlAgent != null) ? rlAgent.proposeRawAction(currentState, now) : 4;
            int greedy = (rlAgent != null) ? rlAgent.proposeGreedyAction(currentState) : proposed;
            int executed = proposed;
            boolean intervened = false;
            String trigAri = "ARI://DTNMA/RL/DIRECT";
            String expl = "RL_PROPOSED(" + DtnmaPolicyProfile.get(proposed).getName() + ")";

            if (intelligenceMode != IntelligenceMode.UNSHIELDED_RL) {
                int rlSafeFallback = (rlAgent != null)
                        ? rlAgent.selectBestSafeAction(currentState, nextMask, now, false)
                        : 0;
                DtnmaSafetyShield.ShieldDecision dec = safetyShield.enforce(
                        proposed, greedy, rlSafeFallback, eddSampler, ruleDatabase);
                executed = dec.getExecutedAction();
                intervened = dec.isIntervened();
                trigAri = intervened ? dec.getTriggerAri() : DtnmaPolicyProfile.get(executed).getAri();
                expl = dec.getExplanation();

                // Safe-Anchored Counterfactual Dual-Update Shaping (SHIELDED_CMDP_RL only)
                if (intervened && intelligenceMode == IntelligenceMode.SHIELDED_CMDP_RL && rlAgent != null) {
                    rlAgent.applyShieldPenaltyUpdate(currentState, proposed, executed);
                }
            }

            applyPolicyProfile(executed);
            if (profileExecCounts != null && executed >= 0 && executed < profileExecCounts.length) {
                profileExecCounts[executed]++;
            }
            updateAuditState(proposed, executed, intervened, trigAri, expl);
            this.prevEpochState = currentState;
            this.prevExecutedAction = executed;
            this.lastRlEpochTime = now;
        } else if (intelligenceMode != IntelligenceMode.UNSHIELDED_RL && prevExecutedAction >= 0) {
            // Fast 1.0 Hz Safety Guard between RL macro-epochs: immediately clamp on sudden surge
            StringBuilder maskReason = new StringBuilder();
            boolean[] safeMask = safetyShield.computeSafeActionMask(eddSampler, ruleDatabase, maskReason);
            if (!safeMask[prevExecutedAction]) {
                double[] currentState = eddSampler.getNormalizedStateVector();
                int greedy = (rlAgent != null) ? rlAgent.proposeGreedyAction(currentState) : prevExecutedAction;
                int rlSafeFallback = (rlAgent != null)
                        ? rlAgent.selectBestSafeAction(currentState, safeMask, now, false)
                        : 0;
                DtnmaSafetyShield.ShieldDecision dec = safetyShield.enforce(
                        prevExecutedAction, greedy, rlSafeFallback, eddSampler, ruleDatabase);
                int safeAct = dec.getExecutedAction();
                if (intelligenceMode == IntelligenceMode.SHIELDED_CMDP_RL && rlAgent != null) {
                    rlAgent.applyShieldPenaltyUpdate(currentState, prevExecutedAction, safeAct);
                }
                applyPolicyProfile(safeAct);
                if (profileExecCounts != null && safeAct >= 0 && safeAct < profileExecCounts.length) {
                    profileExecCounts[safeAct]++;
                }
                updateAuditState(prevExecutedAction, safeAct, true, dec.getTriggerAri(), dec.getExplanation());
                this.prevExecutedAction = safeAct;
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
                rule.execute(this);

                if (rule.getPriority() >= 100) {
                    this.activeRegime = REGIME_CONGESTED_SAFE;
                    this.currentBackpressureDelta = 0.08;
                    this.currentFocusMinPred = 0.45;
                    this.currentFocusMargin = 0.25;
                } else if (rule.getPriority() >= 50) {
                    this.activeRegime = REGIME_DENSE_BALANCED;
                    this.currentBackpressureDelta = 0.15;
                    this.currentFocusMinPred = 0.45;
                    this.currentFocusMargin = 0.25;
                } else {
                    this.activeRegime = REGIME_SPARSE_NOMINAL;
                    this.currentBackpressureDelta = 0.15;
                    this.currentFocusMinPred = 0.45;
                    this.currentFocusMargin = 0.25;
                }
                break;
            }
        }
    }

    private void applyPolicyProfile(int profileIndex) {
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
        if (prof.getIngressMode() == INGRESS_CRITICAL_ONLY || (intervened && prof.getSprayQuota() <= 1)) {
            this.activeRegime = REGIME_CONGESTED_SAFE;
        } else if (prof.getIngressMode() == INGRESS_THROTTLE_BULK || prof.getSprayQuota() <= 2 || density >= 4.0) {
            this.activeRegime = REGIME_DENSE_BALANCED;
        } else {
            this.activeRegime = REGIME_SPARSE_NOMINAL;
        }
    }

    private double computeLocalEpochReward(double qNow) {
        double bufPenalty = 1.5 * Math.pow(Math.max(0.0, qNow - 0.80), 2);
        double starvePenalty = 0.25 * Math.min(2, epochStarvationCount);
        return config.rewardPredProgress * epochPredProgressSum
             + config.rewardCoverageGain * epochCoverageGain
             - config.penaltyBulkDrop * epochBulkDrops
             - 2.5 * epochCritDrops
             - 0.30 * epochRedundantForwards
             - config.penaltyRelayCost * epochRelayTransfers
             - bufPenalty
             - starvePenalty;
    }

    private double computeLocalEpochCost(double qNow) {
        double dropCost = 0.45 * epochBulkDrops + 1.0 * epochCritDrops;
        double bufCost = 0.80 * Math.max(0.0, qNow - 0.78) / 0.22;
        double redCost = 0.25 * epochRedundantForwards + 0.02 * epochRelayTransfers;
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

    /**
     * Computes exact relay-time copy split respecting active spray quota while conserving tokens.
     */
    public static int computeRelaySplit(int copies, int activeQuota, boolean isCritical) {
        if (copies <= 1) {
            return 0;
        }
        if (isCritical) {
            return Math.max(1, (int) Math.floor(copies / 2.0));
        }
        if (activeQuota <= 1) {
            return 0;
        }
        int eff = Math.min(copies, activeQuota);
        return Math.max(1, Math.min(copies - 1, (int) Math.floor(eff / 2.0)));
    }

    /**
     * Policy-Governed Spray & Focus Forwarding.
     */
    private void tryPolicyGovernedForwarding() {
        List<Connection> connections = getConnections();
        if (connections.isEmpty()) return;

        List<Connection> readyConnections = null;
        for (Connection con : connections) {
            if (con.isReadyForTransfer()) {
                if (readyConnections == null) {
                    readyConnections = new ArrayList<Connection>(connections.size());
                }
                readyConnections.add(con);
            }
        }
        if (readyConnections == null || readyConnections.isEmpty()) return;

        int nReady = readyConnections.size();
        DTNHost[] peerHosts = new DTNHost[nReady];
        DtnmaRouter[] peerRouters = new DtnmaRouter[nReady];
        ProphetRouter[] prophetRouters = new ProphetRouter[nReady];
        double[] peerQueueRatios = new double[nReady];

        for (int i = 0; i < nReady; i++) {
            Connection con = readyConnections.get(i);
            DTNHost other = con.getOtherNode(getHost());
            peerHosts[i] = other;
            if (other != null && other.getRouter() instanceof DtnmaRouter) {
                peerRouters[i] = (DtnmaRouter) other.getRouter();
                peerQueueRatios[i] = getBeaconNeighborQueueRatio(other);
            } else if (other != null && other.getRouter() instanceof ProphetRouter) {
                prophetRouters[i] = (ProphetRouter) other.getRouter();
                peerQueueRatios[i] = 0.5;
            } else {
                peerQueueRatios[i] = 0.5;
            }
        }

        double myQueueRatio = getBufferOccupancyRatio();

        Collection<Message> msgColl = getMessageCollection();
        if (msgColl.isEmpty()) return;

        List<Message> messages = new ArrayList<Message>(msgColl);
        sortMessagesDualTier(messages);

        for (Message m : messages) {
            if (isSending(m.getId())) continue;
            if (this.ackFeedbackEnabled && this.deliveredAckSet != null
                    && this.deliveredAckSet.containsKey(m.getId())) {
                continue;
            }

            boolean isCritical = isCriticalMessage(m);
            Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
            if (copies == null) {
                copies = isCritical ? DEFAULT_CRITICAL_QUOTA : this.currentSprayQuota;
                m.updateProperty(MSG_COPIES_PROPERTY, copies);
            }

            DTNHost dest = m.getTo();
            double myPred = (eddSampler != null && dest != null) ? eddSampler.getPredFor(dest) : 0.0;

            for (int i = 0; i < nReady; i++) {
                Connection con = readyConnections.get(i);
                DTNHost other = peerHosts[i];
                if (other == null) continue;

                // 1. Direct delivery to final recipient always executes immediately
                if (dest == other) {
                    m.updateProperty(MSG_SPLIT_PROPERTY, 0);
                    if (startTransfer(m, con) == RCV_OK) {
                        return;
                    }
                    continue;
                }

                double peerQueue = peerQueueRatios[i];

                // 2. SPRAY PHASE (copies > 1)
                if (copies > 1) {
                    if (!isCritical && backpressureEnabled && peerRouters[i] != null) {
                        if (peerQueue >= myQueueRatio + currentBackpressureDelta) {
                            continue;
                        }
                    }

                    if (!isCritical && peerQueue >= 0.95) {
                        continue;
                    }

                    int toSend = computeRelaySplit(copies, this.currentSprayQuota, isCritical);
                    if (toSend <= 0) {
                        if (!isCritical && myQueueRatio < 0.70 && peerQueue < 0.65) {
                            this.epochStarvationCount++;
                            this.epochActivityCount++;
                        }
                        continue;
                    }

                    m.updateProperty(MSG_SPLIT_PROPERTY, toSend);
                    if (startTransfer(m, con) == RCV_OK) {
                        return;
                    }
                    m.updateProperty(MSG_SPLIT_PROPERTY, 0);
                }
                // 3. COGNITIVE FOCUS HANDOFF PHASE (copies == 1)
                else {
                    if (!isCritical && m.getTtl() < 15) {
                        continue;
                    }
                    int maxHops = isCritical ? 3 : 2;
                    if (m.getHopCount() < maxHops) {
                        double peerPred = 0.0;
                        if (peerRouters[i] != null && peerRouters[i].getEddSampler() != null) {
                            peerPred = peerRouters[i].getEddSampler().getPredFor(dest);
                        } else if (prophetRouters[i] != null) {
                            peerPred = prophetRouters[i].getPredFor(dest);
                        }

                        double minAbsolutePred = isCritical ? 0.35 : this.currentFocusMinPred;
                        double requiredAdvantage = isCritical ? 0.20 : this.currentFocusMargin;
                        double maxPeerQ = isCritical ? 0.90 : 0.80;
                        if (peerPred >= minAbsolutePred && peerPred > myPred + requiredAdvantage && peerQueue < maxPeerQ) {
                            m.updateProperty(MSG_SPLIT_PROPERTY, 0);
                            if (startTransfer(m, con) == RCV_OK) {
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns total bytes belonging to active non-sending incoming transfers on this host
     * that are not destined for this host (i.e., will be buffered upon transfer completion).
     */
    private long getReservedIncomingBytes() {
        if (getHost() == null) return 0L;
        long reserved = 0L;
        for (Connection con : getConnections()) {
            if (con != null && con.isUp() && con.getMessage() != null
                    && !this.sendingConnections.contains(con)
                    && con.getMessage().getTo() != getHost()) {
                reserved += con.getMessage().getSize();
            }
        }
        return reserved;
    }

    @Override
    public boolean createNewMessage(Message msg) {
        long neededLong = (long) msg.getSize() + getReservedIncomingBytes();
        if (neededLong > this.getBufferSize()) {
            return false;
        }
        int needed = (int) neededLong;
        if (!makeRoomForMessage(needed)) {
            if (!isCriticalMessage(msg) || !makeRoomForCriticalEmergency(needed)) {
                return false;
            }
        }
        msg.setTtl(this.msgTtl);
        boolean isCrit = isCriticalMessage(msg);
        int initialCopies = isCrit ? DEFAULT_CRITICAL_QUOTA : this.currentSprayQuota;
        msg.addProperty(MSG_COPIES_PROPERTY, initialCopies);
        addToMessages(msg, true);
        this.epochActivityCount++;
        this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
        return true;
    }

    /**
     * Emergency room reclamation for critical creation under saturated all-critical buffers:
     * evicts already-ACKed critical copies first, then highest redundant replica (copies > 1),
     * never touching sole un-ACKed copies or exceeding physical buffer capacity.
     */
    private boolean makeRoomForCriticalEmergency(int size) {
        if (size > this.getBufferSize()) {
            return false;
        }
        while (this.getFreeBufferSize() < size) {
            Message ackedCand = null;
            Message redundantCand = null;
            int highestRedundantCopies = 1;

            for (Message m : getMessageCollection()) {
                if (isSending(m.getId()) || !isCriticalMessage(m)) continue;
                if (this.ackFeedbackEnabled && this.deliveredAckSet != null
                        && this.deliveredAckSet.containsKey(m.getId())) {
                    ackedCand = m;
                    break;
                }
                Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
                int cVal = (copies != null) ? copies : 1;
                if (cVal > 1) {
                    if (redundantCand == null || cVal > highestRedundantCopies
                            || (cVal == highestRedundantCopies && m.getTtl() < redundantCand.getTtl())) {
                        redundantCand = m;
                        highestRedundantCopies = cVal;
                    }
                }
            }

            if (ackedCand != null) {
                deleteMessage(ackedCand.getId(), false);
            } else if (redundantCand != null) {
                deleteMessage(redundantCand.getId(), true);
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Tracks actual local buffer-eviction drops (ignoring natural finite-TTL expirations).
     */
    @Override
    public void deleteMessage(String id, boolean drop) {
        Message m = getMessage(id);
        if (drop && m != null && m.getTtl() > 0) {
            this.epochActivityCount++;
            if (isCriticalMessage(m)) {
                this.epochCritDrops++;
            } else {
                this.epochBulkDrops++;
            }
        }
        super.deleteMessage(id, drop);
    }

    /**
     * Binary spray quota update, cognitive focus finalization, and causal local progress/coverage logging.
     */
    @Override
    protected void transferDone(Connection con) {
        Message m = getMessage(con.getMessage().getId());
        if (m == null) return;

        this.epochActivityCount++;
        DTNHost recipient = con.getOtherNode(getHost());
        if (m.getTo() == recipient) {
            this.epochDirectDeliveries++;
            if (this.deliveredAckSet != null) {
                this.deliveredAckSet.put(m.getId(), Boolean.TRUE);
            }
            m.updateProperty(MSG_SPLIT_PROPERTY, 0);
            return;
        }
        this.epochRelayTransfers++;

        Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
        Integer splitProp = (Integer) m.getProperty(MSG_SPLIT_PROPERTY);
        int actualSent = 0;
        if (copies != null && copies > 1) {
            actualSent = (splitProp != null && splitProp > 0)
                    ? Math.max(1, Math.min(copies - 1, splitProp))
                    : Math.max(1, (int) Math.floor(copies / 2.0));
            m.updateProperty(MSG_COPIES_PROPERTY, Math.max(1, copies - actualSent));
        } else if (copies != null && copies == 1) {
            // Focus handoff: delete local copy to strictly conserve network copies to 1
            deleteMessage(m.getId(), false);
        }
        m.updateProperty(MSG_SPLIT_PROPERTY, 0);

        // Local RL reward progress & beacon-aware coverage tracking on completed relay forward
        if (eddSampler != null && recipient != null) {
            double myPred = eddSampler.getPredFor(m.getTo());
            double peerPred = 0.0;
            if (recipient.getRouter() instanceof DtnmaRouter) {
                DtnmaRouter peerRouter = (DtnmaRouter) recipient.getRouter();
                if (peerRouter.getEddSampler() != null) {
                    peerPred = peerRouter.getEddSampler().getPredFor(m.getTo());
                }
            } else if (recipient.getRouter() instanceof ProphetRouter) {
                peerPred = ((ProphetRouter) recipient.getRouter()).getPredFor(m.getTo());
            }

            double deltaP = Math.max(0.0, peerPred - myPred);
            double peerQ = getBeaconNeighborQueueRatio(recipient);

            if (peerQ < 0.78) {
                this.epochCoverageGain += 0.40 * (1.0 - peerQ) * Math.sqrt(Math.max(1, actualSent));
            } else if (!isCriticalMessage(m) && deltaP <= 0.05) {
                this.epochRedundantForwards++;
            }

            if (deltaP > 0.05) {
                this.epochPredProgressSum += deltaP;
            }
        }

        if (this.ackFeedbackEnabled && this.forwardedCreditMap != null
                && !isCriticalMessage(m) && eddSampler != null) {
            this.forwardedCreditMap.put(m.getId(), new ForwardedCreditRecord(
                    eddSampler.getNormalizedStateVector(), this.lastExecutedProfile, SimClock.getTime()));
        }
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            this.epochActivityCount++;
            if (this.getFreeBufferSize() < 0) {
                this.makeRoomForMessage(0);
                if (this.getFreeBufferSize() < 0) {
                    if (!isCriticalMessage(m) && hasMessage(m.getId())) {
                        deleteMessage(m.getId(), true);
                    } else if (isCriticalMessage(m)) {
                        if (!makeRoomForCriticalEmergency(0) && hasMessage(m.getId())) {
                            deleteMessage(m.getId(), true);
                        }
                    }
                }
            }
            this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
            if (m.getTo() == getHost() && this.deliveredAckSet != null) {
                this.deliveredAckSet.put(m.getId(), Boolean.TRUE);
            }
            Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
            Integer splitProp = (Integer) m.getProperty(MSG_SPLIT_PROPERTY);
            if (copies != null && copies > 1) {
                int peerCopies = (splitProp != null && splitProp > 0)
                        ? Math.max(1, Math.min(copies - 1, splitProp))
                        : Math.max(1, (int) Math.floor(copies / 2.0));
                m.updateProperty(MSG_COPIES_PROPERTY, peerCopies);
            } else {
                m.updateProperty(MSG_COPIES_PROPERTY, 1);
            }
            m.updateProperty(MSG_SPLIT_PROPERTY, 0);
        }
        return m;
    }

    /**
     * Constructive Ingress Admission Control.
     */
    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        if (this.ackFeedbackEnabled && this.deliveredAckSet != null && this.deliveredAckSet.containsKey(m.getId())) {
            return MessageRouter.DENIED_OLD;
        }

        boolean isCrit = isCriticalMessage(m);
        if (intelligenceMode != IntelligenceMode.UNSHIELDED_RL && isCrit) {
            return super.checkReceiving(m, from);
        }

        double occ = getBufferOccupancyRatio();
        if (!isCrit) {
            if (currentIngressMode == INGRESS_CRITICAL_ONLY && occ > 0.92) {
                return MessageRouter.DENIED_NO_SPACE;
            }
            if (currentIngressMode == INGRESS_THROTTLE_BULK && occ > 0.94) {
                return MessageRouter.DENIED_NO_SPACE;
            }
        }

        return super.checkReceiving(m, from);
    }

    /**
     * Buffer Eviction: Protects Critical Fault telemetry inviolably in Static and Shielded modes.
     */
    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        Collection<Message> messages = this.getMessageCollection();
        Message candidate = null;
        boolean enforceSafetyLock = (intelligenceMode != IntelligenceMode.UNSHIELDED_RL);

        for (Message m : messages) {
            if (excludeMsgBeingSent && isSending(m.getId())) continue;
            if (enforceSafetyLock && isCriticalMessage(m)) continue;

            if (candidate == null) {
                candidate = m;
            } else {
                Integer cCopies = (Integer) candidate.getProperty(MSG_COPIES_PROPERTY);
                Integer mCopies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
                int cVal = cCopies != null ? cCopies : 1;
                int mVal = mCopies != null ? mCopies : 1;

                if (mVal < cVal || (mVal == cVal && m.getTtl() < candidate.getTtl())) {
                    candidate = m;
                }
            }
        }

        return candidate;
    }

    private void sortMessagesDualTier(List<Message> list) {
        Collections.sort(list, new Comparator<Message>() {
            public int compare(Message m1, Message m2) {
                boolean c1 = isCriticalMessage(m1);
                boolean c2 = isCriticalMessage(m2);

                if (c1 && !c2) return -1;
                if (!c1 && c2) return 1;

                Integer p1 = (Integer) m1.getProperty(MSG_COPIES_PROPERTY);
                Integer p2 = (Integer) m2.getProperty(MSG_COPIES_PROPERTY);
                int v1 = (p1 != null) ? p1 : (c1 ? DEFAULT_CRITICAL_QUOTA : currentSprayQuota);
                int v2 = (p2 != null) ? p2 : (c2 ? DEFAULT_CRITICAL_QUOTA : currentSprayQuota);

                boolean s1 = (v1 > 1);
                boolean s2 = (v2 > 1);
                if (s1 != s2) {
                    return s1 ? -1 : 1; // Spray (copies > 1) strictly before Focus (copies == 1)
                }

                if (s1 && v1 != v2) {
                    return Integer.compare(v2, v1); // Higher copies first within Spray
                }

                return Double.compare(m2.getTtl(), m1.getTtl()); // Larger remaining TTL (fresher) first
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List sortByQueueMode(List list) {
        list = super.sortByQueueMode(list);
        Collections.sort(list, new Comparator() {
            public int compare(Object o1, Object o2) {
                Message m1 = (o1 instanceof Tuple) ? ((Tuple<Message, Connection>) o1).getKey() : (Message) o1;
                Message m2 = (o2 instanceof Tuple) ? ((Tuple<Message, Connection>) o2).getKey() : (Message) o2;
                boolean c1 = isCriticalMessage(m1);
                boolean c2 = isCriticalMessage(m2);

                if (c1 && !c2) return -1;
                if (!c1 && c2) return 1;
                return 0;
            }
        });
        return list;
    }

    /**
     * Returns strictly normalized battery State-of-Charge in [0.0, 1.0].
     */
    public double getEnergySoc() {
        if (this.energy == null || this.initialEnergy <= 0.0) {
            return 1.0;
        }
        double soc = this.energy.getEnergy() / this.initialEnergy;
        return Math.max(0.0, Math.min(1.0, soc));
    }

    public double getEnergyLevel() {
        return getEnergySoc();
    }

    public static boolean isCriticalMessage(Message m) {
        return m != null && isCriticalMessage(m.getId());
    }

    public static boolean isCriticalMessage(String id) {
        if (id == null) return false;
        return id.startsWith("CRITICAL_FAULT") || id.startsWith("FAULT") || id.startsWith("CRIT");
    }

    // RFC 9675 Controls Actuators
    public void setDynamicSprayQuota(int quota) {
        this.currentSprayQuota = Math.max(1, quota);
    }

    public void setIngressMode(int mode) {
        this.currentIngressMode = mode;
    }

    public void setRoutineTelemetryInterval(double intervalSec) {
        this.routineTelemetryInterval = Math.max(1.0, intervalSec);
    }

    public void setBackpressureEnabled(boolean enabled) {
        this.backpressureEnabled = enabled;
    }

    public long getBufferOccupancy() {
        return getBufferSize() - getFreeBufferSize();
    }

    public double getBufferOccupancyRatio() {
        long bSize = getBufferSize();
        if (bSize <= 0) return 0.0;
        return (double) (bSize - getFreeBufferSize()) / bSize;
    }

    // Legacy & Audit Getters
    public int getLastActionIndex() { return this.activeRegime; }

    public String getActionDescription(int action) {
        switch (action) {
            case REGIME_SPARSE_NOMINAL: return "SPARSE_NOMINAL(Quota=" + currentSprayQuota + ")";
            case REGIME_DENSE_BALANCED: return "DENSE_BALANCED(Quota=" + currentSprayQuota + ")";
            case REGIME_CONGESTED_SAFE: return "CONGESTED_SAFE(Quota=" + currentSprayQuota + ")";
            default: return "REGIME_" + action;
        }
    }

    public String getActiveRuleAri() { return this.activeRuleAri; }
    public String getLastExplanation() { return this.lastExplanation; }
    public EddTelemetrySampler getEddSampler() { return this.eddSampler; }

    // Phase 1 RL, Shield & Overhead Getters
    public IntelligenceMode getIntelligenceMode() { return intelligenceMode; }
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
    public long[] getProfileExecCounts() { return profileExecCounts; }

    @Override
    public MessageRouter replicate() {
        return new DtnmaRouter(this);
    }
}
