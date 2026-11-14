package ru.terrawow.main.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LanguagesTest {
  static Languages shipped() {
    var in = LanguagesTest.class.getResourceAsStream("/languages.yml");
    return new Languages(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
  }

  @Test
  void everyRaceKnowsItsFactionLanguageFirst() {
    Languages l = shipped();
    for (String r : List.of("human", "dwarf", "nightelf", "gnome", "draenei", "worgen"))
      assertEquals("common", l.races.get(r).get(0), r);
    for (String r : List.of("orc", "undead", "tauren", "troll", "bloodelf", "goblin"))
      assertEquals("orcish", l.races.get(r).get(0), r);
    assertEquals(List.of("common", "darnassian", "orcish"), l.known("nightelf", Set.of("orcish", "nope")),
        "learned languages come after the racial ones; unknown ids are ignored");
    assertEquals(List.of(), l.known(null, Set.of()));
  }

  @Test
  void enemiesSeeMadeUpWordsOfTheSameShape() {
    Languages.Lang orc = shipped().langs.get("orcish");
    String said = "Привет, Тралл! Где ВСЕ?";
    String heard = Languages.scramble(said, orc);
    assertNotEquals(said, heard);
    assertEquals(heard, Languages.scramble(said, orc), "the same words always sound the same");
    assertEquals(", ", heard.substring(heard.indexOf(','), heard.indexOf(',') + 2), "punctuation stays");
    assertTrue(Character.isUpperCase(heard.charAt(0)), "capital letter stays");
    String[] words = heard.split("[^\\p{L}']+");
    assertEquals(4, words.length);
    assertEquals(words[3], words[3].toUpperCase(), "shouted word stays in capitals");
    for (String w : words) assertFalse(w.startsWith("'") || w.isEmpty(), w);
  }

  @Test
  void languagesAreFoundByIdOrRussianName() {
    Languages l = shipped();
    assertEquals("gutterspeak", l.find("наречие").id());
    assertEquals("common", l.find("COMMON").id());
    assertNull(l.find("эльфийский"));
  }
}
