package dev.toka.regions;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Map;
import static org.junit.Assert.*;

public class ButtonLangTest {
    private static final String[] KEYS = {"win.back","win.prev","win.next","win.search","win.household","win.setHousehold","win.outside","win.showBounds","win.edit","win.delete","win.deleteYes","win.deleteNo","win.close"};

    private static Object get(Object node, String key) {
        for (String part : key.split(java.util.regex.Pattern.quote("."))) node = ((Map<?, ?>) node).get(part);
        return node;
    }

    @Test public void buttonTextsHaveNoColourExceptLeadingRed() throws Exception {
        for (String file : new String[]{"zh-TW.yml", "en-US.yml"}) {
            try (InputStream in = ButtonLangTest.class.getResourceAsStream("/lang/" + file)) {
                Object root = new Yaml().load(in);
                for (String key : KEYS) {
                    String value = String.valueOf(get(root, key));
                    String rest = value.startsWith("§c") ? value.substring(2) : value;
                    assertFalse(file + " " + key, rest.contains("§"));
                }
            }
        }
    }
}
