/*
 * DtnmaRlMetricsReport.java
 * OppNDA-compatible key:value summary reporter for Single-Critic Shielded RL,
 * RFC 9675-inspired Safety Shield intervention decay, control overhead, drop split,
 * and cross-router EnergyModel battery telemetry.
 */
package report;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.SimScenario;
import core.UpdateListener;
import routing.DtnmaPolicyProfile;
import routing.DtnmaRouter;
import routing.DtnmaSafetyShield;
import routing.PriorityMaxPropRouter;
import routing.PrioritySprayAndWaitRouter;
import routing.ShieldedCmdpRlAgent;
import routing.util.EnergyModel;

public class DtnmaRlMetricsReport extends Report implements MessageListener, UpdateListener {

    private int criticalCreated = 0;
    private int criticalDelivered = 0;
    private int criticalBufferDropped = 0;
    private int criticalTtlExpired = 0;

    private int bulkCreated = 0;
    private int bulkDelivered = 0;
    private int bulkBufferDropped = 0;
    private int bulkTtlExpired = 0;

    private Set<String> criticalDeliveredIds = new HashSet<String>();
    private Set<String> criticalBufferDroppedIds = new HashSet<String>();
    private Map<Integer, Double> initialEnergyByHost = new HashMap<Integer, Double>();
    private boolean capturedInitialEnergy = false;
    private long deliveredPayloadBytes = 0;

    public DtnmaRlMetricsReport() {
        super();
        init();
    }

    @Override
    protected void init() {
        super.init();
        this.criticalCreated = 0;
        this.criticalDelivered = 0;
        this.criticalBufferDropped = 0;
        this.criticalTtlExpired = 0;
        this.bulkCreated = 0;
        this.bulkDelivered = 0;
        this.bulkBufferDropped = 0;
        this.bulkTtlExpired = 0;
        if (this.criticalDeliveredIds == null) this.criticalDeliveredIds = new HashSet<String>();
        else this.criticalDeliveredIds.clear();
        if (this.criticalBufferDroppedIds == null) this.criticalBufferDroppedIds = new HashSet<String>();
        else this.criticalBufferDroppedIds.clear();
        if (this.initialEnergyByHost == null) this.initialEnergyByHost = new HashMap<Integer, Double>();
        else this.initialEnergyByHost.clear();
        this.capturedInitialEnergy = false;
        this.deliveredPayloadBytes = 0;
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        if (this.capturedInitialEnergy || hosts == null || hosts.isEmpty()) {
            return;
        }
        boolean foundAny = false;
        for (DTNHost h : hosts) {
            Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
            if (eObj instanceof Double) {
                this.initialEnergyByHost.put(h.getAddress(), (Double) eObj);
                foundAny = true;
            }
        }
        if (foundAny || getSimTime() > 2.0) {
            this.capturedInitialEnergy = true;
        }
    }

    @Override
    public void newMessage(Message m) {
        if (isWarmup()) {
            addWarmupID(m.getId());
            return;
        }
        if (DtnmaRouter.isCriticalMessage(m)) {
            criticalCreated++;
        } else {
            bulkCreated++;
        }
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
        if (isWarmupID(m.getId())) {
            return;
        }
        if (finalTarget) {
            deliveredPayloadBytes += m.getSize();
            if (DtnmaRouter.isCriticalMessage(m)) {
                criticalDelivered++;
                criticalDeliveredIds.add(m.getId());
            } else {
                bulkDelivered++;
            }
        }
    }

    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        if (isWarmupID(m.getId())) {
            return;
        }
        if (dropped) {
            boolean isCrit = DtnmaRouter.isCriticalMessage(m);
            boolean isTtlExpired = (m.getTtl() <= 0);
            if (isCrit) {
                if (isTtlExpired) {
                    criticalTtlExpired++;
                } else {
                    criticalBufferDropped++;
                    criticalBufferDroppedIds.add(m.getId());
                }
            } else {
                if (isTtlExpired) bulkTtlExpired++;
                else bulkBufferDropped++;
            }
        }
    }

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {}

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {}

    @Override
    public void done() {
        long rlDecisionsTotal = 0;
        long shieldInterventionsTotal = 0;
        double shieldEarlySum = 0.0;
        double shieldLateSum = 0.0;
        double shieldGreedySum = 0.0;
        double shieldGreedyEarlySum = 0.0;
        double shieldGreedyLateSum = 0.0;
        int shieldNodes = 0;

        double rlAvgRewardSum = 0.0;
        double rlAvgCostSum = 0.0;
        double dualLambdaSum = 0.0;
        int rlNodes = 0;

        long beaconOverheadBytes = 0;
        long gossipExchanges = 0;
        long gossipOverheadBytes = 0;
        long ackExchanges = 0;
        long ackOverheadBytes = 0;

        double energySocSum = 0.0;
        double allEnergySocSum = 0.0;
        int energyNodes = 0;
        long depletedNodes = 0;
        double maxBufferOccupancy = 0.0;
        long localCreationRejected = 0;
        int dtnmaNodes = 0;
        long[] profileCounts = new long[DtnmaPolicyProfile.NUM_ACTIONS];
        long totalProfileExecs = 0;

        List<DTNHost> hosts = SimScenario.getInstance().getHosts();
        if (hosts != null) {
            for (DTNHost h : hosts) {
                if (h.getRouter() instanceof DtnmaRouter) {
                    DtnmaRouter r = (DtnmaRouter) h.getRouter();
                    dtnmaNodes++;
                    energySocSum += r.getEnergySoc();
                    if (r.getMaxBufferOccupancy() > maxBufferOccupancy) {
                        maxBufferOccupancy = r.getMaxBufferOccupancy();
                    }
                    localCreationRejected += r.getLocalCreationRejected();
                    beaconOverheadBytes += r.getBeaconOverheadBytes();
                    gossipExchanges += r.getGossipExchanges();
                    gossipOverheadBytes += r.getGossipOverheadBytes();
                    ackExchanges += r.getAckExchanges();
                    ackOverheadBytes += r.getAckOverheadBytes();

                    long[] pCounts = r.getProfileExecCounts();
                    if (pCounts != null) {
                        for (int a = 0; a < DtnmaPolicyProfile.NUM_ACTIONS && a < pCounts.length; a++) {
                            profileCounts[a] += pCounts[a];
                            totalProfileExecs += pCounts[a];
                        }
                    }

                    DtnmaSafetyShield shield = r.getSafetyShield();
                    if (shield != null) {
                        rlDecisionsTotal += shield.getTotalDecisions();
                        shieldInterventionsTotal += shield.getTotalInterventions();
                        if (shield.getTotalDecisions() > 0) {
                            shieldEarlySum += shield.getEarlyInterventionRate();
                            shieldLateSum += shield.getLateInterventionRate();
                            shieldGreedySum += shield.getGreedyInterventionRate();
                            shieldGreedyEarlySum += shield.getGreedyEarlyInterventionRate();
                            shieldGreedyLateSum += shield.getGreedyLateInterventionRate();
                            shieldNodes++;
                        }
                    }

                    ShieldedCmdpRlAgent agent = r.getRlAgent();
                    if (agent != null) {
                        rlAvgRewardSum += agent.getAvgReward();
                        rlAvgCostSum += agent.getAvgCost();
                        dualLambdaSum += agent.getLambda();
                        rlNodes++;
                    }
                } else if (h.getRouter() instanceof PrioritySprayAndWaitRouter) {
                    PrioritySprayAndWaitRouter pr = (PrioritySprayAndWaitRouter) h.getRouter();
                    if (pr.getMaxBufferOccupancy() > maxBufferOccupancy) {
                        maxBufferOccupancy = pr.getMaxBufferOccupancy();
                    }
                    localCreationRejected += pr.getLocalCreationRejected();
                } else if (h.getRouter() instanceof PriorityMaxPropRouter) {
                    PriorityMaxPropRouter pm = (PriorityMaxPropRouter) h.getRouter();
                    if (pm.getMaxBufferOccupancy() > maxBufferOccupancy) {
                        maxBufferOccupancy = pm.getMaxBufferOccupancy();
                    }
                    localCreationRejected += pm.getLocalCreationRejected();
                }

                Object eObj = h.getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
                if (eObj instanceof Double) {
                    double curE = (Double) eObj;
                    energyNodes++;
                    if (curE <= 1e-9) {
                        depletedNodes++;
                    }
                    double soc;
                    if (h.getRouter() instanceof DtnmaRouter) {
                        soc = ((DtnmaRouter) h.getRouter()).getEnergySoc();
                    } else {
                        Double initE = (this.initialEnergyByHost != null) ? this.initialEnergyByHost.get(h.getAddress()) : null;
                        if (initE != null && initE > 0.0) {
                            soc = Math.max(0.0, Math.min(1.0, curE / initE));
                        } else {
                            soc = curE > 0.0 ? 1.0 : 0.0;
                        }
                    }
                    allEnergySocSum += soc;
                }
            }
        }

        int uniqueCriticalLost = 0;
        for (String droppedId : criticalBufferDroppedIds) {
            if (!criticalDeliveredIds.contains(droppedId)) {
                uniqueCriticalLost++;
            }
        }

        double critDeliveryProb = criticalCreated > 0 ? (double) criticalDelivered / criticalCreated : 0.0;
        double shieldIntRate = rlDecisionsTotal > 0 ? (double) shieldInterventionsTotal / rlDecisionsTotal : 0.0;
        double shieldEarlyRate = shieldNodes > 0 ? shieldEarlySum / shieldNodes : 0.0;
        double shieldLateRate = shieldNodes > 0 ? shieldLateSum / shieldNodes : 0.0;
        double shieldGreedyRate = shieldNodes > 0 ? shieldGreedySum / shieldNodes : 0.0;
        double shieldGreedyEarlyRate = shieldNodes > 0 ? shieldGreedyEarlySum / shieldNodes : 0.0;
        double shieldGreedyLateRate = shieldNodes > 0 ? shieldGreedyLateSum / shieldNodes : 0.0;
        double rlAvgReward = rlNodes > 0 ? rlAvgRewardSum / rlNodes : 0.0;
        double rlAvgCost = rlNodes > 0 ? rlAvgCostSum / rlNodes : 0.0;
        double dualLambdaAvg = rlNodes > 0 ? dualLambdaSum / rlNodes : 0.0;
        double avgEnergySoc = energyNodes > 0
                ? (allEnergySocSum / energyNodes)
                : (dtnmaNodes > 0 ? energySocSum / dtnmaNodes : 1.0);
        long totalCtrlBytes = beaconOverheadBytes + gossipOverheadBytes + ackOverheadBytes;
        double ctrlToPayloadRatio = (double) totalCtrlBytes / Math.max(1.0, (double) deliveredPayloadBytes);

        StringBuilder sb = new StringBuilder();
        sb.append("RL metrics for scenario ").append(getScenarioName()).append("\n");
        sb.append(String.format(Locale.US, "sim_time: %.4f\n", getSimTime()));
        sb.append("critical_created: ").append(criticalCreated).append("\n");
        sb.append("critical_delivered: ").append(criticalDelivered).append("\n");
        sb.append("critical_buffer_dropped: ").append(criticalBufferDropped).append("\n");
        sb.append("unique_critical_lost: ").append(uniqueCriticalLost).append("\n");
        sb.append("critical_ttl_expired: ").append(criticalTtlExpired).append("\n");
        sb.append(String.format(Locale.US, "critical_delivery_prob: %.4f\n", critDeliveryProb));
        sb.append("bulk_created: ").append(bulkCreated).append("\n");
        sb.append("bulk_delivered: ").append(bulkDelivered).append("\n");
        sb.append("bulk_buffer_dropped: ").append(bulkBufferDropped).append("\n");
        sb.append("bulk_ttl_expired: ").append(bulkTtlExpired).append("\n");
        sb.append("delivered_payload_bytes: ").append(deliveredPayloadBytes).append("\n");
        sb.append("local_creation_rejected: ").append(localCreationRejected).append("\n");
        sb.append(String.format(Locale.US, "max_buffer_occupancy: %.4f\n", maxBufferOccupancy));
        sb.append("rl_decisions_total: ").append(rlDecisionsTotal).append("\n");
        sb.append("shield_interventions_total: ").append(shieldInterventionsTotal).append("\n");
        sb.append(String.format(Locale.US, "shield_intervention_rate: %.4f\n", shieldIntRate));
        sb.append(String.format(Locale.US, "shield_early_rate: %.4f\n", shieldEarlyRate));
        sb.append(String.format(Locale.US, "shield_late_rate: %.4f\n", shieldLateRate));
        sb.append(String.format(Locale.US, "shield_greedy_intervention_rate: %.4f\n", shieldGreedyRate));
        sb.append(String.format(Locale.US, "shield_greedy_early_rate: %.4f\n", shieldGreedyEarlyRate));
        sb.append(String.format(Locale.US, "shield_greedy_late_rate: %.4f\n", shieldGreedyLateRate));
        sb.append(String.format(Locale.US, "rl_avg_reward: %.4f\n", rlAvgReward));
        sb.append(String.format(Locale.US, "rl_avg_cost: %.4f\n", rlAvgCost));
        sb.append(String.format(Locale.US, "dual_lambda_avg: %.4f\n", dualLambdaAvg));
        sb.append("beacon_overhead_bytes: ").append(beaconOverheadBytes).append("\n");
        sb.append("gossip_exchanges: ").append(gossipExchanges).append("\n");
        sb.append("gossip_overhead_bytes: ").append(gossipOverheadBytes).append("\n");
        sb.append("ack_exchanges: ").append(ackExchanges).append("\n");
        sb.append("ack_overhead_bytes: ").append(ackOverheadBytes).append("\n");
        sb.append(String.format(Locale.US, "control_to_payload_ratio: %.6f\n", ctrlToPayloadRatio));
        for (int a = 0; a < DtnmaPolicyProfile.NUM_ACTIONS; a++) {
            double frac = totalProfileExecs > 0 ? (double) profileCounts[a] / totalProfileExecs : 0.0;
            sb.append(String.format(Locale.US, "profile_%d_frac: %.4f\n", a, frac));
        }
        sb.append(String.format(Locale.US, "avg_energy_soc: %.4f\n", avgEnergySoc));
        sb.append("depleted_nodes_count: ").append(depletedNodes).append("\n");
        sb.append(String.format(Locale.US, "avg_energy_remaining_soc: %.4f", avgEnergySoc));

        write(sb.toString());
        super.done();
    }
}
