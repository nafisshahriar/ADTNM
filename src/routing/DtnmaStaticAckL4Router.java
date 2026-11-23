/*
 * DtnmaStaticAckL4Router.java
 * Static Control Oracle Candidate (L=4): Declarative SRL Rules with nominal spray quota L=4
 * + Delayed Delivery ACK Pruning (no RL, no gossip).
 */
package routing;

import core.Settings;

public class DtnmaStaticAckL4Router extends DtnmaStaticAckRouter {

    public static final String L4_RULES_FILE = "scenarios/rules_vdtn_L4.txt";

    public DtnmaStaticAckL4Router(Settings s) {
        super(s);
        loadRulesFromFile(L4_RULES_FILE);
    }

    protected DtnmaStaticAckL4Router(DtnmaStaticAckL4Router r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaStaticAckL4Router(this);
    }
}
