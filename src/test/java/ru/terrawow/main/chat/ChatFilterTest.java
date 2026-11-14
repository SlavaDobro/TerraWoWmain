package ru.terrawow.main.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The shipped chat-filter.yml: real-world insults are caught, insults about the game pass. */
class ChatFilterTest {
  static ChatFilter shipped() {
    var in = ChatFilterTest.class.getResourceAsStream("/chat-filter.yml");
    return new ChatFilter(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
  }

  @Test
  void realWorldIsCaught() {
    ChatFilter f = shipped();
    Map<String, List<String>> must = Map.of(
        "real_dox", List.of("вычислю тебя по ip", "скажи свой адрес", "+7 (912) 345-67-89", "где ты живёшь?"),
        "real_family", List.of("твоя мамка", "мать твою", "твои родители"),
        "real_slurs", List.of("иди уроки делай", "школота"),
        "advertising", List.of("заходите на best-server.ru", "play 192.168.1.10"),
        "real_talk", List.of("а в реале ты кто", "irl я устал"));
    must.forEach((rule, lines) -> {
      for (String s : lines) {
        ChatFilter.Rule r = f.check(s);
        assertNotNull(r, s);
        assertEquals(rule, r.id(), s);
      }
    });
    assertEquals(ChatFilter.Action.BLOCK, f.check("школота").action());
    assertTrue(f.check("школота").warn());
    assertFalse(f.check("твоя мамка").warn(), "family: blocked without a warning (RP lines can match)");
    assertEquals(ChatFilter.Action.HIDE, f.check("best-server.ru").action());
    assertEquals(ChatFilter.Action.NOTIFY, f.check("irl я устал").action());
  }

  @Test
  void insultsAboutTheGamePass() {
    ChatFilter f = shipped();
    for (String s : List.of("я найду тебя, орк, и сожгу твою деревню", "жалкий гоблин, твой клан падёт",
        "хачапури в таверне", "жидкость в зелье", "координаты 100 64 -200", "школа магии в Даларане",
        "дауншифтинг", "твой меч тупой как твоя башка", "тупой нежить, сдохни", "моя мама зовёт ужинать",
        "никогда в жизни", "т.е. идём в рейд", "За Орду!"))
      assertNull(f.check(s), s);
  }

  @Test
  void templates() {
    assertEquals("Ник: нация — x", ChatFilter.fill("{player}: {reason} — {message}", "Ник", "нация", "x"));
  }
}
