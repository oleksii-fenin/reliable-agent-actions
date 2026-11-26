package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * What Maven Central requires of every staged artifact (https://central.sonatype.org/publish/requirements/, read
 * 2026-11-23): the POM metadata, sources and javadoc jars, MD5 and SHA-1 checksums that match, and, when the staging
 * directory was signed ({@code -Psigned}), a valid signature of every file (docs/OPEN-QUESTIONS.md OQ5). Also the
 * module names and the BOM's contents.
 */
class PublishedArtifactsTest {

    private static final String GROUP = "io.github.oleksii-fenin";
    /** Every published library and its module name (its package); raa-bom manages exactly these. */
    private static final Map<String, String> LIBRARIES = Map.of(
            "raa-storage-api", "io.github.oleksiifenin.raa.storage",
            "raa-core", "io.github.oleksiifenin.raa.core",
            "raa-postgres", "io.github.oleksiifenin.raa.postgres",
            "raa-journal-tck", "io.github.oleksiifenin.raa.tck",
            "raa-testkit", "io.github.oleksiifenin.raa.testkit",
            "raa-langchain4j", "io.github.oleksiifenin.raa.langchain4j",
            "raa-opentelemetry", "io.github.oleksiifenin.raa.opentelemetry");
    private static final String BOM = "raa-bom";
    private static final List<String> REQUIRED_POM_ELEMENTS = List.of("groupId", "artifactId", "version", "name",
            "description", "url", "licenses/license/name", "licenses/license/url", "developers/developer/name",
            "scm/connection", "scm/developerConnection", "scm/url");

    private final Path group = Path.of(System.getProperty("raa.staging"), GROUP.split("\\."));
    private final String version = System.getProperty("raa.version");
    private final boolean signed = Boolean.getBoolean("raa.signed");
    /** The fingerprint of the key that must have signed, required with {@code -Psigned}. */
    private final String signingKey = System.getProperty("raa.signingKey", "");

    @Test
    void theStagingDirectory_holdsTheBomAndEveryLibrary_andNothingElse() throws IOException {
        Set<String> expected = new TreeSet<>(LIBRARIES.keySet());
        expected.add(BOM);
        try (Stream<Path> artifacts = Files.list(group)) {
            assertEquals(expected, artifacts.map(path -> path.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new)));
        }
    }

    @Test
    void theVersion_isARelease() {
        assertFalse(version.endsWith("-SNAPSHOT"), version);
    }

    @Test
    void everyLibrary_hasItsJars_aCompletePom_matchingChecksums_andItsModuleName() throws Exception {
        for (Map.Entry<String, String> library : LIBRARIES.entrySet()) {
            String artifact = library.getKey();
            for (String suffix : List.of(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")) {
                assertChecksummed(file(artifact, suffix));
            }
            assertCompletePom(artifact, pom(artifact));
            try (JarFile jar = new JarFile(file(artifact, ".jar").toFile())) {
                assertEquals(library.getValue(), jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"),
                        artifact);
            }
            try (JarFile javadoc = new JarFile(file(artifact, "-javadoc.jar").toFile())) {
                assertTrue(javadoc.getEntry("index.html") != null, artifact + " javadoc has no index.html");
            }
            try (JarFile sources = new JarFile(file(artifact, "-sources.jar").toFile())) {
                assertTrue(sources.stream().anyMatch(entry -> entry.getName().endsWith(".java")),
                        artifact + " sources have no .java file");
            }
        }
    }

    @Test
    void theBom_managesEveryLibraryInThisVersion_andHasACompletePom() throws Exception {
        assertChecksummed(file(BOM, ".pom"));
        assertChecksummed(file(BOM, ".module"));
        Document pom = pom(BOM);
        assertCompletePom(BOM, pom);
        assertEquals("pom", text(pom.getDocumentElement(), "packaging"));
        Set<String> managed = new TreeSet<>();
        NodeList dependencies = pom.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            assertEquals(GROUP, text(dependency, "groupId"));
            assertEquals(version, text(dependency, "version"), text(dependency, "artifactId"));
            managed.add(text(dependency, "artifactId"));
        }
        assertEquals(new TreeSet<>(LIBRARIES.keySet()), managed);
    }

    /** raa-postgres declares the driver with runtime scope; an application must get it without declaring it. */
    @Test
    void thePostgresDriver_isOnTheRuntimeClassPath() throws ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
    }

    private void assertCompletePom(String artifact, Document pom) {
        Element project = pom.getDocumentElement();
        List<String> missing = new ArrayList<>();
        for (String element : REQUIRED_POM_ELEMENTS) {
            if (text(project, element).isBlank()) {
                missing.add(element);
            }
        }
        assertEquals(List.of(), missing, artifact + " POM lacks required elements");
        assertEquals(GROUP, text(project, "groupId"));
        assertEquals(artifact, text(project, "artifactId"));
        assertEquals(version, text(project, "version"));
    }

    /**
     * Every jar, POM and module file of the staging directory carries a signature exactly when signing was requested,
     * and each signature is good (gpg's {@code GOODSIG}: not expired, not revoked) and made by the expected key.
     */
    @Test
    void whenSigned_everyFileHasAGoodSignatureOfTheExpectedKey() throws IOException, InterruptedException {
        if (signed) {
            assertTrue(signingKey.matches("[0-9A-F]{40}"),
                    "-Psigned needs -PsigningKey=<the signing key's 40-digit fingerprint>, got '" + signingKey + "'");
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> all = Files.walk(group)) {
            all.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(".*\\.(jar|pom|module)"))
                    .forEach(files::add);
        }
        assertEquals(LIBRARIES.size() * 5 + 2, files.size(), "jars, POMs and module files of the staging directory");
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            Path signature = Path.of(file + ".asc");
            if (signed != Files.isRegularFile(signature)) {
                problems.add(file.getFileName()
                        + (signed ? " has no signature" : " is signed, although -Psigned is not set"));
            } else if (signed) {
                String problem = signatureProblem(signature, file);
                if (problem != null) {
                    problems.add(file.getFileName() + ": " + problem);
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** What is wrong with the signature according to gpg's status lines, or {@code null} if nothing is. */
    private String signatureProblem(Path signature, Path file) throws IOException, InterruptedException {
        Path status = Files.createTempFile("gpg-status", ".txt");
        try {
            Process gpg = new ProcessBuilder("gpg", "--batch", "--status-fd", "1", "--verify", signature.toString(),
                    file.toString())
                    .redirectOutput(status.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!gpg.waitFor(30, TimeUnit.SECONDS)) {
                gpg.destroyForcibly();
                throw new IllegalStateException("gpg did not finish verifying " + signature);
            }
            List<String> lines = Files.readAllLines(status);
            if (gpg.exitValue() != 0 || lines.stream().noneMatch(line -> line.startsWith("[GNUPG:] GOODSIG "))) {
                return "no good signature (gpg exit " + gpg.exitValue() + ", " + lines + ")";
            }
            // VALIDSIG <signing key fingerprint> ... <primary key fingerprint> (the last field)
            boolean byTheExpectedKey = lines.stream()
                    .filter(line -> line.startsWith("[GNUPG:] VALIDSIG "))
                    .map(line -> line.split(" "))
                    .anyMatch(fields -> fields[2].equals(signingKey) || fields[fields.length - 1].equals(signingKey));
            return byTheExpectedKey ? null : "not signed by the key " + signingKey + " (" + lines + ")";
        } finally {
            Files.delete(status);
        }
    }

    private static void assertChecksummed(Path file) throws IOException, NoSuchAlgorithmException {
        assertTrue(Files.isRegularFile(file), "missing " + file);
        for (Map.Entry<String, String> checksum : Map.of("md5", "MD5", "sha1", "SHA-1").entrySet()) {
            Path checksumFile = Path.of(file + "." + checksum.getKey());
            assertTrue(Files.isRegularFile(checksumFile), "missing " + checksumFile);
            String expected;
            try (InputStream content = Files.newInputStream(file)) {
                expected = HexFormat.of().formatHex(
                        MessageDigest.getInstance(checksum.getValue()).digest(content.readAllBytes()));
            }
            assertEquals(expected, Files.readString(checksumFile).trim(), checksumFile.toString());
        }
    }

    private Path file(String artifact, String suffix) {
        return group.resolve(artifact).resolve(version).resolve(artifact + "-" + version + suffix);
    }

    private Document pom(String artifact) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(artifact, ".pom").toFile());
    }

    /** The text of the first element at {@code path} (child names separated by {@code /}), or "" if there is none. */
    private static String text(Element parent, String path) {
        Element current = parent;
        for (String name : path.split("/")) {
            current = child(current, name);
            if (current == null) {
                return "";
            }
        }
        return current.getTextContent().trim();
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }
}
