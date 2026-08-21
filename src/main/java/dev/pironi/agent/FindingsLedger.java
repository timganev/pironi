package dev.pironi.agent;

import java.util.List;

/**
 * What the run has established, kept so a conclusion outlives the tool output it came from.
 *
 * <p>Read-only work leaves no artifact behind, so without a ledger it is simply repeated. The
 * bookkeeping is a bounded list with rules of its own - a hedge is not a finding, the oldest
 * earned entry goes first, and entries inherited from earlier runs are presented as dated rather
 * than as settled - which is a subject of its own rather than a corner of the turn loop.
 */
public final class FindingsLedger {
    private FindingsLedger() {
    }

    /** Findings carried, in memory and on disk. Two numbers silently dropped the oldest half. */
    public static final int MAX_FINDINGS = 40;

    /** Below this a tool result is an answer, not a discovery, and does not count as progress. */
    static final int MIN_INFORMATIVE_CHARACTERS = 40;

    public static void recordFinding(List<String> findings, String finding) {
        recordFinding(findings, finding, 0);
    }
    /** Drops the oldest earned finding when full; the first {@code pinned} entries never go. */
    public static void recordFinding(List<String> findings, String finding, int pinned) {
        if (finding == null || finding.isBlank()) return;
        String trimmed = finding.strip();
        // "nothing conclusive yet" is not a finding.
        if (uninformativeFinding(trimmed)) return;
        if (findings.contains(trimmed)) return;
        findings.add(trimmed);
        // Evict the oldest entry that may go - the first unpinned one, or when the pinned prefix
        // fills the budget the oldest pinned one, or the ledger freezes at what early runs learned.
        int evictAt = pinned >= MAX_FINDINGS ? 0 : pinned;
        if (findings.size() > MAX_FINDINGS) findings.remove(evictAt);
    }
    /** A hedge states that nothing was established, which an absent entry already says for free. */
    public static boolean uninformativeFinding(String finding) {
        String lower = finding.toLowerCase(java.util.Locale.ROOT);
        return finding.length() < MIN_INFORMATIVE_CHARACTERS
                && (lower.startsWith("nothing") || lower.startsWith("none")
                    || lower.startsWith("no finding") || lower.startsWith("n/a")
                    || lower.startsWith("unknown") || lower.startsWith("not yet")
                    || lower.startsWith("tbd"));
    }
    public static String findingsLedger(List<String> findings) {
        return findingsLedger(findings, 0);
    }
    /** The first {@code inherited} entries come from earlier runs here. */
    public static String findingsLedger(List<String> findings, int inherited) {
        if (findings.isEmpty()) return "";
        StringBuilder ledger = new StringBuilder();
        int carried = Math.min(inherited, findings.size());
        if (carried > 0) {
            ledger.append(System.lineSeparator())
                    .append("Established here in earlier sessions, with the date each was last "
                            + "confirmed. Verify anything you are about to act on:");
            for (String finding : findings.subList(0, carried)) {
                ledger.append(System.lineSeparator()).append("- ").append(finding);
            }
        }
        if (carried < findings.size()) {
            ledger.append(System.lineSeparator())
                    .append("Established so far (do not re-derive):");
            for (String finding : findings.subList(carried, findings.size())) {
                ledger.append(System.lineSeparator()).append("- ").append(finding);
            }
        }
        return ledger.toString();
    }
}
