package net.cumba.corej.editor.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.editor.error.EditorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every refusal of §5.8.2, by name. */
class PathPolicyTest
{

    private static String refused(String aPath)
    {
        EditorException e = assertThrows(EditorException.class, () -> PathPolicy.check(aPath),
                aPath);
        assertEquals("BAD_PATH", e.code());
        assertEquals(400, e.status().value());
        return e.getMessage();
    }


    @Test
    void allowedTreesAndTheirRootsPass()
    {
        assertEquals("rules-src/checks/CDISC/CDISC-AD0001.yaml",
                PathPolicy.check("rules-src/checks/CDISC/CDISC-AD0001.yaml"));
        assertEquals("rules-src/docs/FDA/x.yaml", PathPolicy.check("rules-src/docs/FDA/x.yaml"));
        assertEquals("rules-src/checks", PathPolicy.check("rules-src/checks/"));
        assertEquals("src/test/resources/net/cumba/corej/core/ruletestsuites/cdisc/A/A-x.cdt",
                PathPolicy.check(
                        "src/test/resources/net/cumba/corej/core/ruletestsuites/cdisc/A/A-x.cdt"));
    }


    @Test
    void traversalDotGitHiddenAbsoluteAndOutsideAreRefused()
    {
        assertTrue(refused("rules-src/checks/../../pom.xml").contains("'..'"));
        assertTrue(refused("rules-src/checks/./x.yaml").contains("'.'"));
        assertTrue(refused("rules-src/checks/.git/hooks/pre-commit").contains(".git"));
        assertTrue(refused(".git/config").contains(".git"));
        assertTrue(refused("rules-src/checks/.GIT/config").contains(".git"));
        assertTrue(refused("rules-src/checks/CDISC/.gitattributes").contains("hidden"));
        assertTrue(refused("/rules-src/checks/x.yaml").contains("absolute"));
        assertTrue(refused("rules-src\\checks\\x.yaml").contains("backslash"));
        assertTrue(refused("rules-src/checks//x.yaml").contains("empty segment"));
        assertTrue(refused("pom.xml").contains("outside"));
        assertTrue(refused("src/main/java/X.java").contains("outside"));
        assertTrue(refused("rules-src/checksum/x.yaml").contains("outside"),
                "a prefix of a root is not the root");
        assertTrue(refused("rules-src").contains("outside"));
        assertTrue(refused("").contains("empty"));
        assertTrue(refused("rules-src/checks/x\0.yaml").contains("NUL"));
    }


    @Test
    void resolveRefusesSymlinksAnywhereOnThePath(@TempDir Path aRoot) throws IOException
    {
        Path checks = Files.createDirectories(aRoot.resolve("rules-src/checks/CDISC"));
        Files.writeString(checks.resolve("ok.yaml"), "Core: x\n");
        Path outside = Files.writeString(aRoot.resolveSibling(aRoot.getFileName() + "-outside"),
                "secret");
        Files.createSymbolicLink(checks.resolve("evil.yaml"), outside);
        Files.createSymbolicLink(aRoot.resolve("rules-src/docs"), outside.getParent());

        assertEquals(checks.resolve("ok.yaml").toRealPath(),
                PathPolicy.resolve(aRoot, "rules-src/checks/CDISC/ok.yaml"));
        assertEquals(checks.toRealPath().resolve("new.yaml"),
                PathPolicy.resolve(aRoot, "rules-src/checks/CDISC/new.yaml"),
                "a file that does not exist yet resolves (for a write)");
        EditorException file = assertThrows(EditorException.class,
                () -> PathPolicy.resolve(aRoot, "rules-src/checks/CDISC/evil.yaml"));
        assertTrue(file.getMessage().contains("symbolic link"), file.getMessage());
        EditorException dir = assertThrows(EditorException.class,
                () -> PathPolicy.resolve(aRoot, "rules-src/docs/anything.yaml"));
        assertTrue(dir.getMessage().contains("symbolic link"), dir.getMessage());
        Files.delete(outside);
    }
}
