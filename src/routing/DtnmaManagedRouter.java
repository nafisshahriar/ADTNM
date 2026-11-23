/*
 * DtnmaManagedRouter.java
 * Composition contract between any stock or DTNMA DTN router and its autonomic
 * DtnmaAgent management plane (RFC 9675 / RFC 7575).
 */
package routing;

import java.util.Collection;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;

public interface DtnmaManagedRouter {

    DtnmaAgent getDtnmaAgent();

    // Host / Buffer / Transfer adapters exposed for composition
    DTNHost getManagedHost();
    ActiveRouter getManagedActiveRouter();
    Collection<Message> getManagedMessageCollection();
    Message getManagedMessage(String id);
    long getManagedBufferSize();
    long getManagedFreeBufferSize();
    boolean hasManagedMessage(String id);
    boolean isManagedSending(String id);
    void deleteManagedMessage(String id, boolean drop);
    List<Connection> getManagedConnections();
    Collection<Connection> getManagedSendingConnections();
    int getManagedMsgTtl();
    int compareManagedByQueueMode(Message m1, Message m2);

    // Telemetry & Protection Queries
    double getEnergySoc();
    double getBufferOccupancyRatio();
    double getCriticalOccupancyRatio();
    double getBeaconNeighborQueueRatio(DTNHost peer);
    boolean supportsDynamicControlReconfig();

    // RFC 9675 Control Actuators (delegated to DtnmaAgent)
    void setDynamicSprayQuota(int quota);
    void setIngressMode(int mode);
    void setRoutineTelemetryInterval(double intervalSec);
    void setBackpressureEnabled(boolean enabled);
}
