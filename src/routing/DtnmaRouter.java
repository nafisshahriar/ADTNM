/*
 * DtnmaRouter.java
 * Solidified Autonomic DTNMA Router implementing RFC 9675 (DTNMA) & RFC 7575 (Autonomic Networking).
 * Features:
 * 1. Strongly-typed RFC 9675 Predicate Autonomy Engine with State-Based Rules (SRLs).
 * 2. Density-Aware Dynamic Forwarding (Adapts spray quotas between Sparse and Dense regimes).
 * 3. Backpressure Queue Gradient Forwarding (Suppresses transfers into congested neighbors).
 * 4. Dual-Tier Priority Queuing (Strict Head-of-Line preemption & zero drops for Critical Faults).
 * 5. Constructive Ingress Admission Control (Eliminates destructive post-hoc mass purges).
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;
import core.SimClock;
import util.Tuple;

public class DtnmaRouter extends ActiveRouter {

    public static final String DTNMA_NS = "DtnmaRouter";
    public static final String MSG_COPIES_PROPERTY = "Dtnma_Copies";

    // Ingress Control Modes
    public static final int INGRESS_ACCEPT_ALL = 0;
    public static final int INGRESS_THROTTLE_BULK = 1;
    public static final int INGRESS_CRITICAL_ONLY = 2;

    // Action / Regime States for Reporting & Analytics
    public static final int REGIME_SPARSE_NOMINAL = 0;
    public static final int REGIME_DENSE_BALANCED = 1;
    public static final int REGIME_CONGESTED_SAFE = 2;

    // Default Policy Bounds
    public static final int DEFAULT_SPARSE_QUOTA = 6;
    public static final int DEFAULT_DENSE_QUOTA = 2;
    public static final int DEFAULT_CRITICAL_QUOTA = 1;

    public static final String RULES_FILE_SETTING = "rulesFile";

    // DTNMA Subsystem
    private EddTelemetrySampler eddSampler;
    private List<StateBasedRule> ruleDatabase;
    private String activeRuleAri = "ARI://DTNMA/SRL/BOOTSTRAP";
    private String lastExplanation = "INITIAL_STATE";
    private int activeRegime = REGIME_SPARSE_NOMINAL;

    // Dynamic Policy Variables (Adjusted via RFC 9675 Controls)
    private int currentSprayQuota = DEFAULT_SPARSE_QUOTA;
    private int currentIngressMode = INGRESS_ACCEPT_ALL;
    private double routineTelemetryInterval = 60.0;
    private boolean backpressureEnabled = true;

    // Autonomy Engine Timing
    private double lastAutonomyEvalTime = -1.0;
    private double autonomyEvalInterval = 1.0; // Evaluate rules at 1.0 Hz

    public DtnmaRouter(Settings s) {
        super(s);
        this.ruleDatabase = new ArrayList<StateBasedRule>();
        if (s.contains(RULES_FILE_SETTING)) {
            String rFile = s.getSetting(RULES_FILE_SETTING);
            try {
                this.ruleDatabase = DtnmaRuleParser.parseFile(rFile);
            } catch (Exception e) {
                System.err.println("Warning: Failed to load DTNMA rules from " + rFile + " (" + e.getMessage() + "), using defaults.");
                initDefaultRuleDatabase();
            }
        } else {
            initDefaultRuleDatabase();
        }
    }

    protected DtnmaRouter(DtnmaRouter r) {
        super(r);
        this.ruleDatabase = new ArrayList<StateBasedRule>();
        for (StateBasedRule rule : r.ruleDatabase) {
            this.ruleDatabase.add(rule);
        }
        this.currentSprayQuota = r.currentSprayQuota;
        this.currentIngressMode = r.currentIngressMode;
        this.routineTelemetryInterval = r.routineTelemetryInterval;
        this.backpressureEnabled = r.backpressureEnabled;
        this.autonomyEvalInterval = r.autonomyEvalInterval;
        this.activeRegime = r.activeRegime;
        this.activeRuleAri = r.activeRuleAri;
        this.lastExplanation = r.lastExplanation;
    }

    /**
     * Initializes the default RFC 9675 State-Based Rule database.
     * Incorporates Node Density, Queue Congestion, and Critical Fault Preservation.
     */
    private void initDefaultRuleDatabase() {
        // RULE 1: CRITICAL CONGESTION & LOW ENERGY (Priority 100)
        // IF Queue >= 92% OR Energy < 20% -> Quota = 2, Ingress = Throttle, Interval = 600s
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

        // RULE 2: DENSE REGIME (Priority 50)
        // IF NodeDensity >= 4 -> Quota = 3, Interval = 300s
        PredicateExpression pDense = new PredicateExpression(false);
        pDense.addClause(EddTelemetrySampler.EDD_NODE_DENSITY, PredicateExpression.Operator.GREATER_EQUAL, 4.0);
        StateBasedRule rDense = new StateBasedRule("ARI://DTNMA/SRL/DENSE_CLUSTER",
                "High density cluster: Set balanced spray quota to 3", 50, pDense);
        rDense.addAction(ControlAction.setSprayQuota(3));
        rDense.addAction(ControlAction.setIngressMode(INGRESS_ACCEPT_ALL));
        rDense.addAction(ControlAction.setTelemetryInterval(300.0));
        rDense.addAction(ControlAction.setBackpressure(true));
        this.ruleDatabase.add(rDense);

        // RULE 3: SPARSE NOMINAL REGIME (Priority 10)
        // IF Queue < 85% AND NodeDensity < 4 -> Quota = 6, Ingress = Accept All, Interval = 60s
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

        // Sort rules by priority descending
        Collections.sort(this.ruleDatabase, (r1, r2) -> Integer.compare(r2.getPriority(), r1.getPriority()));
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        if (con.isUp()) {
            if (this.eddSampler == null && getHost() != null) {
                this.eddSampler = new EddTelemetrySampler(getHost(), this);
            }
            if (this.eddSampler != null) {
                this.eddSampler.recordContact(con.getOtherNode(getHost()));
            }
        }
    }

    @Override
    public void update() {
        super.update();

        // Lazy initialize EDD sampler once host is attached
        if (this.eddSampler == null && getHost() != null) {
            this.eddSampler = new EddTelemetrySampler(getHost(), this);
        }

        double now = SimClock.getTime();

        // 1. RUN RFC 9675 AUTONOMY ENGINE EVALUATION LOOP
        if (now - lastAutonomyEvalTime >= autonomyEvalInterval) {
            evaluateAutonomyEngine();
            lastAutonomyEvalTime = now;
        }

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        // 2. DELIVER LOCAL DELIVERABLE MESSAGES TO RECIPIENTS FIRST
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        // 3. POLICY-GOVERNED SPRAY & FOCUS FORWARDING
        tryPolicyGovernedForwarding();
    }

    /**
     * RFC 9675 Autonomy Engine: Evaluates active State-Based Rules (SRLs) in priority order.
     */
    private void evaluateAutonomyEngine() {
        if (eddSampler == null) return;
        eddSampler.sampleNow();

        for (StateBasedRule rule : ruleDatabase) {
            if (rule.isTriggered(eddSampler)) {
                this.activeRuleAri = rule.getAri();
                this.lastExplanation = rule.getLastExplanation();
                rule.execute(this);

                // Update regime reporting state
                if (rule.getPriority() >= 100) {
                    this.activeRegime = REGIME_CONGESTED_SAFE;
                } else if (rule.getPriority() >= 50) {
                    this.activeRegime = REGIME_DENSE_BALANCED;
                } else {
                    this.activeRegime = REGIME_SPARSE_NOMINAL;
                }
                break; // Highest priority triggered rule governs
            }
        }
    }

    /**
     * Policy-Governed Spray & Focus Forwarding.
     * Combines dynamic token spray (copies > 1) with PRoPHET-assisted cognitive handoff (copies == 1).
     * Eliminates redundant O(M*C) buffer queries for ultra-fast simulation throughput.
     */
    private void tryPolicyGovernedForwarding() {
        List<Connection> connections = getConnections();
        if (connections.isEmpty()) return;

        // Fast filtering: find connections ready for transfer
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

        // Pre-cache peer metadata once per ready connection (O(C) instead of O(M*C))
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
                DtnmaRouter pr = (DtnmaRouter) other.getRouter();
                peerRouters[i] = pr;
                peerQueueRatios[i] = pr.getBufferOccupancyRatio();
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
                copies = isCritical ? 8 : this.currentSprayQuota;
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
                    // Backpressure check: suppress non-critical forwarding into congested peers
                    if (!isCritical && backpressureEnabled && peerRouters[i] != null) {
                        if (peerQueue >= myQueueRatio + 0.15) {
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

                        // Focus handoff: Hand off single copy only to peers with high genuine predictability
                        double minAbsolutePred = isCritical ? 0.25 : 0.35;
                        double requiredAdvantage = isCritical ? 0.10 : 0.15;
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
        int initialCopies = isCrit ? 8 : this.currentSprayQuota;
        msg.addProperty(MSG_COPIES_PROPERTY, initialCopies);
        addToMessages(msg, true);
        return true;
    }

    /**
     * Binary spray quota update & cognitive focus handoff finalization.
     */
    @Override
    protected void transferDone(Connection con) {
        Message m = getMessage(con.getMessage().getId());
        if (m == null) return;

        DTNHost recipient = con.getOtherNode(getHost());
        // Destination delivery: transfer is complete
        if (m.getTo() == recipient) {
            return;
        }

        Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
        if (copies != null && copies > 1) {
            // Binary spray: sender retains ceil(copies / 2)
            int remainingCopies = (int) Math.ceil(copies / 2.0);
            m.updateProperty(MSG_COPIES_PROPERTY, Math.max(1, remainingCopies));
        } else if (copies != null && copies == 1) {
            // Focus handoff: single copy was transferred to a higher-predictability relay.
            // Relinquish local copy to conserve network copies to exactly 1.
            deleteMessage(m.getId(), false);
        }
    }

    /**
     * Initializes copies property for incoming messages accepted from peers.
     */
    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
            if (copies != null && copies > 1) {
                // Binary spray: receiver gets floor(copies / 2)
                int peerCopies = (int) Math.floor(copies / 2.0);
                m.updateProperty(MSG_COPIES_PROPERTY, Math.max(1, peerCopies));
            } else {
                // Focus handoff or single copy: receiver maintains exactly 1 copy.
                // Strict copy conservation: NEVER re-seed extra copies!
                m.updateProperty(MSG_COPIES_PROPERTY, 1);
            }
        }
        return m;
    }

    /**
     * Ingress Admission Control: Applies backpressure at reception instead of post-hoc dropping.
     */
    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        if (isCriticalMessage(m)) {
            return super.checkReceiving(m, from); // Always admit critical faults!
        }

        // Only block incoming bulk transfers if buffer is critically congested (>95%)
        if (currentIngressMode == INGRESS_CRITICAL_ONLY && getBufferOccupancyRatio() > 0.95) {
            return MessageRouter.DENIED_NO_SPACE;
        }

        return super.checkReceiving(m, from);
    }

    /**
     * Buffer Replacement: Prioritizes shedding low-value replicas over critical data.
     * NEVER drops critical fault messages!
     */
    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        Collection<Message> messages = this.getMessageCollection();
        Message candidate = null;

        for (Message m : messages) {
            if (excludeMsgBeingSent && isSending(m.getId())) continue;
            if (isCriticalMessage(m)) continue; // Inviolable safety guarantee

            // Prefer removing bulk science messages with remaining copies or low TTL
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

    /**
     * Dual-Tier Priority Sorting: Critical fault observability messages strictly placed at head of line.
     */
    private void sortMessagesDualTier(List<Message> list) {
        Collections.sort(list, new Comparator<Message>() {
            public int compare(Message m1, Message m2) {
                boolean c1 = isCriticalMessage(m1);
                boolean c2 = isCriticalMessage(m2);

                if (c1 && !c2) return -1; // Critical first
                if (!c1 && c2) return 1;

                // Secondary sort: smaller remaining TTL first
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

    public double getEnergyLevel() {
        return (this.energy != null) ? this.energy.getEnergy() : 1.0;
    }

    public static boolean isCriticalMessage(Message m) {
        return m != null && isCriticalMessage(m.getId());
    }

    public static boolean isCriticalMessage(String id) {
        if (id == null) return false;
        return id.startsWith("CRITICAL_FAULT") || id.startsWith("FAULT") || id.startsWith("CRIT");
    }

    // Controls Actuators
    public void setDynamicSprayQuota(int quota) {
        this.currentSprayQuota = Math.max(1, quota);
    }

    public void setIngressMode(int mode) {
        this.currentIngressMode = mode;
    }

    public void setRoutineTelemetryInterval(double intervalSec) {
        this.routineTelemetryInterval = Math.max(10.0, intervalSec);
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

    public int getLastActionIndex() {
        return this.activeRegime;
    }

    public String getActionDescription(int action) {
        switch (action) {
            case REGIME_SPARSE_NOMINAL: return "SPARSE_NOMINAL(Quota=" + currentSprayQuota + ")";
            case REGIME_DENSE_BALANCED: return "DENSE_BALANCED(Quota=" + currentSprayQuota + ")";
            case REGIME_CONGESTED_SAFE: return "CONGESTED_SAFE(Quota=" + currentSprayQuota + ")";
            default: return "REGIME_" + action;
        }
    }

    public String getActiveRuleAri() {
        return this.activeRuleAri;
    }

    public String getLastExplanation() {
        return this.lastExplanation;
    }

    public EddTelemetrySampler getEddSampler() {
        return this.eddSampler;
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaRouter(this);
    }
}
