/*
 * DtnmaStaticAckL2Router.java
 * Static Control Oracle Candidate (L=2): Declarative SRL Rules with nominal spray quota L=2
 * + Delayed Delivery ACK Pruning (no RL, no gossip).
 */
package routing;

import core.Settings;

public class DtnmaStaticAckL2Router extends DtnmaStaticAckRouter {

    public static final String L2_RULES_FILE = "scenarios/rules_vdtn_L2.txt";

    public DtnmaStaticAckL2Router(Settings s) {
        super(s);
        loadRulesFromFile(L2_RULES_FILE);
    }

    protected DtnmaStaticAckL2Router(DtnmaStaticAckL2Router r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaStaticAckL2Router(this);
    }
}
