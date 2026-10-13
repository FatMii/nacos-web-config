package io.github.fatmii.nacoswebconfig.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the CVE-2026-47890 exemption recorded in .trivyignore: Spring corrupts an SSE stream only
 * when a payload is a rendered view fragment, so these types must stay unreferenced here.
 */
class WebConfigNoViewFragmentOutputTest {
    private static final List<String> FORBIDDEN = List.of(
            "org/springframework/web/servlet/ModelAndView",
            "org/springframework/web/servlet/view/FragmentsRendering");

    @Test
    void compiledModuleNeverReferencesViewFragmentTypes() throws Exception {
        var classes = Path.of(WebConfigStreamService.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "expected a classes directory, found " + classes);
        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files.filter(path -> path.toString().endsWith(".class")).flatMap(path -> {
                try {
                    var pool = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                    return FORBIDDEN.stream()
                            .filter(pool::contains)
                            .map(forbidden -> classes.relativize(path) + " -> " + forbidden);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }).toList();
        }
        assertEquals(List.of(), offenders,
                "streams JSON only; view fragments over SSE are the CVE-2026-47890 path");
    }
}
