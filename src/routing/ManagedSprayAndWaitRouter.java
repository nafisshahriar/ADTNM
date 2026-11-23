/*
 * ManagedSprayAndWaitRouter.java
 * Stock ONE SprayAndWaitRouter wrapped with DtnmaAgent by composition.
 * Governs spray quota L, ingress admission, critical buffer protection,
 * critical-first queue/transfer scheduling, and delayed ACK pruning.
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import util.Tuple;

public class ManagedSprayAndWaitRouter extends SprayAndWaitRouter implements DtnmaManagedRouter {

    private final DtnmaAgent agent;
    private double initialEnergy = 1.0;

    public ManagedSprayAndWaitRouter(Settings s) {
        super(s);
        this.agent = new DtnmaAgent(this, s);
    }

    protected ManagedSprayAndWaitRouter(ManagedSprayAndWaitRouter r) {
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
        if (!this.agent.prepareRoomForNewMessage(msg, MSG_COUNT_PROPERTY)) {
            return false;
        }
        msg.setTtl(this.msgTtl);
        boolean isCrit = DtnmaRouter.isCriticalMessage(msg);
        int copies = isCrit
                ? DtnmaRouter.DEFAULT_CRITICAL_QUOTA
                : this.agent.getEffectiveBulkSprayQuota(this.initialNrofCopies);
        msg.addProperty(MSG_COUNT_PROPERTY, copies);
        addToMessages(msg, true);
        this.agent.onAfterCreateNewMessage(msg, copies);
        return true;
    }

    @Override
    public void deleteMessage(String id, boolean drop) {
        this.agent.onDeleteMessage(getMessage(id), drop);
        super.deleteMessage(id, drop);
    }

    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        Integer denied = this.agent.evaluateCheckReceiving(m, from, MSG_COUNT_PROPERTY);
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
    protected List<Message> getMessagesWithCopiesLeft() {
        List<Message> list = new ArrayList<Message>();
        int quota = this.agent.getEffectiveBulkSprayQuota(this.initialNrofCopies);
        boolean critOnly = this.agent.isCriticalOnlyBulkSheddingActive();
        for (Message m : getMessageCollection()) {
            if (this.agent.isAcked(m)) continue;
            if (this.agent.isEpidemicControlPriority(m)) {
                list.add(m);
                continue;
            }
            boolean isCrit = DtnmaRouter.isCriticalMessage(m);
            if (!isCrit && (quota <= 1 || critOnly)) continue;
            Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
            if (nrofCopies != null && nrofCopies > 1) {
                list.add(m);
            }
        }
        return list;
    }

    @Override
    protected Connection tryMessagesToConnections(
            List<Message> messages, List<Connection> connections) {
        if (messages == null || messages.isEmpty() || connections == null || connections.isEmpty()) {
            return null;
        }
        List<Connection> readyCons = null;
        for (Connection con : connections) {
            if (!con.isReadyForTransfer()) continue;
            DTNHost other = con.getOtherNode(getHost());
            if (other == null || other.getRouter() == null) continue;
            if (other.getRouter() instanceof ActiveRouter
                    && ((ActiveRouter) other.getRouter()).isTransferring()) continue;
            if (readyCons == null) {
                readyCons = new ArrayList<Connection>(connections.size());
            }
            readyCons.add(con);
        }
        if (readyCons == null || readyCons.isEmpty()) {
            return null;
        }

        int nReady = readyCons.size();
        double myQ = getBufferOccupancyRatio();
        double[] peerQ = new double[nReady];
        double[] peerSoc = new double[nReady];
        boolean[] peerManaged = new boolean[nReady];
        boolean[] peerCanTakeBulk = new boolean[nReady];
        for (int i = 0; i < nReady; i++) {
            DTNHost other = readyCons.get(i).getOtherNode(getHost());
            peerManaged[i] = (other.getRouter() instanceof DtnmaManagedRouter);
            if (peerManaged[i]) {
                peerQ[i] = this.agent.getBeaconNeighborQueueRatio(other);
                peerSoc[i] = this.agent.getBeaconNeighborEnergySoc(other);
            } else {
                peerQ[i] = 0.5;
                peerSoc[i] = 1.0;
            }
            peerCanTakeBulk[i] = this.agent.isPeerSelectableForBulk(myQ, peerQ[i], peerSoc[i], peerManaged[i]);
        }

        DtnmaRouter.sortCriticalFirst(messages);
        for (Message m : messages) {
            boolean isCritOrCtrl = DtnmaRouter.isCriticalMessage(m) || this.agent.isEpidemicControlPriority(m);
            for (int i = 0; i < nReady; i++) {
                if (!isCritOrCtrl && !peerCanTakeBulk[i]) continue;
                if (this.agent.allowNonDirectBulkForward(
                        m, myQ, peerQ[i], peerSoc[i], peerManaged[i], -1.0, -1.0)) {
                    Connection con = readyCons.get(i);
                    if (startTransfer(m, con) == RCV_OK) {
                        return con;
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected void transferDone(Connection con) {
        Message msg = getMessage(con.getMessage().getId());
        if (msg == null) return;
        DTNHost recipient = con.getOtherNode(getHost());
        if (msg.getTo() == recipient) {
            this.agent.onTransferDone(con, msg, MSG_COUNT_PROPERTY, 0);
            return;
        }
        int beforeCopies = 1;
        Integer cp = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);
        if (cp != null) beforeCopies = cp.intValue();

        if (this.agent.isEpidemicControlPriority(msg)) {
            this.agent.onTransferDone(con, msg, MSG_COUNT_PROPERTY, 1);
            return;
        }

        if (beforeCopies > 1) {
            int afterCopies = isBinary
                    ? (int) Math.ceil(beforeCopies / 2.0)
                    : Math.max(1, beforeCopies - 1);
            int cap = DtnmaRouter.isCriticalMessage(msg)
                    ? DtnmaRouter.DEFAULT_CRITICAL_QUOTA
                    : this.agent.getEffectiveBulkSprayQuota(this.initialNrofCopies);
            if (!DtnmaRouter.isCriticalMessage(msg) && beforeCopies > cap) {
                afterCopies = Math.max(1, (int) Math.ceil(cap / 2.0));
            }
            msg.updateProperty(MSG_COUNT_PROPERTY, afterCopies);
            this.agent.onTransferDone(con, msg, MSG_COUNT_PROPERTY, Math.max(1, beforeCopies - afterCopies));
        } else {
            this.agent.onTransferDone(con, msg, MSG_COUNT_PROPERTY, 1);
        }
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            if (this.agent.isEpidemicControlPriority(m)) {
                m.updateProperty(MSG_COUNT_PROPERTY, DtnmaRouter.DEFAULT_CRITICAL_QUOTA);
            } else if (!DtnmaRouter.isCriticalMessage(m)) {
                Integer cp = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
                int quota = Math.max(1, this.agent.getEffectiveBulkSprayQuota(this.initialNrofCopies));
                if (cp != null && cp > quota) {
                    m.updateProperty(MSG_COUNT_PROPERTY, Math.max(1, quota / 2));
                }
            }
            this.agent.onMessageTransferred(m, from, MSG_COUNT_PROPERTY);
        }
        return m;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List sortByQueueMode(List list) {
        return DtnmaRouter.sortCriticalFirst(super.sortByQueueMode(list));
    }

    @Override
    public SprayAndWaitRouter replicate() {
        return new ManagedSprayAndWaitRouter(this);
    }
}
