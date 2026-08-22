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

    /**
     * A ceiling on one finding.
     *
     * <p>Only the number of entries was ever bounded, never the size of one. A model that answered
     * "remember" with a pasted document stored the document, and every tool result for the life of
     * that workspace carried it. A fact that will not fit in a few sentences is not what this is
     * for; the session transcript is where the long version belongs.
     */
    static final int MAX_FINDING_CHARACTERS = 500;

    /** And a ceiling on all of them together, because forty short ones still add up. */
    static final int MAX_LEDGER_CHARACTERS = 6_000;

    public static void recordFinding(List<String> findings, String finding) {
        recordFinding(findings, finding, 0);
    }

    /** Drops the oldest earned finding when full; the first {@code pinned} entries never go. */
    public static void recordFinding(List<String> findings, String finding, int pinned) {
        if (finding == null || finding.isBlank()) return;
        String trimmed = clip(finding.strip());
        // "nothing conclusive yet" is not a finding.
        if (uninformativeFinding(trimmed)) return;
        if (findings.contains(trimmed)) return;
        for (int i = 0; i < findings.size(); i++) {
            if (!restates(findings.get(i), trimmed)) continue;
            // The same fact, written out further: keep the fuller wording where the fact already is.
            if (trimmed.length() > findings.get(i).length()) findings.set(i, trimmed);
            return;
        }
        findings.add(trimmed);
        // Evict the oldest entry that may go - the first unpinned one, or when the pinned prefix
        // fills the budget the oldest pinned one, or the ledger freezes at what early runs learned.
        int evictAt = pinned >= MAX_FINDINGS ? 0 : pinned;
        if (findings.size() > MAX_FINDINGS) findings.remove(evictAt);
    }

    /** Cuts an over-long finding down, and says so rather than leaving a sentence hanging. */
    public static String clip(String finding) {
        if (finding == null || finding.length() <= MAX_FINDING_CHARACTERS) return finding;
        return finding.substring(0, MAX_FINDING_CHARACTERS) + " …(cut)";
    }

    /**
     * True when one text is the other's opening — one fact written out further, not two facts.
     * A model that pastes a growing prefix of the same document turn after turn otherwise fills
     * the ledger with copies of it, and every copy then rides on every prompt that follows.
     */
    public static boolean restates(String held, String candidate) {
        if (held == null || candidate == null) return false;
        String one = held.strip();
        String other = candidate.strip();
        if (one.isEmpty() || other.isEmpty()) return false;
        String shorter = one.length() <= other.length() ? one : other;
        String longer = one.length() <= other.length() ? other : one;
        // Two brief findings can open alike by chance; a long one that opens alike is the same one.
        return shorter.length() >= MIN_INFORMATIVE_CHARACTERS && longer.startsWith(shorter);
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
        int room = MAX_LEDGER_CHARACTERS;
        int dropped = 0;
        if (carried > 0) {
            ledger.append(System.lineSeparator())
                    .append("Established here in earlier sessions, with the date each was last "
                            + "confirmed. Verify anything you are about to act on:");
            dropped += append(ledger, findings.subList(0, carried), room);
            room = MAX_LEDGER_CHARACTERS - ledger.length();
        }
        if (carried < findings.size()) {
            ledger.append(System.lineSeparator())
                    .append("Established so far (do not re-derive):");
            dropped += append(ledger, findings.subList(carried, findings.size()), room);
        }
        // Silence here would read as "that is all there was", which is the one thing it is not.
        if (dropped > 0) {
            ledger.append(System.lineSeparator())
                    .append("- (").append(dropped)
                    .append(" further findings not shown: the ledger is over its budget. ")
                    .append("/findings lists them all.)");
        }
        return ledger.toString();
    }

    /** @return how many were left out for want of room */
    private static int append(StringBuilder ledger, List<String> findings, int room) {
        int written = 0;
        for (String finding : findings) {
            int cost = finding.length() + 3;
            if (cost > room && written > 0) return findings.size() - written;
            room -= cost;
            written++;
            ledger.append(System.lineSeparator()).append("- ").append(finding);
        }
        return 0;
    }
}
