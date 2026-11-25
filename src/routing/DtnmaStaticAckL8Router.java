/*
 * DtnmaStaticAckL8Router.java
 * Static Control Oracle Candidate (L=8): Declarative SRL Rules with nominal spray quota L=8
 * + Delayed Delivery ACK Pruning (no RL, no gossip).
 */
package routing;

import core.Settings;

public class DtnmaStaticAckL8Router extends DtnmaStaticAckRouter {

    public static final String L8_RULES_FILE = "scenarios/rules_vdtn_L8.txt";

    public DtnmaStaticAckL8Router(Settings s) {
        super(s);
        loadRulesFromFile(L8_RULES_FILE);
    }

    protected DtnmaStaticAckL8Router(DtnmaStaticAckL8Router r) {
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return new DtnmaStaticAckL8Router(this);
    }
}
