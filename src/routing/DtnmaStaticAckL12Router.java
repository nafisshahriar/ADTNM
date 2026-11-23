/*
 * DtnmaStaticAckL12Router.java
 * Static Control Oracle Candidate (L=12): Declarative SRL Rules with nominal spray quota L=12
 * + Delayed Delivery ACK Pruning (no RL, no gossip).
 */
package routing;

import core.Settings;

public class DtnmaStaticAckL12Router extends DtnmaStaticAckRouter {

    public static final String L12_RULES_FILE = "scenarios/rules_vdtn_L12.txt";

    public DtnmaStaticAckL12Router(Settings s) {
        super(s);
        loadRulesFromFile(L12_RULES_FILE);
    }

    protected DtnmaStaticAckL12Router(DtnmaStaticAckL12Router r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaStaticAckL12Router(this);
    }
}
