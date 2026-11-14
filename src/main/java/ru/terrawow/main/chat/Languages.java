package ru.terrawow.main.chat;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** WoW languages: who knows what (by race + learned) and how a language sounds to those who don't. */
public final class Languages {
  public record Lang(String id, String name, List<String> syllables) {}

  public final Map<String, Lang> langs = new LinkedHashMap<>();
  public final Map<String, List<String>> races = new LinkedHashMap<>();

  public Languages(YamlConfiguration y) {
    ConfigurationSection ls = y.getConfigurationSection("languages");
    if (ls == null) throw new IllegalArgumentException("languages.yml: нет раздела languages");
    for (String id : ls.getKeys(false)) {
      List<String> syl = ls.getStringList(id + ".syllables");
      if (syl.stream().allMatch(x -> x.equals("'") || x.isEmpty())) throw new IllegalArgumentException("languages." + id + ": пустой список syllables");
      langs.put(id, new Lang(id, ls.getString(id + ".name", id), List.copyOf(syl)));
    }
    ConfigurationSection rs = y.getConfigurationSection("races");
    if (rs != null)
      for (String race : rs.getKeys(false)) {
        List<String> list = rs.getStringList(race);
        for (String l : list)
          if (!langs.containsKey(l)) throw new IllegalArgumentException("races." + race + ": нет языка " + l);
        if (list.isEmpty()) throw new IllegalArgumentException("races." + race + ": нет языков");
        races.put(race, List.copyOf(list));
      }
  }

  /** Languages a player knows: race languages first (the first is the default), then learned ones. */
  public List<String> known(String race, Collection<String> learned) {
    LinkedHashSet<String> out = new LinkedHashSet<>(race == null ? List.of() : races.getOrDefault(race, List.of()));
    for (String l : learned) if (langs.containsKey(l)) out.add(l);
    return List.copyOf(out);
  }

  /** Language id by id or by Russian name (any case, prefix is enough). */
  public Lang find(String q) {
    String s = q.toLowerCase(Locale.ROOT);
    if (langs.containsKey(s)) return langs.get(s);
    for (Lang l : langs.values()) if (l.name().toLowerCase(Locale.ROOT).startsWith(s)) return l;
    return null;
  }

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

  /**
   * Text as heard by someone who does not know the language: every word becomes a made-up word of
   * about the same length; the same word always becomes the same made-up word (like in WoW).
   * Spaces, punctuation and capital letters stay.
   */
  public static String scramble(String text, Lang lang) {
    Matcher m = WORD.matcher(text);
    StringBuilder out = new StringBuilder(text.length() + 8);
    int last = 0;
    while (m.find()) {
      out.append(text, last, m.start());
      out.append(word(m.group(), lang));
      last = m.end();
    }
    return out.append(text.substring(last)).toString();
  }

  static String word(String w, Lang lang) {
    String lower = w.toLowerCase(Locale.ROOT);
    Random r = new Random((lang.id() + ':' + lower).hashCode() * 31L + lower.length());
    StringBuilder b = new StringBuilder();
    List<String> syl = lang.syllables();
    while (b.length() < w.length()) {
      String s = syl.get(r.nextInt(syl.size()));
      if (s.equals("'") && (b.isEmpty() || b.length() + 2 >= w.length())) continue; // apostrophe only inside
      b.append(s);
    }
    if (b.length() > w.length() + 1) b.setLength(Math.max(1, w.length()));
    String res = b.toString();
    if (w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT)) && !w.equals(lower))
      return res.toUpperCase(Locale.ROOT);
    if (Character.isUpperCase(w.charAt(0)))
      return Character.toUpperCase(res.charAt(0)) + res.substring(1);
    return res;
  }
}
