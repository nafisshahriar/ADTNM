/*
 * DtnmaStaticAckRouter.java
 * OppNDA-compatible ablation preset: RFC 9675-inspired Declarative Static Rules
 * + Delayed Delivery ACK Vaccine Pruning (isolates RL + Gossip contribution from ACK pruning).
 */
package routing;

import core.Settings;

public class DtnmaStaticAckRouter extends DtnmaRouter {

    public DtnmaStaticAckRouter(Settings s) {
        super(s);
        this.intelligenceMode = IntelligenceMode.STATIC_RULES;
        this.gossipEnabled = false;
        this.ackFeedbackEnabled = true;
        this.config.intelligenceMode = this.intelligenceMode;
        this.config.gossipEnabled = false;
        this.config.ackFeedbackEnabled = true;
    }

    protected DtnmaStaticAckRouter(DtnmaStaticAckRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaStaticAckRouter(this);
    }
}
