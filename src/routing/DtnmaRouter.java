/*
 * DtnmaRouter.java
 * Autonomic DTNMA Router implementing RFC 9675-inspired declarative policy
 * management & RFC 7575 autonomic closed-loop principles, delegating its
 * entire management plane by composition to DtnmaAgent.
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import util.Tuple;

public class DtnmaRouter extends ActiveRouter implements DtnmaManagedRouter {

    public static final String DTNMA_NS = "DtnmaRouter";
    public static final String MSG_COPIES_PROPERTY = "Dtnma_Copies";
    public static final String MSG_SPLIT_PROPERTY = "Dtnma_PendingSplit";

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
    public static final int DEFAULT_CRITICAL_QUOTA = 8;

    // Settings Identifiers
    public static final String RULES_FILE_SETTING = "rulesFile";
    public static final String ACK_FEEDBACK_SETTING = "ackFeedbackEnabled";

    // Preset compatibility fields (synchronized with DtnmaAgent)
    protected DtnmaConfig config;
    protected boolean ackFeedbackEnabled = true;

    // Composed Autonomic Management Plane Agent
    private final DtnmaAgent agent;

    // Normalized Energy Reference (captured on host instantiation)
    private double initialEnergy = 1.0;

    public DtnmaRouter(Settings s) {
        super(s);
        this.agent = new DtnmaAgent(this, s);
        this.config = this.agent.config;
        this.ackFeedbackEnabled = this.agent.ackFeedbackEnabled;
    }

    protected DtnmaRouter(DtnmaRouter r) {
        super(r);
        r.syncPrototypeToAgent();
        this.agent = new DtnmaAgent(this, r.agent);
        this.config = this.agent.config;
        this.ackFeedbackEnabled = this.agent.ackFeedbackEnabled;

        if (this.energy != null && this.energy.getEnergy() > 0.0) {
            this.initialEnergy = this.energy.getEnergy();
        } else {
            this.initialEnergy = 1.0;
        }
    }

    private void syncPrototypeToAgent() {
        this.agent.ackFeedbackEnabled = this.ackFeedbackEnabled;
        this.agent.config.ackFeedbackEnabled = this.ackFeedbackEnabled;
    }

    protected final void applyBaselinePresetKnobs() {
        syncPrototypeToAgent();
        this.agent.applyConfiguredBaselineKnobs();
    }

    protected final void loadRulesFromFile(String rFile) {
        this.agent.loadRulesFromFile(rFile);
    }

    protected final void loadRulesFromString(String ruleText) {
        this.agent.loadRulesFromString(ruleText);
    }

    @Override
    public boolean supportsDynamicControlReconfig() {
        return true;
    }

    @Override
    public DtnmaAgent getDtnmaAgent() {
        syncPrototypeToAgent();
        return this.agent;
    }

    // DtnmaManagedRouter adapters
    @Override public DTNHost getManagedHost() { return getHost(); }
    @Override public ActiveRouter getManagedActiveRouter() { return this; }
    @Override public Collection<Message> getManagedMessageCollection() { return getMessageCollection(); }
    @Override public Message getManagedMessage(String id) { return getMessage(id); }
    @Override public long getManagedBufferSize() { return getBufferSize(); }
    @Override public long getManagedFreeBufferSize() { return getFreeBufferSize(); }
    @Override public boolean hasManagedMessage(String id) { return hasMessage(id); }
    @Override public boolean isManagedSending(String id) { return isSending(id); }
    @Override public void deleteManagedMessage(String id, boolean drop) { deleteMessage(id, drop); }
    @Override public List<Connection> getManagedConnections() { return getConnections(); }
    @Override public Collection<Connection> getManagedSendingConnections() { return this.sendingConnections; }
    @Override public int getManagedMsgTtl() { return this.msgTtl; }
    @Override public int compareManagedByQueueMode(Message m1, Message m2) { return compareByQueueMode(m1, m2); }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        syncPrototypeToAgent();
        this.agent.onChangedConnection(con);
    }

    public DtnmaContactBeacon getBeaconSnapshot(double now) {
        return this.agent.getBeaconSnapshot(now);
    }

    @Override
    public double getBeaconNeighborQueueRatio(DTNHost peer) {
        return this.agent.getBeaconNeighborQueueRatio(peer);
    }

    @Override
    public void update() {
        super.update();
        syncPrototypeToAgent();
        double now = SimClock.getTime();
        this.agent.onBeforeUpdate(now);

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        if (exchangeDeliverableMessages() != null) {
            return;
        }

        tryPolicyGovernedForwarding();
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
     * Policy-Governed Binary Spray & Cognitive Focus Forwarding.
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
        DtnmaManagedRouter[] peerManaged = new DtnmaManagedRouter[nReady];
        ProphetRouter[] prophetRouters = new ProphetRouter[nReady];
        double[] peerQueueRatios = new double[nReady];

        for (int i = 0; i < nReady; i++) {
            Connection con = readyConnections.get(i);
            DTNHost other = con.getOtherNode(getHost());
            peerHosts[i] = other;
            if (other != null && other.getRouter() instanceof DtnmaManagedRouter) {
                peerManaged[i] = (DtnmaManagedRouter) other.getRouter();
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

        int activeQuota = this.agent.getCurrentSprayQuota();
        boolean critOnly = (this.agent.getCurrentIngressMode() == INGRESS_CRITICAL_ONLY);

        // Linear O(N) 4-tier bucketing: avoids double-sorting all messages every tick
        // Buckets: 0=crit(L>1), 1=crit(L<=1), 2=bulk(L>1), 3=bulk(L<=1)
        @SuppressWarnings("unchecked")
        List<Message>[] buckets = (List<Message>[]) new List<?>[4];
        for (Message m : msgColl) {
            if (isSending(m.getId()) || this.agent.isAcked(m)) continue;
            boolean isCrit = isCriticalMessage(m);
            if (!isCrit && critOnly) continue;

            Integer copiesObj = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
            int copies = (copiesObj != null) ? copiesObj.intValue() : (isCrit ? DEFAULT_CRITICAL_QUOTA : activeQuota);
            int b = isCrit ? (copies > 1 ? 0 : 1) : (copies > 1 ? 2 : 3);
            if (buckets[b] == null) {
                buckets[b] = new ArrayList<Message>();
            }
            buckets[b].add(m);
        }

        EddTelemetrySampler edd = this.agent.getEddSampler();
        boolean bpEnabled = this.agent.isBackpressureEnabled();
        double bpDelta = this.agent.getCurrentBackpressureDelta();
        double focusMinPred = this.agent.getCurrentFocusMinPred();
        double focusMargin = this.agent.getCurrentFocusMargin();

        for (int b = 0; b < 4; b++) {
            List<Message> bucket = buckets[b];
            if (bucket == null || bucket.isEmpty()) continue;
            if (bucket.size() > 1) {
                super.sortByQueueMode(bucket);
            }

            for (Message m : bucket) {
                boolean isCritical = isCriticalMessage(m);
                Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
                if (copies == null) {
                    copies = isCritical ? DEFAULT_CRITICAL_QUOTA : activeQuota;
                    m.updateProperty(MSG_COPIES_PROPERTY, copies);
                }

                DTNHost dest = m.getTo();
                double myPred = -1.0;

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
                        if (!isCritical && bpEnabled && peerManaged[i] != null) {
                            if (peerQueue >= 0.65 && peerQueue >= myQueueRatio + bpDelta) {
                                continue;
                            }
                        }

                        int toSend = computeRelaySplit(copies, activeQuota, isCritical);
                        if (toSend <= 0) {
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
                            if (myPred < 0.0) {
                                myPred = (edd != null && dest != null) ? edd.getPredFor(dest) : 0.0;
                            }
                            double peerPred = 0.0;
                            if (peerManaged[i] != null && peerManaged[i].getDtnmaAgent() != null
                                    && peerManaged[i].getDtnmaAgent().getEddSampler() != null) {
                                peerPred = peerManaged[i].getDtnmaAgent().getEddSampler().getPredFor(dest);
                            } else if (prophetRouters[i] != null) {
                                peerPred = prophetRouters[i].getPredFor(dest);
                            }

                            double minAbsolutePred = isCritical ? 0.35 : Math.max(0.60, focusMinPred);
                            double requiredAdvantage = isCritical ? 0.20 : Math.max(0.30, focusMargin);
                            double maxPeerQ = isCritical ? 0.90 : 0.70;
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
    }

    /**
     * Computes total bytes belonging to active non-sending incoming transfers on this host
     * that are not destined for this host (i.e., will be buffered upon transfer completion).
     */
    public static long computeReservedIncomingBytes(
            DTNHost host, List<Connection> connections, Collection<Connection> sendingConnections) {
        if (host == null || connections == null) return 0L;
        long reserved = 0L;
        for (Connection con : connections) {
            if (con != null && con.isUp() && con.getMessage() != null
                    && (sendingConnections == null || !sendingConnections.contains(con))
                    && con.getMessage().getTo() != host) {
                reserved += con.getMessage().getSize();
            }
        }
        return reserved;
    }

    /**
     * Emergency room reclamation for critical creation under saturated all-critical buffers:
     * evicts only already-ACKed critical copies, never evicting un-ACKed critical messages.
     */
    public static boolean reclaimCriticalEmergency(
            ActiveRouter router, String copiesPropKey,
            Map<String, Boolean> deliveredAckSet, int size) {
        if (size > router.getBufferSize()) {
            return false;
        }
        while (router.getFreeBufferSize() < size) {
            Message ackedCand = null;

            for (Message m : router.getMessageCollection()) {
                if (router.isSending(m.getId()) || !isCriticalMessage(m)) continue;
                if (deliveredAckSet != null && deliveredAckSet.containsKey(m.getId())) {
                    ackedCand = m;
                    break;
                }
            }

            if (ackedCand != null) {
                router.deleteMessage(ackedCand.getId(), false);
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Reclaims any post-transfer physical buffer overshoot so occupancy stays <= 1.0.
     */
    public static void clampPostTransferDeficit(
            ActiveRouter router, Message incomingMsg, String copiesPropKey,
            Map<String, Boolean> deliveredAckSet) {
        if (router.getFreeBufferSize() < 0) {
            router.makeRoomForMessage(0);
            if (router.getFreeBufferSize() < 0 && incomingMsg != null) {
                if (!isCriticalMessage(incomingMsg) && router.hasMessage(incomingMsg.getId())) {
                    router.deleteMessage(incomingMsg.getId(), true);
                } else if (isCriticalMessage(incomingMsg)) {
                    if (!reclaimCriticalEmergency(router, copiesPropKey, deliveredAckSet, 0)
                            && router.hasMessage(incomingMsg.getId())) {
                        router.deleteMessage(incomingMsg.getId(), false);
                    }
                }
            }
        }
    }

    @Override
    public boolean createNewMessage(Message msg) {
        syncPrototypeToAgent();
        if (!this.agent.prepareRoomForNewMessage(msg, MSG_COPIES_PROPERTY)) {
            return false;
        }
        msg.setTtl(this.msgTtl);
        boolean isCrit = isCriticalMessage(msg);
        int initialCopies = isCrit ? DEFAULT_CRITICAL_QUOTA : this.agent.getCurrentSprayQuota();
        msg.addProperty(MSG_COPIES_PROPERTY, initialCopies);
        addToMessages(msg, true);
        this.agent.onAfterCreateNewMessage(msg, initialCopies);
        return true;
    }

    @Override
    public void deleteMessage(String id, boolean drop) {
        Message m = getMessage(id);
        this.agent.onDeleteMessage(m, drop);
        super.deleteMessage(id, drop);
    }

    @Override
    protected void transferDone(Connection con) {
        Message m = getMessage(con.getMessage().getId());
        if (m == null) return;

        DTNHost recipient = con.getOtherNode(getHost());
        if (m.getTo() == recipient) {
            this.agent.onTransferDone(con, m, MSG_COPIES_PROPERTY, 0);
            m.updateProperty(MSG_SPLIT_PROPERTY, 0);
            return;
        }

        Integer copies = (Integer) m.getProperty(MSG_COPIES_PROPERTY);
        Integer splitProp = (Integer) m.getProperty(MSG_SPLIT_PROPERTY);
        int actualSent = 0;
        if (copies != null && copies > 1) {
            actualSent = (splitProp != null && splitProp > 0)
                    ? Math.max(1, Math.min(copies - 1, splitProp))
                    : Math.max(1, (int) Math.floor(copies / 2.0));
            m.updateProperty(MSG_COPIES_PROPERTY, Math.max(1, copies - actualSent));
        } else if (copies != null && copies == 1) {
            deleteMessage(m.getId(), false);
        }
        m.updateProperty(MSG_SPLIT_PROPERTY, 0);

        this.agent.onTransferDone(con, m, MSG_COPIES_PROPERTY, actualSent);
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            this.agent.onMessageTransferred(m, from, MSG_COPIES_PROPERTY);
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

    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        Integer denied = this.agent.evaluateCheckReceiving(m, from, MSG_COPIES_PROPERTY);
        if (denied != null) {
            return denied.intValue();
        }
        return super.checkReceiving(m, from);
    }

    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        return this.agent.selectNextMessageToRemove(excludeMsgBeingSent, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List sortCriticalFirst(List list) {
        if (list == null || list.size() <= 1) {
            return list;
        }
        List crit = null;
        List nonCrit = null;
        for (Object o : list) {
            Message m = (o instanceof Tuple) ? ((Tuple<Message, Connection>) o).getKey() : (Message) o;
            if (isCriticalMessage(m)) {
                if (crit == null) crit = new ArrayList();
                crit.add(o);
            } else {
                if (nonCrit == null) nonCrit = new ArrayList();
                nonCrit.add(o);
            }
        }
        if (crit == null || nonCrit == null) {
            return list;
        }
        list.clear();
        list.addAll(crit);
        list.addAll(nonCrit);
        return list;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List sortByQueueMode(List list) {
        return sortCriticalFirst(super.sortByQueueMode(list));
    }

    /**
     * Returns strictly normalized battery State-of-Charge in [0.0, 1.0].
     */
    @Override
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
        if (id == null || id.isEmpty()) return false;
        char c = id.charAt(0);
        if (c != 'C' && c != 'F' && c != 'A' && c != 'T') {
            return false;
        }
        return id.startsWith("CRITICAL_FAULT")
            || id.startsWith("FAULT")
            || id.startsWith("CRIT")
            || id.startsWith("CTRL_UPDATE")
            || id.startsWith("ALARM")
            || id.startsWith("TELEM_CRIT");
    }

    public static boolean isDownlinkControlMessage(Message m) {
        if (m == null || m.getId() == null) return false;
        return m.getId().startsWith("CTRL_UPDATE");
    }

    public static boolean isTelemetryOrAlarmMessage(Message m) {
        if (m == null || m.getId() == null) return false;
        String id = m.getId();
        return !id.startsWith("CTRL_UPDATE")
            && (id.startsWith("TELEM_") || id.startsWith("ALARM") || isCriticalMessage(id));
    }

    // RFC 9675 Controls Actuators
    @Override public void setDynamicSprayQuota(int quota) { this.agent.setDynamicSprayQuota(quota); }
    @Override public void setIngressMode(int mode) { this.agent.setIngressMode(mode); }
    @Override public void setRoutineTelemetryInterval(double intervalSec) { this.agent.setRoutineTelemetryInterval(intervalSec); }
    @Override public void setBackpressureEnabled(boolean enabled) { this.agent.setBackpressureEnabled(enabled); }

    public long getBufferOccupancy() {
        return getBufferSize() - getFreeBufferSize();
    }

    @Override
    public double getBufferOccupancyRatio() {
        long bSize = getBufferSize();
        if (bSize <= 0) return 0.0;
        return (double) (bSize - getFreeBufferSize()) / bSize;
    }

    @Override
    public double getCriticalOccupancyRatio() {
        long bSize = getBufferSize();
        if (bSize <= 0) return 0.0;
        long critBytes = 0L;
        for (Message m : getMessageCollection()) {
            if (isCriticalMessage(m)) {
                critBytes += m.getSize();
            }
        }
        return (double) critBytes / bSize;
    }

    // Delegated Getters (for tests & reports)
    public int getLastActionIndex() { return this.agent.getLastActionIndex(); }
    public String getActionDescription(int action) { return this.agent.getActionDescription(action); }
    public String getActiveRuleAri() { return this.agent.getActiveRuleAri(); }
    public String getLastExplanation() { return this.agent.getLastExplanation(); }
    public EddTelemetrySampler getEddSampler() { return this.agent.getEddSampler(); }
    public boolean isAckFeedbackEnabled() { syncPrototypeToAgent(); return this.agent.isAckFeedbackEnabled(); }
    public int getLastProposedProfile() { return this.agent.getLastProposedProfile(); }
    public int getLastExecutedProfile() { return this.agent.getLastExecutedProfile(); }
    public boolean isLastShieldIntervened() { return this.agent.isLastShieldIntervened(); }
    public String getShieldTriggerAri() { return this.agent.getShieldTriggerAri(); }
    public DtnmaSafetyShield getSafetyShield() { return this.agent.getSafetyShield(); }
    public long getBeaconOverheadBytes() { return this.agent.getBeaconOverheadBytes(); }
    public long getAckExchanges() { return this.agent.getAckExchanges(); }
    public long getAckOverheadBytes() { return this.agent.getAckOverheadBytes(); }
    public double getMaxBufferOccupancy() { return this.agent.getMaxBufferOccupancy(); }
    public long getCriticalCreationRejected() { return this.agent.getCriticalCreationRejected(); }
    public long getBulkCreationRejected() { return this.agent.getBulkCreationRejected(); }
    public long getLocalCreationRejected() { return this.agent.getLocalCreationRejected(); }
    public long[] getProfileExecCounts() { return this.agent.getProfileExecCounts(); }
    public int getCurrentSprayQuota() { return this.agent.getCurrentSprayQuota(); }
    public List<StateBasedRule> getRuleDatabase() { return this.agent.getRuleDatabase(); }
    public int getLastFallbackProfile() { return this.agent.getLastFallbackProfile(); }
    public int getLastSafeMaskBits() { return this.agent.getLastSafeMaskBits(); }
    public int getAppliedControlVersion() { return this.agent.getAppliedControlVersion(); }
    public double getLastControlAdoptTime() { return this.agent.getLastControlAdoptTime(); }
    public String getLastControlAri() { return this.agent.getLastControlAri(); }
    public long getDecisionCpuNanosTotal() { return this.agent.getDecisionCpuNanosTotal(); }
    public long getDecisionCpuCount() { return this.agent.getDecisionCpuCount(); }
    public double getLastDecisionTime() { return this.agent.getLastDecisionTime(); }
    public double getLastDecisionQ() { return this.agent.getLastDecisionQ(); }
    public double getLastDecisionCritQ() { return this.agent.getLastDecisionCritQ(); }
    public double getLastDecisionDq() { return this.agent.getLastDecisionDq(); }
    public double getLastDecisionDensity() { return this.agent.getLastDecisionDensity(); }
    public double getLastDecisionSpeed() { return this.agent.getLastDecisionSpeed(); }
    public double getLastDecisionSoc() { return this.agent.getLastDecisionSoc(); }
    public int getLastDecisionActiveConn() { return this.agent.getLastDecisionActiveConn(); }
    public DtnmaConfig getConfig() { return this.agent.getConfig(); }

    @Override
    public MessageRouter replicate() {
        return new DtnmaRouter(this);
    }
}
