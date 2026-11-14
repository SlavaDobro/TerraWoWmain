package ru.terrawow.main.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ru.terrawow.main.collection.Text;
import ru.terrawow.main.race.RaceModule;

/**
 * WoW chat: "Ник говорит:" nearby, "Ник кричит:" far (message starts with "!"), names in faction
 * colours, and languages — each listener gets the text in the speaker's language only if they know it,
 * otherwise made-up words (see {@link Languages}). Chat bubbles above the head follow the same rule.
 * Whispers (/msg …) to a player with no common language are blocked, like whispering the enemy faction.
 *
 * <p>CMI keeps its chat filter, mutes and chat rooms; its chat format, range and bubbles must be off
 * (tools/cmi_rp_setup.py does it), otherwise CMI would show the real text to everyone.
 */
public final class ChatModule implements Listener, CommandExecutor, TabCompleter {
  private final JavaPlugin plugin;
  private final RaceModule races; // may be null: then everybody understands everything
  private final NamespacedKey speakKey, learnedKey;
  private Languages langs;
  private int sayRange, yellRange, bubbleSeconds;
  private String yellPrefix;
  private boolean bubbles, heads;
  private Set<String> whisperCommands = Set.of();
  private final Map<String, TextColor> factionColors = new HashMap<>();
  // filled on the main thread, read by the async chat thread
  private final Map<UUID, String> speak = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> learned = new ConcurrentHashMap<>();
  private final Map<UUID, List<TextDisplay>> shown = new HashMap<>(); // main thread only

  private static final TextColor GRAY = TextColor.color(0xAAAAAA), SAY = NamedTextColor.WHITE,
      YELL = TextColor.color(0xFF4040), TAG = TextColor.color(0xFFE88A);

  public ChatModule(JavaPlugin plugin, RaceModule races) {
    this.plugin = plugin;
    this.races = races;
    this.speakKey = new NamespacedKey(plugin, "lang_speak");
    this.learnedKey = new NamespacedKey(plugin, "lang_learned");
  }

  public void enable() {
    reload();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    PluginCommand c = Objects.requireNonNull(plugin.getCommand("lang"), "lang");
    c.setExecutor(this);
    c.setTabCompleter(this);
    for (Player p : Bukkit.getOnlinePlayers()) load(p);
    plugin.getLogger().info("Чат: языков " + langs.langs.size() + ", говорить " + sayRange + ", кричать " + yellRange + ".");
  }

  public void reload() {
    File f = new File(plugin.getDataFolder(), "languages.yml");
    if (!f.exists()) plugin.saveResource("languages.yml", false);
    langs = new Languages(YamlConfiguration.loadConfiguration(f));
    ConfigurationSection c = plugin.getConfig().getConfigurationSection("chat");
    if (c == null) c = new YamlConfiguration();
    sayRange = c.getInt("say-range", 50);
    yellRange = c.getInt("yell-range", 300);
    yellPrefix = c.getString("yell-prefix", "!");
    bubbles = c.getBoolean("bubbles", true);
    bubbleSeconds = Math.max(2, c.getInt("bubble-seconds", 6));
    heads = c.getBoolean("heads", true);
    whisperCommands = new HashSet<>();
    for (String s : c.getStringList("whisper-commands")) whisperCommands.add(s.toLowerCase(Locale.ROOT));
    factionColors.clear();
    ConfigurationSection fc = c.getConfigurationSection("faction-colors");
    if (fc != null)
      for (String k : fc.getKeys(false)) {
        TextColor t = TextColor.fromHexString(fc.getString(k, ""));
        if (t != null) factionColors.put(k, t);
      }
  }

  public void disable() {
    for (List<TextDisplay> l : shown.values()) l.forEach(Entity::remove);
    shown.clear();
  }

  // ---------------------------------------------------------------- who knows what
  private String race(UUID id) {
    return races == null ? null : races.raceId(id);
  }

  private String faction(UUID id) {
    String r = race(id);
    return r == null ? null : races.factionOf(r);
  }

  /** No race yet (character creation) or the twm.lang.all permission: understands every language. */
  private boolean all(Player p) {
    return race(p.getUniqueId()) == null || p.hasPermission("twm.lang.all");
  }

  public List<String> known(Player p) {
    return langs.known(race(p.getUniqueId()), learned.getOrDefault(p.getUniqueId(), Set.of()));
  }

  /** Language the player speaks now, or null (no race: speaks so that everybody understands). */
  public String speaking(Player p) {
    List<String> k = known(p);
    if (race(p.getUniqueId()) == null || k.isEmpty()) return null;
    String s = speak.get(p.getUniqueId());
    return s != null && k.contains(s) ? s : k.get(0);
  }

  private boolean understands(Player listener, String lang) {
    return lang == null || all(listener) || known(listener).contains(lang);
  }

  private String defaultLanguage(Player p) {
    List<String> k = known(p);
    return k.isEmpty() ? null : k.get(0);
  }

  private void load(Player p) {
    var pdc = p.getPersistentDataContainer();
    String s = pdc.get(speakKey, PersistentDataType.STRING);
    if (s != null) speak.put(p.getUniqueId(), s);
    String l = pdc.get(learnedKey, PersistentDataType.STRING);
    Set<String> set = ConcurrentHashMap.newKeySet();
    if (l != null) for (String x : l.split(",")) if (!x.isBlank()) set.add(x.trim());
    learned.put(p.getUniqueId(), set);
  }

  private void saveLearned(Player p) {
    p.getPersistentDataContainer().set(learnedKey, PersistentDataType.STRING,
        String.join(",", learned.getOrDefault(p.getUniqueId(), Set.of())));
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    load(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    speak.remove(id);
    learned.remove(id);
    clearBubbles(id);
  }

  // ---------------------------------------------------------------- chat
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void chat(AsyncChatEvent e) {
    Player sp = e.getPlayer();
    String text = PlainTextComponentSerializer.plainText().serialize(e.message());
    Component message = e.message();
    boolean yell = !yellPrefix.isEmpty() && text.startsWith(yellPrefix) && text.length() > yellPrefix.length();
    if (yell) {
      text = text.substring(yellPrefix.length()).strip();
      message = Component.text(text);
    }
    int range = yell ? yellRange : sayRange;
    if (range > 0) {
      double r2 = (double) range * range;
      Location at = sp.getLocation();
      e.viewers().removeIf(a -> a instanceof Player v && v != sp && !v.hasPermission("twm.chat.spy")
          && (!v.getWorld().equals(at.getWorld()) || v.getLocation().distanceSquared(at) > r2));
    }
    List<Player> hearers = new ArrayList<>();
    for (Audience a : e.viewers()) if (a instanceof Player v && v != sp) hearers.add(v);
    String lang = speaking(sp);
    Languages.Lang l = lang == null ? null : langs.langs.get(lang);
    String heard = l == null ? text : Languages.scramble(text, l);
    Component head = heads ? Component.object(sp).append(Component.space()) : Component.empty();
    Component name = Component.text(sp.getName(), factionColors.getOrDefault(String.valueOf(faction(sp.getUniqueId())), SAY));
    Component level = Component.text("[" + level(sp) + "] ", GRAY);
    Component verb = Component.text(yell ? " кричит: " : " говорит: ", GRAY);
    final Component original = message.colorIfAbsent(yell ? YELL : SAY);
    final Component scrambled = Component.text(heard, yell ? YELL : SAY);
    e.renderer((source, displayName, msg, viewer) -> {
      boolean ok = !(viewer instanceof Player v) || understands(v, lang);
      boolean tag = l != null && (!(viewer instanceof Player v) || !lang.equals(defaultLanguage(v)));
      Component line = Component.empty().append(head).append(level).append(name).append(verb);
      if (tag) line = line.append(Component.text("[" + l.name() + "] ", TAG));
      return line.append(ok ? (yell ? original : msg.colorIfAbsent(SAY)) : scrambled);
    });
    if (hearers.isEmpty())
      sp.sendMessage(Text.c("&8Рядом никого нет — вас не услышали (слышно в радиусе " + sayRange + " блоков)."
          + (yellPrefix.isEmpty() ? "" : " Крикнуть: &7" + yellPrefix + "текст")));
    if (bubbles) {
      final String said = text;
      Bukkit.getScheduler().runTask(plugin, () -> bubble(sp, said, heard, lang, yell, hearers));
    }
  }

  private static String level(Player p) {
    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      String s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, "%mmocore_level%");
      if (!s.contains("%")) return s;
    }
    return String.valueOf(p.getLevel());
  }

  // ---------------------------------------------------------------- bubbles (main thread)
  private void bubble(Player sp, String text, String heard, String lang, boolean yell, List<Player> hearers) {
    if (!sp.isOnline() || sp.isDead()) return;
    clearBubbles(sp.getUniqueId());
    List<TextDisplay> list = new ArrayList<>();
    TextDisplay real = spawn(sp, text, yell);
    list.add(real);
    sp.showEntity(plugin, real);
    TextDisplay fake = null;
    for (Player v : hearers) {
      if (!v.isOnline()) continue;
      if (understands(v, lang)) v.showEntity(plugin, real);
      else {
        if (fake == null) list.add(fake = spawn(sp, heard, yell));
        v.showEntity(plugin, fake);
      }
    }
    shown.put(sp.getUniqueId(), list);
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      if (shown.get(sp.getUniqueId()) == list) clearBubbles(sp.getUniqueId());
    }, bubbleSeconds * 20L + text.length() / 2L);
  }

  private TextDisplay spawn(Player sp, String text, boolean yell) {
    TextDisplay d = sp.getWorld().spawn(sp.getLocation(), TextDisplay.class, t -> {
      t.setPersistent(false);
      t.setVisibleByDefault(false);
      t.text(Component.text(text, yell ? YELL : SAY));
      t.setBillboard(Display.Billboard.CENTER);
      t.setLineWidth(160);
      t.setShadowed(true);
      t.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
      t.setTransformation(new Transformation(new Vector3f(0, 0.55f, 0), new AxisAngle4f(), new Vector3f(0.8f, 0.8f, 0.8f), new AxisAngle4f()));
    });
    sp.addPassenger(d);
    return d;
  }

  private void clearBubbles(UUID id) {
    List<TextDisplay> old = shown.remove(id);
    if (old != null) old.forEach(Entity::remove);
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    clearBubbles(e.getPlayer().getUniqueId());
  }

  // ---------------------------------------------------------------- whispers to the enemy
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void whisper(PlayerCommandPreprocessEvent e) {
    String[] a = e.getMessage().substring(1).split(" ", 3);
    if (a.length < 3) return;
    String cmd = a[0].toLowerCase(Locale.ROOT);
    if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
    if (!whisperCommands.contains(cmd)) return;
    Player from = e.getPlayer(), to = Bukkit.getPlayerExact(a[1]);
    if (to == null || to == from || all(from) || all(to)) return;
    for (String l : known(from)) if (known(to).contains(l)) return;
    e.setCancelled(true);
    from.sendMessage(Text.c("&c" + to.getName() + " не знает ни одного вашего языка — шептать врагу нельзя."));
  }

  // ---------------------------------------------------------------- /lang and /twm lang
  @Override
  public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
    if (!(s instanceof Player p)) {
      s.sendMessage("Только для игроков. Админ: /twm lang give|take|list <ник> [язык]");
      return true;
    }
    if (race(p.getUniqueId()) == null) {
      p.sendMessage(Text.c("&7Сначала создайте персонажа (&f/race&7): язык зависит от расы."));
      return true;
    }
    if (a.length == 0) {
      String now = speaking(p);
      List<String> names = known(p).stream()
          .map(id -> (id.equals(now) ? "&a" : "&f") + langs.langs.get(id).name() + (id.equals(now) ? " &7(говорите)" : ""))
          .toList();
      p.sendMessage(Text.c("<#FFD100>Ваши языки: " + String.join("&7, ", names)));
      p.sendMessage(Text.c("&7Сменить: &f/lang <язык>&7. Кто язык не знает, увидит бессмыслицу."));
      return true;
    }
    Languages.Lang l = langs.find(String.join(" ", a));
    if (l == null || !known(p).contains(l.id())) {
      p.sendMessage(Text.c("&cВы не знаете такого языка. &7Ваши: /lang"));
      return true;
    }
    speak.put(p.getUniqueId(), l.id());
    p.getPersistentDataContainer().set(speakKey, PersistentDataType.STRING, l.id());
    p.sendMessage(Text.c("<#FFD100>Теперь вы говорите: &f" + l.name()));
    return true;
  }

  /** /twm lang give|take|list &lt;ник&gt; [язык] — teach / forget a language (twm.admin). */
  public void admin(CommandSender s, String[] a) {
    if (!s.hasPermission("twm.admin")) {
      s.sendMessage(Text.c("&cНет права twm.admin"));
      return;
    }
    if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) {
      reload();
      s.sendMessage(Text.c("&aЯзыки и настройки чата перечитаны."));
      return;
    }
    if (a.length < 2) {
      s.sendMessage(Text.c("&6/twm lang give <ник> <язык> &7— выучить язык"));
      s.sendMessage(Text.c("&6/twm lang take <ник> <язык> &7— забыть выученный язык"));
      s.sendMessage(Text.c("&6/twm lang list <ник> &7— языки игрока  &6/twm lang reload"));
      s.sendMessage(Text.c("&7Языки: " + String.join(", ", langs.langs.keySet())));
      return;
    }
    Player t = Bukkit.getPlayerExact(a[1]);
    if (t == null) {
      s.sendMessage(Text.c("&cИгрок не в сети: " + a[1]));
      return;
    }
    if (a[0].equalsIgnoreCase("list")) {
      s.sendMessage(Text.c("&f" + t.getName() + "&7: " + String.join(", ", known(t).stream().map(id -> langs.langs.get(id).name()).toList())
          + "&7; говорит: &f" + Optional.ofNullable(speaking(t)).map(id -> langs.langs.get(id).name()).orElse("— (нет расы)")));
      return;
    }
    Languages.Lang l = a.length >= 3 ? langs.find(a[2]) : null;
    if (l == null) {
      s.sendMessage(Text.c("&cНет такого языка. Есть: " + String.join(", ", langs.langs.keySet())));
      return;
    }
    Set<String> set = learned.computeIfAbsent(t.getUniqueId(), u -> ConcurrentHashMap.newKeySet());
    if (a[0].equalsIgnoreCase("give")) {
      set.add(l.id());
      saveLearned(t);
      t.sendMessage(Text.c("<#FFD100>Вы выучили язык: &f" + l.name()));
      s.sendMessage(Text.c("&a" + t.getName() + " знает язык " + l.name()));
    } else if (a[0].equalsIgnoreCase("take")) {
      set.remove(l.id());
      saveLearned(t);
      s.sendMessage(Text.c("&a" + t.getName() + " забыл выученный язык " + l.name() + " &7(расовые языки не забываются)"));
    } else s.sendMessage(Text.c("&cgive, take, list или reload"));
  }

  public List<String> adminTab(String[] a) {
    if (a.length <= 1) return List.of("give", "take", "list", "reload");
    if (a.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    if (a.length == 3) return List.copyOf(langs.langs.keySet());
    return List.of();
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
    if (!(s instanceof Player p) || a.length != 1) return List.of();
    return known(p).stream().map(id -> langs.langs.get(id).name()).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
  }
}
