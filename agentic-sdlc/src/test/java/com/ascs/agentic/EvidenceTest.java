package com.ascs.agentic;

import com.ascs.agentic.agents.SecurityScanAgent;
import com.ascs.agentic.workspace.MavenBuildRunner;
import com.ascs.agentic.workspace.TestReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Evidence the system produces itself: the deterministic security scan and the build runner's report parsing. */
class EvidenceTest {

    static final String DIFF = """
            diff --git a/shortener-service/src/main/java/demo/A.java b/shortener-service/src/main/java/demo/A.java
            --- a/shortener-service/src/main/java/demo/A.java
            +++ b/shortener-service/src/main/java/demo/A.java
            @@ -1,2 +1,4 @@
             package demo;
            +class A { String k = "AKIA""" + "ABCDEFGHIJKLMNOP" + """
            "; }
            +class B { void run() throws Exception { Runtime.getRuntime().exec("x"); } }
             // end
            diff --git a/shortener-service/src/test/java/demo/ATest.java b/shortener-service/src/test/java/demo/ATest.java
            --- a/shortener-service/src/test/java/demo/ATest.java
            +++ b/shortener-service/src/test/java/demo/ATest.java
            @@ -10,1 +10,2 @@
             class ATest {
            +  @Disabled void t() {}
            diff --git a/shortener-service/src/test/java/demo/OldTest.java b/shortener-service/src/test/java/demo/OldTest.java
            deleted file mode 100644
            --- a/shortener-service/src/test/java/demo/OldTest.java
            +++ /dev/null
            @@ -1 +0,0 @@
            -class OldTest {}
            """;

    @Test
    void securityScanFindsSecretsExecDisabledAndDeletedTestsWithFileAndLine() {
        List<SecurityScanAgent.Finding> f = SecurityScanAgent.scan(DIFF);
        assertTrue(f.contains(new SecurityScanAgent.Finding("CRITICAL", "secret.aws-key", "shortener-service/src/main/java/demo/A.java", 2, "possible AWS access key")), f::toString);
        assertTrue(f.stream().anyMatch(x -> x.rule().equals("exec.process") && x.line() == 3));
        assertTrue(f.stream().anyMatch(x -> x.rule().equals("tests.disabled") && x.line() == 11));
        assertTrue(f.stream().anyMatch(x -> x.rule().equals("tests.deleted") && x.file().endsWith("OldTest.java")));
        assertTrue(f.stream().noneMatch(x -> x.message().contains("ABCDEFGHIJKLMNOP")), "findings never echo the secret");
        assertNull(SecurityScanAgent.gateFailure("{\"blocking\":0}"));
        assertNotNull(SecurityScanAgent.gateFailure("{\"blocking\":2}"));
    }

    @Test
    void surefireReportsAreParsedAndExternalEntitiesRefused(@TempDir Path dir) throws Exception {
        TestRepo.write(dir, "mod/target/surefire-reports/TEST-a.ATest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="a.ATest" tests="3" failures="1" errors="0" skipped="1">
                  <testcase classname="a.ATest" name="ok"/>
                  <testcase classname="a.ATest" name="bad"><failure message="expected 1 but was 2">trace</failure></testcase>
                  <testcase classname="a.ATest" name="skip"><skipped/></testcase>
                </testsuite>""");
        TestRepo.write(dir, "mod/target/site/jacoco/jacoco.csv", "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\n"
                + "g,p,C,1,9,0,2,1,3\n");
        // a fake "mvn" that succeeds without doing anything: the parsing under test reads the files above
        MavenBuildRunner runner = new MavenBuildRunner("git", Set.of("mod"), Duration.ofSeconds(30));
        TestReport r = runner.runTests(dir, "mod", "ATest#ok");
        assertEquals(3, r.tests());
        assertEquals(1, r.failures());
        assertEquals(1, r.skipped());
        assertEquals(List.of("a.ATest#bad [failure] expected 1 but was 2"), r.failedTests());
        assertEquals(0.75, r.lineCoverage(), 1e-9);
        assertFalse(r.passed());
        assertThrows(IllegalArgumentException.class, () -> runner.runTests(dir, "other", null), "module allowlist");
        assertThrows(IllegalArgumentException.class, () -> runner.runTests(dir, "mod", "x; rm -rf /"), "filter allowlist");

        TestRepo.write(dir, "mod/target/surefire-reports/TEST-evil.xml", """
                <?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><testsuite tests="&e;"/>""");
        assertThrows(java.io.IOException.class, () -> runner.runTests(dir, "mod", null), "DOCTYPE/XXE refused");
    }
}
