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

    public ManagedMaxPropRouter(Settings s) {
        super(s);
        this.agent = new DtnmaAgent(this, s);
    }

    protected ManagedMaxPropRouter(ManagedMaxPropRouter r) {
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
        double now = SimClock.getTime();
        this.agent.onBeforeUpdate(now);
        this.agent.refreshStaleBeacons(now);
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
        if (m != null) {
            this.agent.onMessageTransferred(m, from, null);
        }
        return m;
    }

    @Override
    protected Tuple<Message, Connection> tryOtherMessages() {
        List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();
        Collection<Message> msgCollection = getMessageCollection();

        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            if (!(other.getRouter() instanceof MaxPropRouter)) continue;
            MaxPropRouter othRouter = (MaxPropRouter) other.getRouter();
            if (othRouter.isTransferring()) continue;
            Set<String> sentMsgIds = this.sentMessages.get(other);

            for (Message m : msgCollection) {
                if (this.agent.isAcked(m)) continue;
                if (othRouter.hasMessage(m.getId()) || m.getHops().contains(other)) continue;
                if (sentMsgIds != null && sentMsgIds.contains(m.getId())) continue;

                boolean epiCtrl = this.agent.isEpidemicControlPriority(m);
                if (epiCtrl || this.agent.allowNonDirectBulkForward(m, other, -1.0, -1.0)) {
                    messages.add(new Tuple<Message, Connection>(m, con));
                }
            }
        }

        if (messages.isEmpty()) {
            return null;
        }

        final Comparator<Tuple<Message, Connection>> baseComp =
                new MaxPropTupleComparator(calcThreshold());
        Collections.sort(messages, new Comparator<Tuple<Message, Connection>>() {
            @Override
            public int compare(Tuple<Message, Connection> t1, Tuple<Message, Connection> t2) {
                boolean c1 = DtnmaRouter.isCriticalMessage(t1.getKey());
                boolean c2 = DtnmaRouter.isCriticalMessage(t2.getKey());
                if (c1 && !c2) return -1;
                if (!c1 && c2) return 1;
                return baseComp.compare(t1, t2);
            }
        });

        return tryMessagesForConnected(messages);
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
