/*
 * ManagedEpidemicRouter.java
 * Stock ONE EpidemicRouter wrapped with DtnmaAgent by composition.
 * Preserves unrestricted epidemic dissemination under nominal conditions while
 * enforcing critical preservation, energy/congestion ingress & relay throttling,
 * critical-first scheduling, and delayed ACK copy pruning.
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

public class ManagedEpidemicRouter extends EpidemicRouter implements DtnmaManagedRouter {

    private final DtnmaAgent agent;
    private double initialEnergy = 1.0;

    public ManagedEpidemicRouter(Settings s) {
        super(s);
        this.agent = new DtnmaAgent(this, s);
    }

    protected ManagedEpidemicRouter(ManagedEpidemicRouter r) {
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
    protected Connection tryAllMessagesToAllConnections() {
        List<Connection> connections = getConnections();
        if (connections.isEmpty() || getNrofMessages() == 0) {
            return null;
        }

        List<Connection> readyCons = null;
        for (Connection con : connections) {
            if (!con.isReadyForTransfer()) continue;
            DTNHost other = con.getOtherNode(getHost());
            if (other == null || other.getRouter() == null) continue;
            MessageRouter othR = other.getRouter();
            if (othR instanceof ActiveRouter && ((ActiveRouter) othR).isTransferring()) continue;
            if (readyCons == null) {
                readyCons = new ArrayList<Connection>(connections.size());
            }
            readyCons.add(con);
        }
        if (readyCons == null || readyCons.isEmpty()) {
            return null;
        }

        int nReady = readyCons.size();
        MessageRouter[] othRouters = new MessageRouter[nReady];
        double[] peerQ = new double[nReady];
        double[] peerSoc = new double[nReady];
        boolean[] peerManaged = new boolean[nReady];
        for (int i = 0; i < nReady; i++) {
            DTNHost other = readyCons.get(i).getOtherNode(getHost());
            othRouters[i] = other.getRouter();
            peerManaged[i] = (othRouters[i] instanceof DtnmaManagedRouter);
            if (peerManaged[i]) {
                peerQ[i] = this.agent.getBeaconNeighborQueueRatio(other);
                peerSoc[i] = this.agent.getBeaconNeighborEnergySoc(other);
            } else {
                peerQ[i] = 0.5;
                peerSoc[i] = 1.0;
            }
        }

        boolean critOnly = (this.agent.getCurrentIngressMode() == DtnmaRouter.INGRESS_CRITICAL_ONLY);
        List<Message> msgs = new ArrayList<Message>(getNrofMessages());
        for (Message m : getMessageCollection()) {
            if (isSending(m.getId()) || this.agent.isAcked(m)) continue;
            if (critOnly && !DtnmaRouter.isCriticalMessage(m) && !this.agent.isEpidemicControlPriority(m)) {
                continue;
            }
            msgs.add(m);
        }
        if (msgs.isEmpty()) {
            return null;
        }

        sortByQueueMode(msgs);
        double myQ = getBufferOccupancyRatio();

        for (Message m : msgs) {
            boolean epiCtrl = this.agent.isEpidemicControlPriority(m);
            for (int i = 0; i < nReady; i++) {
                if (othRouters[i].hasMessage(m.getId())) continue;
                if (epiCtrl || this.agent.allowNonDirectBulkForward(
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
    @SuppressWarnings("unchecked")
    protected List sortByQueueMode(List list) {
        return DtnmaRouter.sortCriticalFirst(super.sortByQueueMode(list));
    }

    @Override
    public EpidemicRouter replicate() {
        return new ManagedEpidemicRouter(this);
    }
}
