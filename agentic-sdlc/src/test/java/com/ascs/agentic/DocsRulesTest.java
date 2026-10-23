package com.ascs.agentic;

import com.ascs.agentic.agents.DocsAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deterministic documentation rules the docs agent must satisfy before its result is accepted. */
class DocsRulesTest {

    static final String GOOD = """
            # Link deletion

            ## User stories
            - As an API client, I want to delete a link, so that it no longer redirects (AC-1, AC-2)
            - As an operator, I want deletions audited, so that changes are traceable (AC-3)

            ## API changes
            """;

    @Test
    void storiesTracingEveryCriterionPass() {
        assertEquals(List.of(), DocsAgent.docRules(GOOD, List.of("AC-1", "AC-2", "AC-3"), false, false));
    }

    @Test
    void missingStoriesOrUntracedCriteriaAreRejected() {
        List<String> noStories = DocsAgent.docRules("# Change\nSome text about AC-1.\n", List.of("AC-1"), false, false);
        assertTrue(noStories.get(0).contains("'## User stories'"), noStories.toString());

        List<String> untraced = DocsAgent.docRules(GOOD, List.of("AC-1", "AC-4", "AC-10"), false, false);
        assertEquals(List.of("the user stories do not cover acceptance criteria [AC-4, AC-10]"), untraced, "AC-1 must not count as AC-10");
    }

    @Test
    void aContractChangeRequiresTheArchitectureDocument() {
        List<String> errors = DocsAgent.docRules(GOOD, List.of("AC-1"), true, false);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains(DocsAgent.DESIGN_DOC), errors.toString());
        assertEquals(List.of(), DocsAgent.docRules(GOOD, List.of("AC-1"), true, true));
    }
}
