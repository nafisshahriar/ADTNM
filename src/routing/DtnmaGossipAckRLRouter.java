/*
 * DtnmaGossipAckRLRouter.java
 * OppNDA-compatible ablation preset: Shielded CMDP RL + Gossip Consensus + Delayed ACK Feedback.
 */
package routing;

import core.Settings;

public class DtnmaGossipAckRLRouter extends DtnmaRouter {

    public DtnmaGossipAckRLRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.SHIELDED_CMDP_RL;
        this.gossipEnabled = true;
        this.ackFeedbackEnabled = true;
    }

    protected DtnmaGossipAckRLRouter(DtnmaGossipAckRLRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaGossipAckRLRouter(this);
    }
}
