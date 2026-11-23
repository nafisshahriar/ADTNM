/*
 * EnergyAwareEpidemicRouter.java
 * Policy-equivalent hand-coded baseline for Comparison A: extends stock ONE
 * EpidemicRouter and hard-codes ONLY the exact Tier 2 protection, ACK pruning,
 * and energy/congestion rules from rules_vdtn.txt without using DtnmaAgent,
 * declarative rule parsing, safety masking, RL, or Tier 3 hop caps.
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.Coord;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

public class EnergyAwareEpidemicRouter extends EpidemicRouter {

    private static final double ENERGY_MIN_SOC = 0.20;
    private static final double CRIT_QUEUE_HIGH = 0.50;
    private static final double SURGE_QUEUE = 0.88;
    private static final double SURGE_DRIFT = 0.008;
    private static final double JAM_QUEUE = 0.75;
    private static final double JAM_DENSITY = 5.0;
    private static final double JAM_SPEED = 2.0;

    private static final double BACKPRESSURE_DELTA = 0.15;
    private static final double PEER_CONGEST_Q = 0.94;
    private static final double BEACON_REFRESH_SEC = 5.0;
    private static final double DENSITY_WINDOW_SEC = 300.0;
    private static final int ACK_CACHE_SIZE = 512;
    private static final int ACK_MAX_PER_CONTACT = 32;

    private static final class PeerSnapshot {
        final double timestamp;
        final double queueRatio;
        final double energySoc;

        PeerSnapshot(double ts, double q, double soc) {
            this.timestamp = ts;
            this.queueRatio = q;
            this.energySoc = soc;
        }
    }

    private double initialEnergy = 1.0;
    private int ingressMode = DtnmaRouter.INGRESS_ACCEPT_ALL;
    private boolean backpressureEnabled = false;

    private double lastEvalTime = -1.0;
    private double lastSampleTime = 0.0;
    private double lastQueueRatio = 0.0;
    private double queueDrift = 0.0;
    private Coord lastLocation = null;
    private double nodeSpeed = 0.0;

    private double cachedCritOcc = 0.0;
    private boolean critOccDirty = true;

    private double maxBufferOccupancy = 0.0;
    private long criticalCreationRejected = 0L;
    private long bulkCreationRejected = 0L;

    private Map<Integer, Double> contactTimestamps;
    private Map<Integer, PeerSnapshot> peerSnapshots;
    private Map<String, Boolean> deliveredAckSet;

    public EnergyAwareEpidemicRouter(Settings s) {
        super(s);
        initCollections();
    }

    protected EnergyAwareEpidemicRouter(EnergyAwareEpidemicRouter r) {
        super(r);
        if (this.energy != null && this.energy.getEnergy() > 0.0) {
            this.initialEnergy = this.energy.getEnergy();
        } else {
            this.initialEnergy = 1.0;
        }
        initCollections();
    }

    private void initCollections() {
        this.contactTimestamps = new HashMap<Integer, Double>();
        this.peerSnapshots = new HashMap<Integer, PeerSnapshot>();
        this.deliveredAckSet = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > ACK_CACHE_SIZE;
            }
        };
    }

    public double getEnergySoc() {
        if (this.energy == null || this.initialEnergy <= 0.0) return 1.0;
        return Math.max(0.0, Math.min(1.0, this.energy.getEnergy() / this.initialEnergy));
    }

    public double getBufferOccupancyRatio() {
        long bSize = getBufferSize();
        if (bSize <= 0) return 0.0;
        return (double) (bSize - getFreeBufferSize()) / bSize;
    }

    private double getCachedCriticalOccupancyRatio() {
        if (this.critOccDirty) {
            long bSize = getBufferSize();
            if (bSize <= 0) {
                this.cachedCritOcc = 0.0;
            } else {
                long critBytes = 0L;
                for (Message m : getMessageCollection()) {
                    if (DtnmaRouter.isCriticalMessage(m)) {
                        critBytes += m.getSize();
                    }
                }
                this.cachedCritOcc = (double) critBytes / bSize;
            }
            this.critOccDirty = false;
        }
        return this.cachedCritOcc;
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        if (con == null || !con.isUp()) return;
        DTNHost host = getHost();
        if (host == null) return;
        DTNHost other = con.getOtherNode(host);
        if (other == null) return;

        double now = SimClock.getTime();
        contactTimestamps.put(other.getAddress(), now);

        if (other.getRouter() instanceof EnergyAwareEpidemicRouter) {
            EnergyAwareEpidemicRouter peer = (EnergyAwareEpidemicRouter) other.getRouter();
            PeerSnapshot cur = peerSnapshots.get(other.getAddress());
            if (cur == null || (now - cur.timestamp) >= 1.0) {
                peerSnapshots.put(other.getAddress(),
                        new PeerSnapshot(now, peer.getBufferOccupancyRatio(), peer.getEnergySoc()));
            }
            if (peer.deliveredAckSet != null && !peer.deliveredAckSet.isEmpty()) {
                int learned = 0;
                for (String msgId : peer.deliveredAckSet.keySet()) {
                    if (!this.deliveredAckSet.containsKey(msgId)) {
                        this.deliveredAckSet.put(msgId, Boolean.TRUE);
                        learned++;
                        if (hasMessage(msgId) && !isSending(msgId)) {
                            deleteMessage(msgId, false);
                        }
                        if (learned >= ACK_MAX_PER_CONTACT) {
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void update() {
        double now = SimClock.getTime();
        if (this.lastEvalTime < 0 || (now - this.lastEvalTime) >= 1.0) {
            refreshStaleBeacons(now);
            evaluateHardcodedRules(now);
            this.lastEvalTime = now;
        }
        super.update();
    }

    private void refreshStaleBeacons(double now) {
        DTNHost host = getHost();
        if (host == null) return;
        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(host);
            if (other != null && other.getRouter() instanceof EnergyAwareEpidemicRouter) {
                PeerSnapshot cur = peerSnapshots.get(other.getAddress());
                if (cur == null || (now - cur.timestamp) >= BEACON_REFRESH_SEC) {
                    EnergyAwareEpidemicRouter peer = (EnergyAwareEpidemicRouter) other.getRouter();
                    peerSnapshots.put(other.getAddress(),
                            new PeerSnapshot(now, peer.getBufferOccupancyRatio(), peer.getEnergySoc()));
                }
            }
        }
    }

    private void evaluateHardcodedRules(double now) {
        double dt = now - this.lastSampleTime;
        if (dt >= 0.5) {
            double qNow = getBufferOccupancyRatio();
            double rawDrift = Math.max(-0.25, Math.min(0.25, (qNow - this.lastQueueRatio) / dt));
            this.queueDrift = 0.30 * rawDrift + 0.70 * this.queueDrift;
            this.lastQueueRatio = qNow;

            DTNHost host = getHost();
            if (host != null && host.getLocation() != null) {
                Coord curLoc = host.getLocation();
                if (this.lastLocation != null) {
                    double instSpeed = curLoc.distance(this.lastLocation) / dt;
                    this.nodeSpeed = 0.25 * instSpeed + 0.75 * this.nodeSpeed;
                    this.lastLocation = curLoc.clone();
                } else {
                    this.lastLocation = curLoc.clone();
                }
            }
            this.lastSampleTime = now;
        }
        contactTimestamps.entrySet().removeIf(e -> (now - e.getValue()) > DENSITY_WINDOW_SEC);

        double q = getBufferOccupancyRatio();
        double critQ = getCachedCriticalOccupancyRatio();
        double soc = getEnergySoc();
        double density = contactTimestamps.size();

        if (soc < ENERGY_MIN_SOC || critQ >= CRIT_QUEUE_HIGH) {
            this.ingressMode = DtnmaRouter.INGRESS_CRITICAL_ONLY;
            this.backpressureEnabled = true;
        } else if (q >= SURGE_QUEUE && this.queueDrift >= SURGE_DRIFT) {
            this.ingressMode = DtnmaRouter.INGRESS_THROTTLE_BULK;
            this.backpressureEnabled = true;
        } else if (q >= JAM_QUEUE && density >= JAM_DENSITY && this.nodeSpeed < JAM_SPEED) {
            this.ingressMode = DtnmaRouter.INGRESS_ACCEPT_ALL;
            this.backpressureEnabled = true;
        } else {
            this.ingressMode = DtnmaRouter.INGRESS_ACCEPT_ALL;
            this.backpressureEnabled = false;
        }
    }

    @Override
    public boolean createNewMessage(Message msg) {
        boolean isCrit = DtnmaRouter.isCriticalMessage(msg);
        long reserved = DtnmaRouter.computeReservedIncomingBytes(
                getHost(), getConnections(), this.sendingConnections);
        long neededLong = (long) msg.getSize() + reserved;
        if (neededLong > getBufferSize()) {
            recordCreationRejected(isCrit);
            return false;
        }
        int needed = (int) neededLong;
        if (!makeRoomForMessage(needed)) {
            if (!isCrit || !DtnmaRouter.reclaimCriticalEmergency(this, null, this.deliveredAckSet, needed)) {
                recordCreationRejected(isCrit);
                return false;
            }
        }
        boolean ok = super.createNewMessage(msg);
        if (ok) {
            if (isCrit) {
                this.critOccDirty = true;
            }
            this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
        }
        return ok;
    }

    private void recordCreationRejected(boolean isCrit) {
        if (isCrit) this.criticalCreationRejected++;
        else this.bulkCreationRejected++;
    }

    @Override
    public void deleteMessage(String id, boolean drop) {
        Message m = getMessage(id);
        if (m != null && DtnmaRouter.isCriticalMessage(m)) {
            this.critOccDirty = true;
        }
        super.deleteMessage(id, drop);
    }

    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        if (this.deliveredAckSet.containsKey(m.getId())) {
            return MessageRouter.DENIED_OLD;
        }
        boolean isCrit = DtnmaRouter.isCriticalMessage(m);
        if (isCrit || this.ingressMode == DtnmaRouter.INGRESS_ACCEPT_ALL) {
            return super.checkReceiving(m, from);
        }
        double occ = getBufferOccupancyRatio();
        double critOcc = getCachedCriticalOccupancyRatio();
        boolean finiteTtl = (this.msgTtl > 0 && this.msgTtl != Message.INFINITE_TTL);
        if (this.ingressMode == DtnmaRouter.INGRESS_CRITICAL_ONLY) {
            if (critOcc >= 0.40
                    || (occ > 0.92 && finiteTtl && m.getTtl() < 0.75 * this.msgTtl)
                    || getEnergySoc() < ENERGY_MIN_SOC) {
                return MessageRouter.DENIED_NO_SPACE;
            }
        } else if (this.ingressMode == DtnmaRouter.INGRESS_THROTTLE_BULK) {
            if (critOcc >= 0.55
                    || (occ > 0.94 && finiteTtl && m.getTtl() < 0.50 * this.msgTtl)) {
                return MessageRouter.DENIED_NO_SPACE;
            }
        }
        return super.checkReceiving(m, from);
    }

    @Override
    protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
        Collection<Message> messages = getMessageCollection();
        if (!this.deliveredAckSet.isEmpty()) {
            for (Message m : messages) {
                if (excludeMsgBeingSent && isSending(m.getId())) continue;
                if (this.deliveredAckSet.containsKey(m.getId())) {
                    return m;
                }
            }
        }
        Message candidate = null;
        for (Message m : messages) {
            if (excludeMsgBeingSent && isSending(m.getId())) continue;
            if (DtnmaRouter.isCriticalMessage(m)) continue;
            if (candidate == null || m.getReceiveTime() < candidate.getReceiveTime()) {
                candidate = m;
            }
        }
        return candidate;
    }

    @Override
    protected void transferDone(Connection con) {
        Message m = getMessage(con.getMessage().getId());
        if (m != null && getHost() != null && m.getTo() == con.getOtherNode(getHost())) {
            this.deliveredAckSet.put(m.getId(), Boolean.TRUE);
        }
        super.transferDone(con);
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            if (DtnmaRouter.isCriticalMessage(m)) {
                this.critOccDirty = true;
            }
            DtnmaRouter.clampPostTransferDeficit(this, m, null, this.deliveredAckSet);
            this.maxBufferOccupancy = Math.max(this.maxBufferOccupancy, getBufferOccupancyRatio());
            if (m.getTo() == getHost()) {
                this.deliveredAckSet.put(m.getId(), Boolean.TRUE);
            }
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
        boolean[] peerCanTakeBulk = new boolean[nReady];
        double myQ = getBufferOccupancyRatio();
        boolean critOnly = (this.ingressMode == DtnmaRouter.INGRESS_CRITICAL_ONLY);

        for (int i = 0; i < nReady; i++) {
            DTNHost other = readyCons.get(i).getOtherNode(getHost());
            othRouters[i] = other.getRouter();
            boolean ok = !critOnly;
            if (ok && othRouters[i] instanceof EnergyAwareEpidemicRouter) {
                PeerSnapshot snap = peerSnapshots.get(other.getAddress());
                double peerQ = (snap != null) ? snap.queueRatio : 0.5;
                double peerSoc = (snap != null) ? snap.energySoc : 1.0;
                if (peerQ >= PEER_CONGEST_Q || peerSoc < ENERGY_MIN_SOC) {
                    ok = false;
                } else if (this.backpressureEnabled && peerQ >= 0.72 && peerQ >= myQ + BACKPRESSURE_DELTA) {
                    ok = false;
                }
            }
            peerCanTakeBulk[i] = ok;
        }

        List<Message> msgs = new ArrayList<Message>(getNrofMessages());
        for (Message m : getMessageCollection()) {
            if (isSending(m.getId()) || this.deliveredAckSet.containsKey(m.getId())) continue;
            if (critOnly && !DtnmaRouter.isCriticalMessage(m)) continue;
            msgs.add(m);
        }
        if (msgs.isEmpty()) {
            return null;
        }

        sortByQueueMode(msgs);
        for (Message m : msgs) {
            boolean isCrit = DtnmaRouter.isCriticalMessage(m);
            for (int i = 0; i < nReady; i++) {
                if (!isCrit && !peerCanTakeBulk[i]) continue;
                if (othRouters[i].hasMessage(m.getId())) continue;
                Connection con = readyCons.get(i);
                if (startTransfer(m, con) == RCV_OK) {
                    return con;
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

    public boolean isThrottleActive() { return ingressMode >= DtnmaRouter.INGRESS_THROTTLE_BULK; }
    public double getMaxBufferOccupancy() { return maxBufferOccupancy; }
    public long getCriticalCreationRejected() { return criticalCreationRejected; }
    public long getBulkCreationRejected() { return bulkCreationRejected; }
    public long getLocalCreationRejected() { return criticalCreationRejected + bulkCreationRejected; }

    @Override
    public EpidemicRouter replicate() {
        return new EnergyAwareEpidemicRouter(this);
    }
}
