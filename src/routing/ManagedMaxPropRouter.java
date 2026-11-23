/*
 * ManagedMaxPropRouter.java
 * Stock ONE MaxPropRouter wrapped with DtnmaAgent by composition.
 * Preserves MaxProp Dijkstra path-cost & hop-threshold ordering while adding
 * critical preservation, rule/RL energy/congestion throttling, critical-first
 * scheduling, and delayed ACK pruning.
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import util.Tuple;

public class ManagedMaxPropRouter extends MaxPropRouter implements DtnmaManagedRouter {

    private final DtnmaAgent agent;
    private double initialEnergy = 1.0;
    private java.util.Map<DTNHost, java.util.Map<DTNHost, Double>> persistentPeerCostCache;

    public ManagedMaxPropRouter(Settings s) {
        super(s);
        this.agent = new DtnmaAgent(this, s);
        this.persistentPeerCostCache = new java.util.HashMap<DTNHost, java.util.Map<DTNHost, Double>>();
    }

    protected ManagedMaxPropRouter(ManagedMaxPropRouter r) {
        super(r);
        this.agent = new DtnmaAgent(this, r.agent);
        this.persistentPeerCostCache = new java.util.HashMap<DTNHost, java.util.Map<DTNHost, Double>>();
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

    private void invalidatePeerCostCache() {
        if (this.persistentPeerCostCache != null && !this.persistentPeerCostCache.isEmpty()) {
            this.persistentPeerCostCache.clear();
        }
    }

    @Override
    public void changedConnection(Connection con) {
        if (con != null && con.isUp()) {
            invalidatePeerCostCache();
        }
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
            invalidatePeerCostCache();
            this.agent.onAfterCreateNewMessage(msg, 1);
        }
        return ok;
    }

    @Override
    public void deleteMessage(String id, boolean drop) {
        this.agent.onDeleteMessage(getMessage(id), drop);
        super.deleteMessage(id, drop);
        invalidatePeerCostCache();
    }

    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        Integer denied = this.agent.evaluateCheckReceiving(m, from, null);
        if (denied != null) {
            return denied.intValue();
        }
        if (this.ackedMessageIds != null && this.ackedMessageIds.contains(m.getId())) {
            return DENIED_OLD;
        }
        return super.checkReceiving(m, from);
    }

    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        final Comparator<Message> mpComp = new MaxPropComparator(this.calcThreshold());
        return this.agent.selectNextMessageToRemove(excludeMsgBeingSent, mpComp);
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
        invalidatePeerCostCache();
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
            if (other == null || !(other.getRouter() instanceof MaxPropRouter)) continue;
            MaxPropRouter othRouter = (MaxPropRouter) other.getRouter();
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
        DTNHost[] peerHosts = new DTNHost[nReady];
        MaxPropRouter[] othRouters = new MaxPropRouter[nReady];
        @SuppressWarnings("unchecked")
        Set<String>[] sentSets = (Set<String>[]) new Set<?>[nReady];
        double[] peerQ = new double[nReady];
        double[] peerSoc = new double[nReady];
        boolean[] peerManaged = new boolean[nReady];

        double myQ = getBufferOccupancyRatio();
        boolean critOnly = this.agent.isCriticalOnlyBulkSheddingActive();
        boolean[] peerCanTakeBulk = new boolean[nReady];

        for (int i = 0; i < nReady; i++) {
            DTNHost other = readyCons.get(i).getOtherNode(getHost());
            peerHosts[i] = other;
            othRouters[i] = (MaxPropRouter) other.getRouter();
            sentSets[i] = this.sentMessages.get(other);
            peerManaged[i] = (othRouters[i] instanceof DtnmaManagedRouter);
            if (peerManaged[i]) {
                peerQ[i] = this.agent.getBeaconNeighborQueueRatio(other);
                peerSoc[i] = this.agent.getBeaconNeighborEnergySoc(other);
            } else {
                peerQ[i] = 0.5;
                peerSoc[i] = 1.0;
            }
            peerCanTakeBulk[i] = this.agent.isPeerSelectableForBulk(myQ, peerQ[i], peerSoc[i], peerManaged[i]);
        }

        Collection<Message> msgCollection = getMessageCollection();
        List<Tuple<Message, Connection>> critTuples = null;
        List<Tuple<Message, Connection>> bulkTuples = null;

        for (Message m : msgCollection) {
            if (isSending(m.getId()) || this.agent.isAcked(m)) continue;
            boolean isCritOrCtrl = DtnmaRouter.isCriticalMessage(m) || this.agent.isEpidemicControlPriority(m);
            if (!isCritOrCtrl && critOnly) continue;

            for (int i = 0; i < nReady; i++) {
                if (!isCritOrCtrl && !peerCanTakeBulk[i]) continue;
                if (othRouters[i].hasMessage(m.getId()) || m.getHops().contains(peerHosts[i])) continue;
                if (sentSets[i] != null && sentSets[i].contains(m.getId())) continue;

                if (isCritOrCtrl) {
                    if (critTuples == null) critTuples = new ArrayList<Tuple<Message, Connection>>();
                    critTuples.add(new Tuple<Message, Connection>(m, readyCons.get(i)));
                } else if (this.agent.allowNonDirectBulkForward(
                        m, myQ, peerQ[i], peerSoc[i], peerManaged[i], -1.0, -1.0)) {
                    if (bulkTuples == null) bulkTuples = new ArrayList<Tuple<Message, Connection>>();
                    bulkTuples.add(new Tuple<Message, Connection>(m, readyCons.get(i)));
                }
            }
        }

        if (critTuples != null && !critTuples.isEmpty()) {
            if (critTuples.size() > 1) {
                sortTuplesByCachedMaxProp(critTuples);
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
            sortTuplesByCachedMaxProp(bulkTuples);
        }
        return tryMessagesForConnected(bulkTuples);
    }

    /**
     * Exact MaxPropTupleComparator ordering reusing persistentPeerCostCache across ticks
     * until changedConnection(isUp) / messageTransferred / create / delete invalidates costs.
     */
    private void sortTuplesByCachedMaxProp(List<Tuple<Message, Connection>> tuples) {
        final int threshold = calcThreshold();
        if (this.persistentPeerCostCache == null) {
            this.persistentPeerCostCache = new java.util.HashMap<DTNHost, java.util.Map<DTNHost, Double>>(8);
        }
        final java.util.Map<DTNHost, java.util.Map<DTNHost, Double>> costCache = this.persistentPeerCostCache;

        // Populate missing (fromHost, dest) entries grouped by fromHost so lastCostFrom stays hot
        java.util.Map<DTNHost, List<Tuple<Message, Connection>>> byPeerMiss = null;
        for (Tuple<Message, Connection> t : tuples) {
            if (t.getKey().getHopCount() < threshold) continue;
            DTNHost fromHost = t.getValue().getOtherNode(getHost());
            java.util.Map<DTNHost, Double> cachedPeer = costCache.get(fromHost);
            if (cachedPeer != null && cachedPeer.containsKey(t.getKey().getTo())) {
                continue;
            }
            if (byPeerMiss == null) {
                byPeerMiss = new java.util.HashMap<DTNHost, List<Tuple<Message, Connection>>>(8);
            }
            List<Tuple<Message, Connection>> bucket = byPeerMiss.get(fromHost);
            if (bucket == null) {
                bucket = new ArrayList<Tuple<Message, Connection>>();
                byPeerMiss.put(fromHost, bucket);
            }
            bucket.add(t);
        }
        if (byPeerMiss != null) {
            for (java.util.Map.Entry<DTNHost, List<Tuple<Message, Connection>>> entry : byPeerMiss.entrySet()) {
                DTNHost fromHost = entry.getKey();
                java.util.Map<DTNHost, Double> destCosts = costCache.get(fromHost);
                if (destCosts == null) {
                    destCosts = new java.util.HashMap<DTNHost, Double>();
                    costCache.put(fromHost, destCosts);
                }
                for (Tuple<Message, Connection> t : entry.getValue()) {
                    DTNHost dest = t.getKey().getTo();
                    if (!destCosts.containsKey(dest)) {
                        destCosts.put(dest, getCost(fromHost, dest));
                    }
                }
            }
        }

        Collections.sort(tuples, new Comparator<Tuple<Message, Connection>>() {
            @Override
            public int compare(Tuple<Message, Connection> t1, Tuple<Message, Connection> t2) {
                Message msg1 = t1.getKey();
                Message msg2 = t2.getKey();
                if (msg1 == msg2 && t1.getValue() == t2.getValue()) {
                    return 0;
                }
                int hopc1 = msg1.getHopCount();
                int hopc2 = msg2.getHopCount();
                if (hopc1 < threshold && hopc2 >= threshold) return -1;
                if (hopc2 < threshold && hopc1 >= threshold) return 1;
                if (hopc1 < threshold && hopc2 < threshold) return hopc1 - hopc2;

                DTNHost f1 = t1.getValue().getOtherNode(getHost());
                DTNHost f2 = t2.getValue().getOtherNode(getHost());
                java.util.Map<DTNHost, Double> cMap1 = costCache.get(f1);
                java.util.Map<DTNHost, Double> cMap2 = costCache.get(f2);
                double p1 = (cMap1 != null && cMap1.containsKey(msg1.getTo()))
                        ? cMap1.get(msg1.getTo()) : getCost(f1, msg1.getTo());
                double p2 = (cMap2 != null && cMap2.containsKey(msg2.getTo()))
                        ? cMap2.get(msg2.getTo()) : getCost(f2, msg2.getTo());

                if (p1 - p2 == 0) {
                    return (hopc1 == hopc2) ? compareByQueueMode(msg1, msg2) : (hopc1 - hopc2);
                }
                return (p1 - p2 < 0) ? -1 : 1;
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List sortByQueueMode(List list) {
        return DtnmaRouter.sortCriticalFirst(super.sortByQueueMode(list));
    }

    @Override
    public MessageRouter replicate() {
        return new ManagedMaxPropRouter(this);
    }
}
