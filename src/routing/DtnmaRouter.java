/*
 * DtnmaRouter.java
 * Phase 1 Autonomic DTNMA Router implementing RFC 9675 (DTNMA) & RFC 7575 (Autonomic Networking)
 * fused with Two-Tier Shielded CMDP Reinforcement Learning & Opportunistic Peer Gossip.
 *
 * Key Architecture & Ablation Support:
 * 1. Strongly-typed RFC 9675 EDD Telemetry Sampler with EWMA smoothing & normalized Energy SoC.
 * 2. Zero-Oracle explicit neighbor telemetry via DtnmaContactBeacon.
 * 3. Primal-Dual Constrained Linear Expected SARSA RL Agent + Mobility-Class Gossip.
 * 4. Pre-Action & Post-Action RFC 9675 Safety Shield with State-Localized Dual-Update Shaping.
 * 5. Event-Gated SMDP transitions with zero-oracle local coverage utility.
 * 6. Optional Delayed Delivery ACK Feedback ("Vaccine" copy pruning & delayed credit).
 * 7. Dual-Tier HoL Priority Scheduling & Inviolable Zero-Drop Protection (when shielded/static).
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
    private static final double BEACON_REFRESH_SEC = 5.0;
    private static final double GOSSIP_COOLDOWN_SEC = 60.0;
    private static final int MAX_ACK_CACHE = 512;
    private static final int MAX_FWD_CREDIT_CACHE = 256;
    private static final int MAX_ACKS_PER_CONTACT = 32;

    // Configuration Fields
    protected IntelligenceMode intelligenceMode = IntelligenceMode.SHIELDED_CMDP_RL;
    protected boolean gossipEnabled = true;
    protected boolean ackFeedbackEnabled = false;
    protected double rlEpochInterval = 15.0;
    protected long scenarioRngSeed = 0L;

    // DTNMA Subsystems (per-host isolated)
    private EddTelemetrySampler eddSampler;
    private List<StateBasedRule> ruleDatabase;
    private DtnmaSafetyShield safetyShield;
    private ShieldedCmdpRlAgent rlAgent;

    // Normalized Energy Reference (captured on host instantiation)
    private double initialEnergy = 1.0;

    // Zero-Oracle Neighbor Contact Beacons & Gossip State
    private Map<Integer, DtnmaContactBeacon> neighborBeacons;
    private Map<Integer, Double> gossipLastExchange;

    // Optional Delayed Delivery Receipt (ACK) Caches (LRU bounded)
    private Map<String, Boolean> deliveredAckSet;
    private Map<String, Double> forwardedCreditMap;

    // Audit & Explainability State (defaults to healthy Profile 4: STANDARD_SPRAY_FOCUS, L=6)
    private String activeRuleAri = "ARI://DTNMA/SRL/BOOTSTRAP";
    private String lastExplanation = "INITIAL_STATE";
    private int activeRegime = REGIME_SPARSE_NOMINAL;
    private int lastProposedProfile = 4;
    private int lastExecutedProfile = 4;
    private boolean lastShieldIntervened = false;
    private String shieldTriggerAri = "ARI://DTNMA/SHIELD/PASS";

    // Dynamic Policy Variables (Adjusted via Profile / RFC 9675 Controls)
    private int currentSprayQuota = DEFAULT_SPARSE_QUOTA;
    private int currentIngressMode = INGRESS_ACCEPT_ALL;
    private double routineTelemetryInterval = 60.0;
    private boolean backpressureEnabled = true;
    private double currentBackpressureDelta = 0.15;
    private double currentFocusMinPred = 0.28;
    private double currentFocusMargin = 0.10;

    // Autonomy & RL Epoch Timing
    private double lastAutonomyEvalTime = -1.0;
    private double autonomyEvalInterval = 1.0; // Fast 1.0 Hz safety monitoring
    private double lastRlEpochTime = -1.0;
    private double[] prevEpochState = null;
    private int prevExecutedAction = -1;

    // Local Epoch Telemetry Accumulators (Zero-Oracle SMDP Reward & Cost)
    private int epochDirectDeliveries = 0;
    private int epochAckDeliveries = 0;
    private double epochPredProgressSum = 0.0;
    private double epochCoverageGain = 0.0;
    private int epochRedundantForwards = 0;
    private int epochRelayTransfers = 0;
    private int epochBulkDrops = 0;
    private int epochCritDrops = 0;
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

        String modeStr = getSettingString(s, dtnmaSettings, INTELLIGENCE_MODE_SETTING, null);
        if (modeStr != null) {
            this.intelligenceMode = parseIntelligenceMode(modeStr);
        }
        this.gossipEnabled = getSettingBoolean(s, dtnmaSettings, GOSSIP_ENABLED_SETTING, true);
        this.ackFeedbackEnabled = getSettingBoolean(s, dtnmaSettings, ACK_FEEDBACK_SETTING, false);
        this.rlEpochInterval = getSettingDouble(s, dtnmaSettings, RL_EPOCH_INTERVAL_SETTING, 15.0);

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
        this.deliveredAckSet = createLruMap(MAX_ACK_CACHE);
        this.forwardedCreditMap = createLruMap(MAX_FWD_CREDIT_CACHE);
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

    private static String getSettingString(Settings s1, Settings s2, String key, String def) {
        if (s1.contains(key)) return s1.getSetting(key);
        if (s2.contains(key)) return s2.getSetting(key);
        return def;
    }

    private static boolean getSettingBoolean(Settings s1, Settings s2, String key, boolean def) {
        if (s1.contains(key)) return s1.getBoolean(key);
        if (s2.contains(key)) return s2.getBoolean(key);
        return def;
    }

    private static double getSettingDouble(Settings s1, Settings s2, String key, double def) {
        if (s1.contains(key)) return s1.getDouble(key);
        if (s2.contains(key)) return s2.getDouble(key);
        return def;
    }

    private static IntelligenceMode parseIntelligenceMode(String val) {
        if (val == null) return IntelligenceMode.SHIELDED_CMDP_RL;
        String clean = val.trim().toUpperCase();
        try {
            return IntelligenceMode.valueOf(clean);
        } catch (IllegalArgumentException e) {
            return IntelligenceMode.SHIELDED_CMDP_RL;
        }
    }

    private void ensureSubsystemsInitialized() {
        if (getHost() == null) return;
        if (this.neighborBeacons == null) this.neighborBeacons = new HashMap<Integer, DtnmaContactBeacon>();
        if (this.gossipLastExchange == null) this.gossipLastExchange = new HashMap<Integer, Double>();
        if (this.deliveredAckSet == null) this.deliveredAckSet = createLruMap(MAX_ACK_CACHE);
        if (this.forwardedCreditMap == null) this.forwardedCreditMap = createLruMap(MAX_FWD_CREDIT_CACHE);

        if (this.eddSampler == null) {
            this.eddSampler = new EddTelemetrySampler(getHost(), this);
        }
        if (this.safetyShield == null) {
            this.safetyShield = new DtnmaSafetyShield();
        }
        if (this.rlAgent == null) {
            this.rlAgent = new ShieldedCmdpRlAgent(this.scenarioRngSeed, getHost().getAddress());
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

                // 1. Explicit Contact Beacon Exchange (Zero-Oracle neighbor queue/state awareness)
                DtnmaContactBeacon beacon = peer.getBeaconSnapshot(now);
                this.neighborBeacons.put(other.getAddress(), beacon);
                this.beaconOverheadBytes += DtnmaContactBeacon.BEACON_SIZE_BYTES;

                // 2. Opportunistic Same-Mobility-Class Gossip Weight Sharing
                if (this.gossipEnabled && this.intelligenceMode != IntelligenceMode.STATIC_RULES) {
                    Double lastG = this.gossipLastExchange.get(other.getAddress());
                    if (lastG == null || (now - lastG) >= GOSSIP_COOLDOWN_SEC) {
                        peer.ensureSubsystemsInitialized();
                        if (this.rlAgent != null && peer.getRlAgent() != null
                                && this.eddSampler != null && peer.getEddSampler() != null) {
                            int myClass = this.eddSampler.getMobilityClass();
                            int peerClass = peer.getEddSampler().getMobilityClass();
                            if (this.rlAgent.gossipMerge(peer.getRlAgent(), myClass, peerClass)) {
                                this.gossipLastExchange.put(other.getAddress(), now);
                                this.gossipExchanges++;
                                this.gossipOverheadBytes += DtnmaContactBeacon.GOSSIP_PAYLOAD_BYTES;
                            }
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
     * Returns peer queue ratio strictly from cached Contact Beacons (Zero-Oracle).
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
                if (existing == null || existing.isStale(now, BEACON_REFRESH_SEC)) {
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
                if (this.forwardedCreditMap != null && this.forwardedCreditMap.remove(msgId) != null) {
                    this.epochAckDeliveries++;
                    this.epochActivityCount++;
                }
                if (hasMessage(msgId) && !isSending(msgId)) {
                    deleteMessage(msgId, false);
                }
                if (learned >= MAX_ACKS_PER_CONTACT) {
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
            int executed = proposed;
            boolean intervened = false;
            String trigAri = "ARI://DTNMA/RL/DIRECT";
            String expl = "RL_PROPOSED(" + DtnmaPolicyProfile.get(proposed).getName() + ")";

            if (intelligenceMode != IntelligenceMode.UNSHIELDED_RL) {
                int rlSafeFallback = (rlAgent != null)
                        ? rlAgent.selectBestSafeAction(currentState, nextMask, now, false)
                        : 0;
                DtnmaSafetyShield.ShieldDecision dec = safetyShield.enforce(proposed, rlSafeFallback, eddSampler, ruleDatabase);
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
                int rlSafeFallback = (rlAgent != null)
                        ? rlAgent.selectBestSafeAction(currentState, safeMask, now, false)
                        : 0;
                DtnmaSafetyShield.ShieldDecision dec = safetyShield.enforce(prevExecutedAction, rlSafeFallback, eddSampler, ruleDatabase);
                int safeAct = dec.getExecutedAction();
                if (intelligenceMode == IntelligenceMode.SHIELDED_CMDP_RL && rlAgent != null) {
                    rlAgent.applyShieldPenaltyUpdate(currentState, prevExecutedAction, safeAct);
                }
                applyPolicyProfile(safeAct);
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
                    this.currentFocusMinPred = 0.35;
                    this.currentFocusMargin = 0.15;
                } else if (rule.getPriority() >= 50) {
                    this.activeRegime = REGIME_DENSE_BALANCED;
                    this.currentBackpressureDelta = 0.15;
                    this.currentFocusMinPred = 0.35;
                    this.currentFocusMargin = 0.15;
                } else {
                    this.activeRegime = REGIME_SPARSE_NOMINAL;
                    this.currentBackpressureDelta = 0.15;
                    this.currentFocusMinPred = 0.35;
                    this.currentFocusMargin = 0.15;
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
        return 2.5 * epochDirectDeliveries
             + 1.2 * epochAckDeliveries
             + 1.5 * epochPredProgressSum
             + 1.0 * epochCoverageGain
             - 0.8 * epochBulkDrops
             - 2.5 * epochCritDrops
             - 0.30 * epochRedundantForwards
             - 0.05 * epochRelayTransfers
             - bufPenalty;
    }

    private double computeLocalEpochCost(double qNow) {
        double dropCost = 0.35 * epochBulkDrops + 1.0 * epochCritDrops;
        double bufCost = 0.80 * Math.max(0.0, qNow - 0.75) / 0.25;
        double redCost = 0.20 * epochRedundantForwards + 0.02 * epochRelayTransfers;
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
        this.epochActivityCount = 0;
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

                    if (startTransfer(m, con) == RCV_OK) {
                        return;
                    }
                }
                // 3. COGNITIVE FOCUS HANDOFF PHASE (copies == 1)
                else {
                    int maxHops = isCritical ? 3 : 2;
                    if (m.getHopCount() < maxHops) {
                        double peerPred = 0.0;
                        if (peerRouters[i] != null && peerRouters[i].getEddSampler() != null) {
                            peerPred = peerRouters[i].getEddSampler().getPredFor(dest);
                        } else if (prophetRouters[i] != null) {
                            peerPred = prophetRouters[i].getPredFor(dest);
                        }

                        double minAbsolutePred = isCritical ? 0.25 : this.currentFocusMinPred;
                        double requiredAdvantage = isCritical ? 0.10 : this.currentFocusMargin;
                        if (peerPred >= minAbsolutePred && peerPred > myPred + requiredAdvantage && peerQueue < 0.90) {
                            if (startTransfer(m, con) == RCV_OK) {
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);
        boolean isCrit = isCriticalMessage(msg);
        int initialCopies = isCrit ? DEFAULT_CRITICAL_QUOTA : this.currentSprayQuota;
        msg.addProperty(MSG_COPIES_PROPERTY, initialCopies);
        addToMessages(msg, true);
        this.epochActivityCount++;
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
     * Binary spray quota update, cognitive focus finalization, and local RL progress/coverage logging.
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
            return;
        }
        this.epochRelayTransfers++;

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

            if (peerQ < 0.75) {
                this.epochCoverageGain += 0.35 * (1.0 - peerQ);
            } else if (!isCriticalMessage(m) && deltaP <= 0.01) {
                this.epochRedundantForwards++;
            }

            if (deltaP > 0.02) {
                this.epochPredProgressSum += deltaP;
            }
        }

        if (this.ackFeedbackEnabled && this.forwardedCreditMap != null) {
            this.forwardedCreditMap.put(m.getId(), SimClock.getTime());
        }

        Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
        if (copies != null && copies > 1) {
            int remainingCopies = (int) Math.ceil(copies / 2.0);
            m.updateProperty(MSG_COPIES_PROPERTY, Math.max(1, remainingCopies));
        } else if (copies != null && copies == 1) {
            // Focus handoff: delete local copy to strictly conserve network copies to 1
            deleteMessage(m.getId(), false);
        }
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            this.epochActivityCount++;
            if (m.getTo() == getHost() && this.deliveredAckSet != null) {
                this.deliveredAckSet.put(m.getId(), Boolean.TRUE);
            }
            Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
            if (copies != null && copies > 1) {
                int peerCopies = (int) Math.floor(copies / 2.0);
                m.updateProperty(MSG_COPIES_PROPERTY, Math.max(1, peerCopies));
            } else {
                m.updateProperty(MSG_COPIES_PROPERTY, 1);
            }
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
            if (currentIngressMode == INGRESS_CRITICAL_ONLY && occ > 0.85) {
                return MessageRouter.DENIED_NO_SPACE;
            }
            if (currentIngressMode == INGRESS_THROTTLE_BULK && occ > 0.88) {
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

                return Double.compare(m1.getTtl(), m2.getTtl());
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

    @Override
    public MessageRouter replicate() {
        return new DtnmaRouter(this);
    }
}
