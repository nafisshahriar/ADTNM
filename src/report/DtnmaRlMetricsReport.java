/*
 * DtnmaRlMetricsReport.java
 * OppNDA-compatible key:value summary reporter for Phase 1 Shielded CMDP RL,
 * RFC 9675 Safety Shield intervention decay, control overhead, and Option A drop split.
 */
package report;

import java.util.List;
import java.util.Locale;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.SimScenario;
import routing.DtnmaRouter;
import routing.DtnmaSafetyShield;
import routing.ShieldedCmdpRlAgent;

public class DtnmaRlMetricsReport extends Report implements MessageListener {

    private int criticalCreated = 0;
    private int criticalDelivered = 0;
    private int criticalBufferDropped = 0;
    private int criticalTtlExpired = 0;

    private int bulkCreated = 0;
    private int bulkDelivered = 0;
    private int bulkBufferDropped = 0;
    private int bulkTtlExpired = 0;

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
            if (DtnmaRouter.isCriticalMessage(m)) {
                criticalDelivered++;
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
                if (isTtlExpired) criticalTtlExpired++;
                else criticalBufferDropped++;
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
        int dtnmaNodes = 0;

        List<DTNHost> hosts = SimScenario.getInstance().getHosts();
        if (hosts != null) {
            for (DTNHost h : hosts) {
                if (h.getRouter() instanceof DtnmaRouter) {
                    DtnmaRouter r = (DtnmaRouter) h.getRouter();
                    dtnmaNodes++;
                    energySocSum += r.getEnergySoc();
                    beaconOverheadBytes += r.getBeaconOverheadBytes();
                    gossipExchanges += r.getGossipExchanges();
                    gossipOverheadBytes += r.getGossipOverheadBytes();
                    ackExchanges += r.getAckExchanges();
                    ackOverheadBytes += r.getAckOverheadBytes();

                    DtnmaSafetyShield shield = r.getSafetyShield();
                    if (shield != null) {
                        rlDecisionsTotal += shield.getTotalDecisions();
                        shieldInterventionsTotal += shield.getTotalInterventions();
                        if (shield.getTotalDecisions() > 0) {
                            shieldEarlySum += shield.getEarlyInterventionRate();
                            shieldLateSum += shield.getLateInterventionRate();
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
                }
            }
        }

        double critDeliveryProb = criticalCreated > 0 ? (double) criticalDelivered / criticalCreated : 0.0;
        double shieldIntRate = rlDecisionsTotal > 0 ? (double) shieldInterventionsTotal / rlDecisionsTotal : 0.0;
        double shieldEarlyRate = shieldNodes > 0 ? shieldEarlySum / shieldNodes : 0.0;
        double shieldLateRate = shieldNodes > 0 ? shieldLateSum / shieldNodes : 0.0;
        double rlAvgReward = rlNodes > 0 ? rlAvgRewardSum / rlNodes : 0.0;
        double rlAvgCost = rlNodes > 0 ? rlAvgCostSum / rlNodes : 0.0;
        double dualLambdaAvg = rlNodes > 0 ? dualLambdaSum / rlNodes : 0.0;
        double avgEnergySoc = dtnmaNodes > 0 ? energySocSum / dtnmaNodes : 1.0;

        StringBuilder sb = new StringBuilder();
        sb.append("RL metrics for scenario ").append(getScenarioName()).append("\n");
        sb.append(String.format(Locale.US, "sim_time: %.4f\n", getSimTime()));
        sb.append("critical_created: ").append(criticalCreated).append("\n");
        sb.append("critical_delivered: ").append(criticalDelivered).append("\n");
        sb.append("critical_buffer_dropped: ").append(criticalBufferDropped).append("\n");
        sb.append("critical_ttl_expired: ").append(criticalTtlExpired).append("\n");
        sb.append(String.format(Locale.US, "critical_delivery_prob: %.4f\n", critDeliveryProb));
        sb.append("bulk_created: ").append(bulkCreated).append("\n");
        sb.append("bulk_delivered: ").append(bulkDelivered).append("\n");
        sb.append("bulk_buffer_dropped: ").append(bulkBufferDropped).append("\n");
        sb.append("bulk_ttl_expired: ").append(bulkTtlExpired).append("\n");
        sb.append("rl_decisions_total: ").append(rlDecisionsTotal).append("\n");
        sb.append("shield_interventions_total: ").append(shieldInterventionsTotal).append("\n");
        sb.append(String.format(Locale.US, "shield_intervention_rate: %.4f\n", shieldIntRate));
        sb.append(String.format(Locale.US, "shield_early_rate: %.4f\n", shieldEarlyRate));
        sb.append(String.format(Locale.US, "shield_late_rate: %.4f\n", shieldLateRate));
        sb.append(String.format(Locale.US, "rl_avg_reward: %.4f\n", rlAvgReward));
        sb.append(String.format(Locale.US, "rl_avg_cost: %.4f\n", rlAvgCost));
        sb.append(String.format(Locale.US, "dual_lambda_avg: %.4f\n", dualLambdaAvg));
        sb.append("beacon_overhead_bytes: ").append(beaconOverheadBytes).append("\n");
        sb.append("gossip_exchanges: ").append(gossipExchanges).append("\n");
        sb.append("gossip_overhead_bytes: ").append(gossipOverheadBytes).append("\n");
        sb.append("ack_exchanges: ").append(ackExchanges).append("\n");
        sb.append("ack_overhead_bytes: ").append(ackOverheadBytes).append("\n");
        sb.append(String.format(Locale.US, "avg_energy_remaining_soc: %.4f", avgEnergySoc));

        write(sb.toString());
        super.done();
    }
}
