package dev.toka.regions;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigEditTest {
    @Test public void onlyTheNamedLineChangesAndCommentsStay() {
        List<String> lines = new ArrayList<>(List.of("other:", "  enforce: true", "protection:", "  # a comment", "  enforce: false # keep this", "tail: 1"));
        assertTrue(ConfigEdit.apply(lines, "protection", "enforce", true));
        assertEquals(List.of("other:", "  enforce: true", "protection:", "  # a comment", "  enforce: true # keep this", "tail: 1"), lines);
    }

    @Test public void aKeyInAnotherSectionIsNotTouched() {
        List<String> lines = new ArrayList<>(List.of("protection:", "  other: 1", "next:", "  enforce: false"));
        assertFalse(ConfigEdit.apply(lines, "protection", "enforce", true));
        assertEquals("  enforce: false", lines.get(3));
    }
}
