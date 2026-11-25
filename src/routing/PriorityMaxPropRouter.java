/*
 * PriorityMaxPropRouter.java
 * Fair Baseline Control: Stock MaxProp routing equipped with Layer-1 critical HoL
 * preemption (via tryMessagesForConnected & sortByQueueMode), protected critical
 * buffer eviction (0 critical drops), and zero-overshoot physical buffer cap
 * reservation (requires zero modifications to base MaxPropRouter.java).
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
import util.Tuple;

public class PriorityMaxPropRouter extends MaxPropRouter {

    private double maxBufferOccupancy = 0.0;
    private long localCreationRejected = 0;

    public PriorityMaxPropRouter(Settings s) {
        super(s);
    }

    protected PriorityMaxPropRouter(PriorityMaxPropRouter r) {
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
                    || !DtnmaRouter.reclaimCriticalEmergency(this, null, null, needed)) {
                this.localCreationRejected++;
                return false;
            }
        }
        msg.setTtl(this.msgTtl);
        addToMessages(msg, true);
        this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
        return true;
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            DtnmaRouter.clampPostTransferDeficit(this, m, null, null);
            this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
        }
        return m;
    }

    /**
     * Never evicts critical telemetry; among remaining bulk messages selects the
     * lowest-priority candidate matching MaxProp's threshold / cost eviction rule.
     */
    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        Collection<Message> messages = this.getMessageCollection();
        List<Message> bulkCandidates = new ArrayList<Message>();
        for (Message m : messages) {
            if (excludeMsgBeingSent && isSending(m.getId())) {
                continue;
            }
            if (DtnmaRouter.isCriticalMessage(m)) {
                continue;
            }
            bulkCandidates.add(m);
        }
        if (bulkCandidates.isEmpty()) {
            return null;
        }

        final int threshold = this.calcThreshold();
        final DTNHost self = getHost();
        return Collections.max(bulkCandidates, new Comparator<Message>() {
            @Override
            public int compare(Message msg1, Message msg2) {
                if (msg1 == msg2) return 0;
                int hopc1 = msg1.getHopCount();
                int hopc2 = msg2.getHopCount();

                if (hopc1 < threshold && hopc2 >= threshold) return -1;
                if (hopc2 < threshold && hopc1 >= threshold) return 1;
                if (hopc1 < threshold && hopc2 < threshold) {
                    if (hopc1 != hopc2) return hopc1 - hopc2;
                    return compareByQueueMode(msg1, msg2);
                }

                double p1 = (self != null) ? getCost(self, msg1.getTo()) : Double.MAX_VALUE;
                double p2 = (self != null) ? getCost(self, msg2.getTo()) : Double.MAX_VALUE;
                double diff = p1 - p2;
                if (diff == 0.0) {
                    if (hopc1 != hopc2) return hopc1 - hopc2;
                    return compareByQueueMode(msg1, msg2);
                }
                return (diff < 0.0) ? -1 : 1;
            }
        });
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected List sortByQueueMode(List list) {
        return DtnmaRouter.sortCriticalFirst(super.sortByQueueMode(list));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Tuple<Message, Connection> tryMessagesForConnected(List<Tuple<Message, Connection>> tuples) {
        return super.tryMessagesForConnected(DtnmaRouter.sortCriticalFirst(tuples));
    }

    public double getBufferOccupancyRatio() {
        long bSize = getBufferSize();
        if (bSize <= 0) return 0.0;
        return (double) (bSize - getFreeBufferSize()) / bSize;
    }

    public double getMaxBufferOccupancy() { return maxBufferOccupancy; }
    public long getLocalCreationRejected() { return localCreationRejected; }

    @Override
    public MessageRouter replicate() {
        return new PriorityMaxPropRouter(this);
    }
}
