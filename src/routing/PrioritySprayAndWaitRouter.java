/*
 * PrioritySprayAndWaitRouter.java
 * Fair Baseline Control: Stock binary Spray-and-Wait forwarding equipped with
 * Layer-1 critical HoL preemption, protected critical buffer eviction (0-drop),
 * and zero-overshoot physical buffer cap reservation (no RL, no gossip, no ACK pruning).
 */
package routing;

import java.util.Collection;
import java.util.List;

import core.DTNHost;
import core.Message;
import core.Settings;

public class PrioritySprayAndWaitRouter extends SprayAndWaitRouter {

    private double maxBufferOccupancy = 0.0;
    private long localCreationRejected = 0;

    public PrioritySprayAndWaitRouter(Settings s) {
        super(s);
    }

    protected PrioritySprayAndWaitRouter(PrioritySprayAndWaitRouter r) {
        super(r);
        this.maxBufferOccupancy = 0.0;
        this.localCreationRejected = 0;
    }

    @Override
    public boolean createNewMessage(Message msg) {
        long reserved = DtnmaRouter.computeReservedIncomingBytes(
                getHost(), getConnections(), this.sendingConnections);
        long neededLong = (long) msg.getSize() + reserved;
        if (neededLong > this.getBufferSize()) {
            this.localCreationRejected++;
            return false;
        }
        int needed = (int) neededLong;
        if (!makeRoomForMessage(needed)) {
            if (!DtnmaRouter.isCriticalMessage(msg)
                    || !DtnmaRouter.reclaimCriticalEmergency(this, MSG_COUNT_PROPERTY, null, needed)) {
                this.localCreationRejected++;
                return false;
            }
        }
        msg.setTtl(this.msgTtl);
        int copies = DtnmaRouter.isCriticalMessage(msg)
                ? DtnmaRouter.DEFAULT_CRITICAL_QUOTA
                : this.initialNrofCopies;
        msg.addProperty(MSG_COUNT_PROPERTY, Integer.valueOf(copies));
        addToMessages(msg, true);
        this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
        return true;
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            DtnmaRouter.clampPostTransferDeficit(this, m, MSG_COUNT_PROPERTY, null);
            this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
        }
        return m;
    }

    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        Collection<Message> messages = this.getMessageCollection();
        Message oldest = null;
        for (Message m : messages) {
            if (excludeMsgBeingSent && isSending(m.getId())) continue;
            if (DtnmaRouter.isCriticalMessage(m)) continue;

            if (oldest == null || m.getReceiveTime() < oldest.getReceiveTime()) {
                oldest = m;
            }
        }
        return oldest;
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected List sortByQueueMode(List list) {
        return DtnmaRouter.sortCriticalFirst(super.sortByQueueMode(list));
    }

    public double getBufferOccupancyRatio() {
        long bSize = getBufferSize();
        if (bSize <= 0) return 0.0;
        return (double) (bSize - getFreeBufferSize()) / bSize;
    }

    public double getMaxBufferOccupancy() { return maxBufferOccupancy; }
    public long getLocalCreationRejected() { return localCreationRejected; }

    @Override
    public PrioritySprayAndWaitRouter replicate() {
        return new PrioritySprayAndWaitRouter(this);
    }
}
