package ru.terrawow.main.chat;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Chat rules of chat-filter.yml: "insult the character, not the person". Pure logic, see ChatModule. */
public final class ChatFilter {
  public enum Action { BLOCK, HIDE, NOTIFY }

  public record Rule(String id, Action action, boolean warn, String reason, List<Pattern> patterns) {
    boolean matches(String text) {
      for (Pattern p : patterns) if (p.matcher(text).find()) return true;
      return false;
    }
  }

  public final List<Rule> rules = new ArrayList<>();
  public final String warnCommand, blockedMessage, staffMessage, spamMessage;
  public final int spamSeconds;

  public ChatFilter(YamlConfiguration y) {
    warnCommand = y.getString("warn-command", "");
    blockedMessage = y.getString("blocked-message", "&cСообщение не отправлено: {reason}");
    staffMessage = y.getString("staff-message", "&4[Чат] &6{player} &c{reason}: &f{message}");
    spamSeconds = y.getInt("anti-spam.seconds", 3);
    spamMessage = y.getString("anti-spam.message", "&cНе повторяйте одно и то же сообщение.");
    ConfigurationSection rs = y.getConfigurationSection("rules");
    if (rs == null) return;
    for (String id : rs.getKeys(false)) {
      ConfigurationSection r = rs.getConfigurationSection(id);
      if (r == null) continue;
      Action a;
      try {
        a = Action.valueOf(r.getString("action", "block").toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("chat-filter.yml rules." + id + ".action: block, hide или notify");
      }
      List<Pattern> ps = new ArrayList<>();
      for (String rx : r.getStringList("regex"))
        try {
          ps.add(Pattern.compile(rx));
        } catch (PatternSyntaxException e) {
          throw new IllegalArgumentException("chat-filter.yml rules." + id + ": неверный шаблон " + rx + " — " + e.getDescription());
        }
      if (ps.isEmpty()) throw new IllegalArgumentException("chat-filter.yml rules." + id + ": пустой regex");
      rules.add(new Rule(id, a, r.getBoolean("warn", false), r.getString("reason", id), List.copyOf(ps)));
    }
  }

  /** First rule the text breaks, or null. */
  public Rule check(String text) {
    for (Rule r : rules) if (r.matches(text)) return r;
    return null;
  }

  public static String fill(String template, String player, String reason, String message) {
    return template.replace("{player}", player).replace("{reason}", reason).replace("{message}", message);
  }
}
