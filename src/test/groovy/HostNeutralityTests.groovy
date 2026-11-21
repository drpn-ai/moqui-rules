import spock.lang.*
import java.nio.file.*
import java.util.regex.Pattern

/**
 * The component is generic: no file names an application or a system that uses it. Only the files that came from the
 * upstream repository (their demo data and specs) are left as they were.
 */
class HostNeutralityTests extends Specification {
    // Relative to the component directory.
    static final List<String> UPSTREAM_FILES = [
            "data/HoldTaskTriageDemoData.xml", "data/RuleDemoData.xml",
            "src/test/groovy/BuildFactTests.groovy", "src/test/groovy/EvaluateRuleSetTests.groovy",
            "src/test/groovy/FactTreeRefusalTests.groovy", "src/test/groovy/HoldTaskTriageTests.groovy"]
    // .superpowers holds untracked agent scratch notes, not component files
    static final List<String> SKIPPED_DIRS = ["build", "lib", ".git", ".superpowers"]
    static final String OWN_FILE = "src/test/groovy/HostNeutralityTests.groovy"
    static final Pattern NAMES = Pattern.compile("(?i)darpan|shopify|netsuite|hotwax")

    def "no file outside the upstream originals names a host application or system"() {
        given:
        Path root = componentDir()
        List<String> offences = []

        when:
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.each { Path file ->
                String rel = root.relativize(file).toString().replace(File.separator, "/")
                if (SKIPPED_DIRS.any { rel == it || rel.startsWith(it + "/") } || rel == OWN_FILE || UPSTREAM_FILES.contains(rel)) return
                List<String> lines
                try { lines = Files.readAllLines(file) } catch (java.io.IOException ignored) { return } // binary or not text
                lines.eachWithIndex { String line, int i -> if (NAMES.matcher(line).find()) offences << "${rel}:${i + 1}".toString() }
            }
        }

        then:
        // the failure message lists each offending path:line
        assert offences.isEmpty(), "Files naming a host application or system:\n" + offences.join("\n")
    }

    private static Path componentDir() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null && !Files.exists(dir.resolve("component.xml"))) dir = dir.getParent()
        assert dir != null, "component.xml not found above ${System.getProperty('user.dir')}"
        return dir
    }
}
