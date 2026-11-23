/*
 * ManagedProphetV2Router.java
 * Stock ONE ProphetV2Router wrapped with DtnmaAgent by composition.
 * Preserves PRoPHETv2 encounter/transitive/aging predictability while adding
 * critical admission/eviction protection, rule/RL forwarding selectivity,
 * critical-first scheduling, and delayed ACK pruning.
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
import core.Settings;
import core.SimClock;
import util.Tuple;

public class ManagedProphetV2Router extends ProphetV2Router implements DtnmaManagedRouter {

    private final DtnmaAgent agent;
    private double initialEnergy = 1.0;

    public ManagedProphetV2Router(Settings s) {
        super(s);
        this.agent = new DtnmaAgent(this, s);
    }

    protected ManagedProphetV2Router(ManagedProphetV2Router r) {
        super(r);
        this.agent = new DtnmaAgent(this, r.agent);
        if (this.energy != null && this.energy.getEnergy() > 0.0) {
            this.initialEnergy = this.energy.getEnergy();
        } else {
            this.initialEnergy = 1.0;
        }
    }

    @Override public DtnmaAgent getDtnmaAgent() { return this.agent; }
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
    @Override public double getBeaconNeighborQueueRatio(DTNHost peer) { return this.agent.getBeaconNeighborQueueRatio(peer); }
    @Override public boolean supportsDynamicControlReconfig() { return true; }

    @Override public void setDynamicSprayQuota(int quota) { this.agent.setDynamicSprayQuota(quota); }
    @Override public void setIngressMode(int mode) { this.agent.setIngressMode(mode); }
    @Override public void setRoutineTelemetryInterval(double intervalSec) { this.agent.setRoutineTelemetryInterval(intervalSec); }
    @Override public void setBackpressureEnabled(boolean enabled) { this.agent.setBackpressureEnabled(enabled); }

    @Override
    public double getEnergySoc() {
        if (this.energy == null || this.initialEnergy <= 0.0) return 1.0;
        return Math.max(0.0, Math.min(1.0, this.energy.getEnergy() / this.initialEnergy));
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
            if (DtnmaRouter.isCriticalMessage(m)) critBytes += m.getSize();
        }
        return (double) critBytes / bSize;
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        this.agent.onChangedConnection(con);
    }

    @Override
    public void update() {
        this.agent.onBeforeUpdate(SimClock.getTime());
        super.update();
    }

    @Override
    public boolean createNewMessage(Message msg) {
        if (!this.agent.prepareRoomForNewMessage(msg, null)) {
            return false;
        }
        boolean ok = super.createNewMessage(msg);
        if (ok) {
            this.agent.onAfterCreateNewMessage(msg, 1);
        }
        return ok;
    }

    @Override
    public void deleteMessage(String id, boolean drop) {
        this.agent.onDeleteMessage(getMessage(id), drop);
        super.deleteMessage(id, drop);
    }

    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        Integer denied = this.agent.evaluateCheckReceiving(m, from, null);
        if (denied != null) {
            return denied.intValue();
        }
        return super.checkReceiving(m, from);
    }

    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        return this.agent.selectNextMessageToRemove(excludeMsgBeingSent, null);
    }

    @Override
    protected void transferDone(Connection con) {
        Message m = getMessage(con.getMessage().getId());
        if (m != null) {
            this.agent.onTransferDone(con, m, null, 1);
        }
        super.transferDone(con);
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            this.agent.onMessageTransferred(m, from, null);
        }
        return m;
    }

    @Override
    protected Tuple<Message, Connection> tryOtherMessages() {
        List<Connection> connections = getConnections();
        if (connections.isEmpty() || getNrofMessages() == 0) {
            return null;
        }

        List<Connection> readyCons = null;
        for (Connection con : connections) {
            if (!con.isReadyForTransfer()) continue;
            DTNHost other = con.getOtherNode(getHost());
            if (other == null || !(other.getRouter() instanceof ProphetV2Router)) continue;
            ProphetV2Router othRouter = (ProphetV2Router) other.getRouter();
            if (othRouter.isTransferring()) continue;
            if (readyCons == null) {
                readyCons = new ArrayList<Connection>(connections.size());
            }
            readyCons.add(con);
        }
        if (readyCons == null || readyCons.isEmpty()) {
            return null;
        }

        int nReady = readyCons.size();
        ProphetV2Router[] othRouters = new ProphetV2Router[nReady];
        double[] peerQ = new double[nReady];
        double[] peerSoc = new double[nReady];
        boolean[] peerManaged = new boolean[nReady];

        double myQ = getBufferOccupancyRatio();
        boolean critOnly = (this.agent.getCurrentIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY);
        boolean autoActive = this.agent.isAutonomicControlActive();
        double minSoc = this.agent.getConfig().shieldEnergyMin;
        boolean bpEnabled = this.agent.isBackpressureEnabled();
        double bpDelta = this.agent.getCurrentBackpressureDelta();
        boolean throttleBulk = (this.agent.getCurrentIngressMode() == DtnmaRouter.INGRESS_THROTTLE_BULK);
        boolean[] peerCanTakeBulk = new boolean[nReady];

        for (int i = 0; i < nReady; i++) {
            DTNHost other = readyCons.get(i).getOtherNode(getHost());
            othRouters[i] = (ProphetV2Router) other.getRouter();
            peerManaged[i] = (othRouters[i] instanceof DtnmaManagedRouter);
            if (peerManaged[i]) {
                peerQ[i] = this.agent.getBeaconNeighborQueueRatio(other);
                peerSoc[i] = this.agent.getBeaconNeighborEnergySoc(other);
            } else {
                peerQ[i] = 0.5;
                peerSoc[i] = 1.0;
            }
            boolean ok = !critOnly;
            if (ok && peerManaged[i]) {
                if (autoActive && (peerQ[i] >= 0.94 || peerSoc[i] < minSoc)) ok = false;
                else if (bpEnabled && peerQ[i] >= 0.72 && peerQ[i] >= myQ + bpDelta) ok = false;
            }
            if (ok && throttleBulk && peerQ[i] >= 0.85) ok = false;
            peerCanTakeBulk[i] = ok;
        }

        Collection<Message> msgCollection = getMessageCollection();
        List<Tuple<Message, Connection>> critTuples = null;
        List<Tuple<Message, Connection>> bulkTuples = null;

        for (Message m : msgCollection) {
            if (isSending(m.getId()) || this.agent.isAcked(m)) continue;
            boolean epiCtrl = this.agent.isEpidemicControlPriority(m);
            boolean isCritOrCtrl = epiCtrl || DtnmaRouter.isCriticalMessage(m);
            if (!isCritOrCtrl && critOnly) continue;

            double myPred = -1.0;
            for (int i = 0; i < nReady; i++) {
                if (!isCritOrCtrl && !peerCanTakeBulk[i]) continue;
                if (othRouters[i].hasMessage(m.getId())) continue;

                if (epiCtrl) {
                    if (critTuples == null) critTuples = new ArrayList<Tuple<Message, Connection>>();
                    critTuples.add(new Tuple<Message, Connection>(m, readyCons.get(i)));
                } else {
                    if (myPred < 0.0) myPred = getPredFor(m.getTo());
                    double peerPred = othRouters[i].getPredFor(m.getTo());
                    if (peerPred >= myPred) {
                        if (isCritOrCtrl) {
                            if (critTuples == null) critTuples = new ArrayList<Tuple<Message, Connection>>();
                            critTuples.add(new Tuple<Message, Connection>(m, readyCons.get(i)));
                        } else if (this.agent.allowNonDirectBulkForward(
                                m, myQ, peerQ[i], peerSoc[i], peerManaged[i], myPred, peerPred)) {
                            if (bulkTuples == null) bulkTuples = new ArrayList<Tuple<Message, Connection>>();
                            bulkTuples.add(new Tuple<Message, Connection>(m, readyCons.get(i)));
                        }
                    }
                }
            }
        }

        final java.util.Map<Connection, java.util.Map<DTNHost, Double>> predCache =
                new java.util.HashMap<Connection, java.util.Map<DTNHost, Double>>(nReady);
        final Comparator<Tuple<Message, Connection>> predComp = new Comparator<Tuple<Message, Connection>>() {
            @Override
            public int compare(Tuple<Message, Connection> t1, Tuple<Message, Connection> t2) {
                Message m1 = t1.getKey();
                Message m2 = t2.getKey();
                double p1 = getCachedPeerPred(predCache, t1.getValue(), m1.getTo());
                double p2 = getCachedPeerPred(predCache, t2.getValue(), m2.getTo());
                int cmp = Double.compare(p2, p1);
                return (cmp != 0) ? cmp : compareByQueueMode(m1, m2);
            }
        };

        if (critTuples != null && !critTuples.isEmpty()) {
            if (critTuples.size() > 1) {
                Collections.sort(critTuples, predComp);
            }
            Tuple<Message, Connection> critRes = tryMessagesForConnected(critTuples);
            if (critRes != null) {
                return critRes;
            }
        }

        if (critOnly || bulkTuples == null || bulkTuples.isEmpty()) {
            return null;
        }
        if (bulkTuples.size() > 1) {
            Collections.sort(bulkTuples, predComp);
        }
        return tryMessagesForConnected(bulkTuples);
    }

    private double getCachedPeerPred(
            java.util.Map<Connection, java.util.Map<DTNHost, Double>> cache,
            Connection con, DTNHost dest) {
        java.util.Map<DTNHost, Double> map = cache.get(con);
        if (map == null) {
            map = new java.util.HashMap<DTNHost, Double>();
            cache.put(con, map);
        }
        Double cached = map.get(dest);
        if (cached != null) return cached.doubleValue();
        double val = ((ProphetV2Router) con.getOtherNode(getHost()).getRouter()).getPredFor(dest);
        map.put(dest, val);
        return val;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List sortByQueueMode(List list) {
        return DtnmaRouter.sortCriticalFirst(super.sortByQueueMode(list));
    }

    @Override
    public MessageRouter replicate() {
        return new ManagedProphetV2Router(this);
    }
}
