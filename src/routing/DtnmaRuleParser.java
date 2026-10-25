/*
 * DtnmaRuleParser.java
 * Declarative parser for RFC 9675 State-Based Rules (SRLs).
 * Allows users to define, modify, and audit autonomic rules via plain text files.
 */
package routing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DtnmaRuleParser {

    private static final Pattern CLAUSE_PATTERN = Pattern.compile(
            "([A-Z_0-9]+)\\s*(>=|<=|>|<|==|!=|=)\\s*([-+]?[0-9]*\\.?[0-9]+)");

    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "([A-Z_0-9]+)\\s*\\(([^)]*)\\)");

    /**
     * Parses rules from a file.
     */
    public static List<StateBasedRule> parseFile(String filePath) throws IOException {
        File f = new File(filePath);
        if (!f.exists()) {
            File alt1 = new File("the-one", filePath);
            if (alt1.exists()) {
                f = alt1;
            } else {
                File alt2 = new File("..", filePath);
                if (alt2.exists()) {
                    f = alt2;
                } else {
                    throw new IOException("DTNMA Rules file not found: " + filePath);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return parseString(sb.toString());
    }

    /**
     * Parses rules from a multi-line string.
     */
    public static List<StateBasedRule> parseString(String ruleText) throws IOException {
        List<StateBasedRule> rules = new ArrayList<StateBasedRule>();

        try (BufferedReader br = new BufferedReader(new StringReader(ruleText))) {
            String line;
            String curAri = null;
            int curPriority = 10;
            String curDesc = "";
            String curIf = null;
            String curThen = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }

                if (line.startsWith("RULE:")) {
                    // If previous rule block is ready, parse it
                    if (curAri != null && curIf != null && curThen != null) {
                        rules.add(buildRule(curAri, curPriority, curDesc, curIf, curThen));
                        curIf = null;
                        curThen = null;
                    }

                    // Parse RULE header: RULE: ARI | PRIORITY: int | DESC: text
                    String content = line.substring(5).trim();
                    String[] parts = content.split("\\|");
                    curAri = parts[0].trim();
                    curPriority = 10;
                    curDesc = curAri;

                    for (int i = 1; i < parts.length; i++) {
                        String p = parts[i].trim();
                        if (p.toUpperCase().startsWith("PRIORITY:")) {
                            curPriority = Integer.parseInt(p.substring(9).trim());
                        } else if (p.toUpperCase().startsWith("DESC:")) {
                            curDesc = p.substring(5).trim();
                        }
                    }
                } else if (line.startsWith("IF:")) {
                    curIf = line.substring(3).trim();
                } else if (line.startsWith("THEN:")) {
                    curThen = line.substring(5).trim();
                }
            }

            // Flush last rule
            if (curAri != null && curIf != null && curThen != null) {
                rules.add(buildRule(curAri, curPriority, curDesc, curIf, curThen));
            }
        }

        // Sort rules by priority descending
        Collections.sort(rules, (r1, r2) -> Integer.compare(r2.getPriority(), r1.getPriority()));
        return rules;
    }

    private static StateBasedRule buildRule(String ari, int priority, String desc, String ifClause, String thenClause) {
        // 1. Determine Conjunction (AND: &&) vs Disjunction (OR: ||)
        boolean isConjunction = true;
        if (ifClause.contains("||")) {
            isConjunction = false;
        }

        PredicateExpression predicate = new PredicateExpression(isConjunction);

        // Extract individual clauses
        Matcher m = CLAUSE_PATTERN.matcher(ifClause);
        while (m.find()) {
            String edd = m.group(1).trim();
            String opStr = m.group(2).trim();
            double thresh = Double.parseDouble(m.group(3).trim());

            predicate.addClause(edd, PredicateExpression.parseOperator(opStr), thresh);
        }

        StateBasedRule rule = new StateBasedRule(ari, desc, priority, predicate);

        // Parse Controls: CTRL_NAME(param); CTRL_NAME(param)
        String[] actions = thenClause.split(";");
        for (String act : actions) {
            act = act.trim();
            if (act.isEmpty()) continue;

            Matcher am = ACTION_PATTERN.matcher(act);
            if (am.find()) {
                String ctrlName = am.group(1).trim().toUpperCase();
                String paramStr = am.group(2).trim();

                if ("CTRL_SET_SPRAY_QUOTA".equals(ctrlName)) {
                    rule.addAction(ControlAction.setSprayQuota(Integer.parseInt(paramStr)));
                } else if ("CTRL_SET_INGRESS_MODE".equals(ctrlName)) {
                    int mode = 0;
                    if ("CRITICAL_ONLY".equalsIgnoreCase(paramStr) || "2".equals(paramStr)) mode = 2;
                    else if ("THROTTLE_BULK".equalsIgnoreCase(paramStr) || "1".equals(paramStr)) mode = 1;
                    rule.addAction(ControlAction.setIngressMode(mode));
                } else if ("CTRL_SET_TELEMETRY_INTERVAL".equals(ctrlName)) {
                    rule.addAction(ControlAction.setTelemetryInterval(Double.parseDouble(paramStr)));
                } else if ("CTRL_SET_BACKPRESSURE".equals(ctrlName)) {
                    rule.addAction(ControlAction.setBackpressure(Boolean.parseBoolean(paramStr)));
                }
            }
        }

        return rule;
    }
}
