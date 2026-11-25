/*
 * DtnmaStaticAckTunedRouter.java
 * Unhandicapped Static Control Baseline: Declarative SRL Rules (with dev-tuned
 * nominal spray quota L=10 from rules_vdtn_L10.txt) + Delayed Delivery ACK Pruning
 * (no RL, no gossip). Ensures RL is benchmarked against the strongest fair static policy.
 */
package routing;

import core.Settings;

public class DtnmaStaticAckTunedRouter extends DtnmaStaticAckRouter {

    public static final String TUNED_RULES_FILE = "scenarios/rules_vdtn_L10.txt";

    public DtnmaStaticAckTunedRouter(Settings s) {
        super(s);
        loadRulesFromFile(TUNED_RULES_FILE);
    }

    protected DtnmaStaticAckTunedRouter(DtnmaStaticAckTunedRouter r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaStaticAckTunedRouter(this);
    }
}
