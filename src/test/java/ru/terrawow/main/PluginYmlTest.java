package ru.terrawow.main;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Guards the naming: one root command /twm, permissions twm.*, no leftovers of the old plugin name. */
class PluginYmlTest {
  private static String resource(String name) throws Exception {
    try (var in = PluginYmlTest.class.getResourceAsStream("/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void commandsAndPermissions() throws Exception {
    YamlConfiguration y;
    try (var in = PluginYmlTest.class.getResourceAsStream("/plugin.yml")) {
      y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    assertEquals("TerraWoWmain", y.getString("name"));
    var commands = y.getConfigurationSection("commands").getKeys(false);
    assertEquals(java.util.Set.of("twm", "menu", "mounts", "pets", "race", "racial", "lang"), commands);
    // Bukkit YAML treats dots as nesting: every permission must live under the single root "twm"
    assertEquals(java.util.Set.of("twm"), y.getConfigurationSection("permissions").getKeys(false));
    assertTrue(y.isSet("permissions.twm.admin.default"), "twm.admin");
    assertTrue(y.isSet("permissions.twm.economy.pay.default"), "twm.economy.pay");
  }

  @Test
  void noOldNameInBundledFiles() throws Exception {
    for (String f : new String[] {"plugin.yml", "config.yml", "messages.yml", "ui.yml", "collections.yml", "races.yml"}) {
      String text = resource(f).toLowerCase(Locale.ROOT);
      assertFalse(text.contains("aeconomy"), f + " mentions aeconomy");
      assertFalse(text.contains("azeroth"), f + " mentions azeroth");
      for (String old : new String[] {"terrawow.mounts", "terrawow.pets", "terrawow.race", "terrawow.admin"})
        assertFalse(text.contains(old), f + " uses old permission " + old);
    }
  }
}
