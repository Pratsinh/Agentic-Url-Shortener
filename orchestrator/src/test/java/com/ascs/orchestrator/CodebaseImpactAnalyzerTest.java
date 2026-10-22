package com.ascs.orchestrator;

import com.ascs.orchestrator.planner.CodebaseImpactAnalyzer;
import com.ascs.orchestrator.scenario.Scenarios;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CodebaseImpactAnalyzerTest {

    @Test
    void walksReferencesBackwardsAndIgnoresCommentsAndStrings() throws IOException {
        Path root = Files.createTempDirectory("impact");
        Files.writeString(root.resolve("Core.java"), "class Core {}");
        Files.writeString(root.resolve("Uses.java"), "class Uses { Core c; }");
        Files.writeString(root.resolve("UsesUses.java"), "class UsesUses { Uses u; }");
        Files.writeString(root.resolve("OnlyMentions.java"),
                "// Core is mentioned here\nclass OnlyMentions { String s = \"Core\"; /* Core */ }");
        Files.writeString(root.resolve("Unrelated.java"), "class Unrelated {}");

        CodebaseImpactAnalyzer.Report r = new CodebaseImpactAnalyzer(root).analyze("Core");

        assertEquals(1, r.direct().size());
        assertTrue(r.direct().get(0).startsWith("Uses "));
        assertEquals(1, r.transitive().size());
        assertTrue(r.transitive().get(0).startsWith("UsesUses "));
        assertTrue(r.toMarkdown().contains("Directly impacted"));
    }

    @Test
    void missingSourceTreeIsReportedNotCrashed() {
        CodebaseImpactAnalyzer a = new CodebaseImpactAnalyzer(Path.of("/definitely/not/here"));
        assertFalse(a.available());
        assertTrue(a.analyze("Anything").direct().isEmpty());
    }

    @Test
    void findsTheRealImpactOfReplacingTheLinkRepository() {
        Path service = RepoRoot.PATH.resolve(Scenarios.TARGET_SYSTEM);
        CodebaseImpactAnalyzer.Report r = new CodebaseImpactAnalyzer(service.resolve("src/main/java"), service.resolve("src/test/java"))
                .analyze("LinkRepository", "InMemoryLinkRepository");

        String direct = String.join("\n", r.direct());
        assertTrue(direct.contains("ShortenerService"), direct);
        assertTrue(direct.contains("AppConfig"), direct);
        assertTrue(direct.contains("ShortenerServiceTest (test)"), direct);
        assertTrue(String.join("\n", r.transitive()).contains("ShortenerController"));
    }
}
