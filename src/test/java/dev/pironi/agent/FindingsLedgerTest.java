package dev.pironi.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ledger has rules of its own; they are asserted apart from the turn loop. */
class FindingsLedgerTest {
    @Test
    void placeholderFindingsNeverEnterTheLedger() {
        java.util.List<String> findings = new java.util.ArrayList<>();

        // Our own nag for a missing finding taught the model to answer with a hedge on turn one,
        // and the hedge then sat in the ledger for the whole run and was carried to the next.
        FindingsLedger.recordFinding(findings, "nothing conclusive yet");
        FindingsLedger.recordFinding(findings, "none");
        FindingsLedger.recordFinding(findings, "N/A");
        assertEquals(java.util.List.of(), findings);

        // A real finding that merely begins with one of those words is still a finding.
        FindingsLedger.recordFinding(findings,
                "Nothing in Outlook.sqlite: Mail and CalendarEvents both count 0 rows");
        assertEquals(1, findings.size());
    }
    @Test
    void inheritedFindingsAreNotTrimmedAwayByThisRunsOwn() {
        List<String> findings = new ArrayList<>();
        findings.add("calendar store.json is readable");
        findings.add("Outlook.sqlite is empty");
        int pinned = findings.size();

        for (int index = 0; index < 60; index++) {
            FindingsLedger.recordFinding(findings, "later fact " + index, pinned);
        }

        assertEquals(FindingsLedger.MAX_FINDINGS, findings.size());
        assertEquals("calendar store.json is readable", findings.get(0));
        assertEquals("Outlook.sqlite is empty", findings.get(1));
        assertEquals("later fact 59", findings.getLast());
    }
    @Test
    void aFullyInheritedLedgerStillAcceptsWhatThisRunLearns() {
        List<String> findings = new ArrayList<>();
        for (int index = 0; index < FindingsLedger.MAX_FINDINGS; index++) {
            findings.add("inherited " + index);
        }
        int pinned = findings.size();

        FindingsLedger.recordFinding(findings, "learned right now", pinned);

        assertEquals(FindingsLedger.MAX_FINDINGS, findings.size());
        assertEquals("learned right now", findings.getLast());
        // the oldest inherited entry gave way, not the new one
        assertEquals("inherited 1", findings.get(0));
    }
    @Test
    void inheritedFindingsAreOfferedForRecheckingNotAsSettled() {
        String ledger = FindingsLedger.findingsLedger(
                List.of("OSA logs are PII-redacted", "calendar store.json is readable"), 1);

        assertTrue(ledger.contains("Established here in earlier sessions"), ledger);
        assertTrue(ledger.contains("date each was last confirmed"), ledger);
        assertTrue(ledger.contains("Verify anything you are about to act on"), ledger);
        assertTrue(ledger.contains("Established so far (do not re-derive):"), ledger);
    }
    @Test
    void findingsAreDeduplicatedAndCapped() {
        List<String> findings = new ArrayList<>();
        FindingsLedger.recordFinding(findings, "mail store is HxStore.hxd");
        FindingsLedger.recordFinding(findings, "  mail store is HxStore.hxd  ");
        FindingsLedger.recordFinding(findings, "");
        FindingsLedger.recordFinding(findings, null);
        assertEquals(1, findings.size());

        for (int index = 0; index < 60; index++) {
            FindingsLedger.recordFinding(findings, "fact " + index);
        }
        assertEquals(FindingsLedger.MAX_FINDINGS, findings.size());
        assertEquals("fact 59", findings.getLast());
        assertEquals("", FindingsLedger.findingsLedger(new ArrayList<>()));
    }
}
