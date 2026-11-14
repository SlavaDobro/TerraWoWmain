package ru.terrawow.main.race;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.io.File;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;
import ru.terrawow.main.collection.Text;
import ru.terrawow.main.race.RaceConfig.*;

/**
 * WoW races: Alliance / Horde, 12 races, gender, height (minecraft:scale), passive bonuses
 * (MythicLib stats + Minecraft attributes), a racial ability (/racial), class rules for MMOCore and
 * a random race skin. Choosing is once (first join); only an admin changes it (/twm race set|reset).
 */
public final class RaceModule implements Listener, CommandExecutor, TabCompleter {
  final JavaPlugin plugin;
  RaceConfig config;
  final RaceMenus menus;
  final Racials racials;
  private final File playersFolder;
  private final Map<UUID, PlayerRace> data = new java.util.concurrent.ConcurrentHashMap<>(); // read by the async chat
  private final NamespacedKey scaleKey;
  private boolean classHook;

  public RaceModule(JavaPlugin plugin) {
    this.plugin = plugin;
    this.playersFolder = new File(plugin.getDataFolder(), "races/players");
    this.menus = new RaceMenus(this);
    this.racials = new Racials(this);
    this.scaleKey = new NamespacedKey(plugin, "race_scale");
  }

  public void enable() {
    reload();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getPluginManager().registerEvents(menus, plugin);
    Bukkit.getPluginManager().registerEvents(racials, plugin);
    for (String cmd : List.of("race", "racial")) {
      PluginCommand c = Objects.requireNonNull(plugin.getCommand(cmd), cmd);
      c.setExecutor(this);
      c.setTabCompleter(this);
    }
    // MMOCore enables after us: hook its class event on the first tick
    Bukkit.getScheduler().runTask(plugin, () -> {
      classHook = Mmo.onClassChange(plugin, this::classChange);
      if (!classHook) plugin.getLogger().warning("Расы: MMOCore не найден — ограничения классов не работают.");
      for (Player p : Bukkit.getOnlinePlayers()) apply(p, false);
    });
    plugin.getLogger().info("Расы: " + config.races.size() + " (" + String.join(", ", config.races.keySet()) + ").");
  }

  public void disable() {
    racials.stopAll();
  }

  void reload() {
    File f = new File(plugin.getDataFolder(), "races.yml");
    if (!f.exists()) plugin.saveResource("races.yml", false);
    File skins = new File(plugin.getDataFolder(), "race-skins.yml"); // tools/sign_race_skins.py
    config = new RaceConfig(YamlConfiguration.loadConfiguration(f), skins.exists() ? YamlConfiguration.loadConfiguration(skins) : null);
  }

  // ---------------------------------------------------------------- player data
  /** Race id of the player, or null if no character was created yet. */
  public String raceId(UUID id) {
    PlayerRace r = of(id);
    return r.chosen() ? r.race : null;
  }

  /** Faction id (alliance / horde) of a race id, or null. */
  public String factionOf(String raceId) {
    Race r = raceId == null ? null : config.races.get(raceId);
    return r == null ? null : r.faction();
  }

  PlayerRace of(UUID id) {
    return data.computeIfAbsent(id, u -> PlayerRace.load(playersFolder, u));
  }

  void save(PlayerRace r) {
    String text = r.snapshot();
    UUID id = r.owner;
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      try {
        PlayerRace.write(playersFolder, id, text);
      } catch (Exception e) {
        plugin.getLogger().log(Level.SEVERE, "Расы: не удалось сохранить " + id, e);
      }
    });
  }

  Optional<Race> raceOf(Player p) {
    PlayerRace r = of(p.getUniqueId());
    return r.chosen() ? Optional.ofNullable(config.races.get(r.race)) : Optional.empty();
  }

  boolean sameFaction(Player a, Player b) {
    var ra = raceOf(a);
    var rb = raceOf(b);
    return ra.isPresent() && rb.isPresent() && ra.get().faction().equals(rb.get().faction());
  }

  // ---------------------------------------------------------------- choose / change
  /** The player's own choice from the menu: once. */
  void choose(Player p, Race race, Gender g) {
    PlayerRace r = of(p.getUniqueId());
    if (r.chosen() && !p.hasPermission("twm.admin")) {
      msg(p, "&cРаса уже выбрана. Сменить её может только администратор.");
      return;
    }
    set(p, race, g);
    Faction f = config.factions.get(race.faction());
    msg(p, "<#FFD100>✦ Добро пожаловать, " + race.name() + "&r <#FFD100>(" + g.ru.toLowerCase() + ")! Фракция: " + f.name());
    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    String cls = Mmo.classId(p);
    String cmd = config.afterChoiceCommand;
    if (cmd != null && !cmd.isBlank() && (cls == null || cls.equals(config.defaultClass)))
      Bukkit.getScheduler().runTaskLater(plugin,
          () -> { if (p.isOnline()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName())); }, 10);
  }

  /** Sets the race (admin or first choice): removes everything from the old one first. */
  void set(Player p, Race race, Gender g) {
    PlayerRace r = of(p.getUniqueId());
    boolean changed = !race.id().equals(r.race) || g != r.gender;
    r.race = race.id();
    r.gender = g;
    if (changed) r.skin = -1; // new race or gender: pick a new skin
    save(r);
    apply(p, true);
  }

  void reset(Player p) {
    PlayerRace r = of(p.getUniqueId());
    r.race = null;
    r.skin = -1;
    clear(p);
    restoreOwnSkin(p, r);
    save(r);
  }

  // ---------------------------------------------------------------- apply
  /** Puts the race on the player: height, passives, skin. Safe to call again (join, respawn). */
  void apply(Player p, boolean skinNow) {
    clear(p);
    Optional<Race> opt = raceOf(p);
    if (opt.isEmpty()) return;
    Race race = opt.get();
    PlayerRace r = of(p.getUniqueId());
    // height
    AttributeInstance scale = p.getAttribute(Attribute.SCALE);
    if (scale != null) {
      scale.setBaseValue(scale.getDefaultValue()); // old "/attribute ... scale base set" tests
      double s = config.scale(race, r.gender);
      if (Math.abs(s - 1) > 1.0E-3)
        scale.addModifier(new AttributeModifier(scaleKey, s - 1, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
    }
    // Minecraft attribute passives
    int i = 0;
    for (Passive ps : race.passives()) {
      if (!ps.kind().equals("attribute")) continue;
      Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(ps.target()));
      AttributeInstance ai = a == null ? null : p.getAttribute(a);
      if (ai == null) {
        plugin.getLogger().warning("Расы: " + race.id() + ": нет атрибута " + ps.target());
        continue;
      }
      ai.addModifier(new AttributeModifier(new NamespacedKey(plugin, "race_" + i++), ps.amount(), op(ps.mode()), EquipmentSlotGroup.ANY));
    }
    applyStats(p, race, 0);
    applySkin(p, race, r);
  }

  private static AttributeModifier.Operation op(String mode) {
    return switch (mode) {
      case "add_multiplied_base" -> AttributeModifier.Operation.ADD_SCALAR;
      case "add_multiplied_total" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
      default -> AttributeModifier.Operation.ADD_NUMBER;
    };
  }

  /** MythicLib loads player data a little after join: retry for ~15 s. */
  private void applyStats(Player p, Race race, int attempt) {
    if (!p.isOnline()) return;
    if (!Mmo.clearStats(p.getUniqueId())) {
      if (attempt < 15) Bukkit.getScheduler().runTaskLater(plugin, () -> applyStats(p, race, attempt + 1), 20);
      else plugin.getLogger().warning("Расы: MythicLib не загрузил данные " + p.getName() + " — статы расы не выданы.");
      return;
    }
    if (!race.id().equals(of(p.getUniqueId()).race)) return; // changed meanwhile
    for (Passive ps : race.passives()) {
      if (!ps.kind().equals("stat")) continue;
      try {
        Mmo.addStat(p.getUniqueId(), ps.target(), ps.amount(), ps.mode().equals("relative"));
      } catch (Exception e) {
        plugin.getLogger().warning("Расы: " + race.id() + ": стат " + ps.target() + " не выдан: " + e);
      }
    }
  }

  /** Removes everything a race gave. */
  void clear(Player p) {
    for (Attribute a : Registry.ATTRIBUTE) {
      AttributeInstance ai = p.getAttribute(a);
      if (ai == null) continue;
      for (AttributeModifier mod : new ArrayList<>(ai.getModifiers())) {
        NamespacedKey k = mod.getKey();
        if (k.getNamespace().equals(scaleKey.getNamespace()) && k.getKey().startsWith("race_")) ai.removeModifier(mod);
      }
    }
    Mmo.clearStats(p.getUniqueId());
  }

  // ---------------------------------------------------------------- skins
  private void applySkin(Player p, Race race, PlayerRace r) {
    List<Skin> skins = race.skins().getOrDefault(r.gender, List.of());
    if (skins.isEmpty()) return;
    if (r.skin < 0 || r.skin >= skins.size()) {
      r.skin = new Random().nextInt(skins.size());
      save(r);
    }
    Skin s = skins.get(r.skin);
    PlayerProfile profile = p.getPlayerProfile();
    ProfileProperty now = profile.getProperties().stream().filter(x -> x.getName().equals("textures")).findFirst().orElse(null);
    if (now != null && now.getValue().equals(s.value())) return; // already on
    if (r.ownValue == null && now != null && now.getSignature() != null) { // remember the player's own skin once
      r.ownValue = now.getValue();
      r.ownSignature = now.getSignature();
      save(r);
    }
    profile.setProperty(new ProfileProperty("textures", s.value(), s.signature()));
    p.setPlayerProfile(profile);
  }

  private void restoreOwnSkin(Player p, PlayerRace r) {
    if (r.ownValue == null || r.ownSignature == null) return;
    PlayerProfile profile = p.getPlayerProfile();
    profile.setProperty(new ProfileProperty("textures", r.ownValue, r.ownSignature));
    p.setPlayerProfile(profile);
  }

  // ---------------------------------------------------------------- MMOCore class rules
  private void classChange(Mmo.ClassChange c) {
    if (!config.classRules || c.forced() || c.newClass().equals(config.defaultClass)) return;
    Player p = c.player();
    Optional<Race> race = raceOf(p);
    if (race.isEmpty()) {
      c.cancel().accept(true);
      msg(p, "&cСначала выберите расу.");
      Bukkit.getScheduler().runTask(plugin, () -> menus.factions(p));
      return;
    }
    if (!config.classAllowed(race.get(), c.newClass())) {
      c.cancel().accept(true);
      msg(p, "&c" + Text.c(config.className(c.newClass())) + " &cнедоступен расе " + race.get().name() + "&c.");
      msg(p, "&7Доступно: " + String.join("&7, ", race.get().classes().stream().map(config::className).toList()));
    }
  }

  // ---------------------------------------------------------------- events
  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    PlayerRace r = of(p.getUniqueId());
    if (r.chosen()) {
      Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) apply(p, true); }, 10);
    } else {
      clear(p);
      if (config.openOnJoin)
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && !of(p.getUniqueId()).chosen()) menus.factions(p); }, 40);
    }
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) { // MythicLib keeps stats; re-check the vanilla side
    Player p = e.getPlayer();
    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && of(p.getUniqueId()).chosen()) apply(p, false); }, 5);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    data.remove(e.getPlayer().getUniqueId());
  }

  // ---------------------------------------------------------------- commands
  /** Short commands /race and /racial: the same as /twm race and /twm racial. */
  @Override
  public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
    if (cmd.getName().equals("racial")) racial(s);
    else race(s, a);
    return true;
  }

  /** /twm racial — cast the racial ability. */
  public void racial(CommandSender s) {
    try {
      Player p = player(s);
      if (!p.hasPermission("twm.race")) throw new IllegalArgumentException("Нет права twm.race");
      racials.cast(p, raceOf(p).orElseThrow(() -> new IllegalArgumentException("Сначала выберите расу: /twm race")));
    } catch (IllegalArgumentException | IllegalStateException e) {
      s.sendMessage(Text.c("&c" + e.getMessage()));
    }
  }

  /** /twm race [set|reset|info|open|reload|help]. Без подкоманды — карточка расы или выбор. */
  public void race(CommandSender s, String[] a) {
    try {
      if (a.length == 0) {
        Player p = player(s);
        if (!p.hasPermission("twm.race")) throw new IllegalArgumentException("Нет права twm.race");
        if (of(p.getUniqueId()).chosen()) menus.info(p);
        else menus.factions(p);
        return;
      }
      if (a[0].equalsIgnoreCase("help")) {
        help(s);
        return;
      }
      if (!s.hasPermission("twm.admin")) throw new IllegalArgumentException("Нет права twm.admin");
      switch (a[0].toLowerCase(Locale.ROOT)) {
        case "set" -> {
          need(a, 3);
          Player p = online(a[1]);
          Race race = config.get(a[2]);
          Gender g = a.length >= 4 ? Gender.parse(a[3]) : of(p.getUniqueId()).gender;
          set(p, race, g);
          s.sendMessage(Text.c("&aРаса " + p.getName() + ": &r" + race.name() + " &7(" + g.ru.toLowerCase() + ")"));
          if (p != s) msg(p, "<#FFD100>✦ Ваша раса изменена: &r" + race.name());
          String cls = Mmo.classId(p);
          if (cls != null && !config.classAllowed(race, cls))
            s.sendMessage(Text.c("&eВнимание: класс " + Text.c(config.className(cls)) + " &eнедоступен этой расе."));
        }
        case "reset" -> {
          need(a, 2);
          Player p = online(a[1]);
          reset(p);
          s.sendMessage(Text.c("&eРаса " + p.getName() + " сброшена."));
          msg(p, "&eВаша раса сброшена. Выберите новую.");
          menus.factions(p);
        }
        case "info" -> {
          Player p = a.length >= 2 ? online(a[1]) : player(s);
          PlayerRace r = of(p.getUniqueId());
          s.sendMessage(Text.c("&6" + p.getName() + ": &f" + (r.chosen() ? Text.c(config.get(r.race).name()) + " &7(" + r.gender.ru.toLowerCase()
              + ", скин " + r.skin + ")" : "раса не выбрана")));
        }
        case "open" -> {
          need(a, 2);
          menus.factions(online(a[1]));
        }
        case "reload" -> {
          reload();
          for (Player p : Bukkit.getOnlinePlayers()) apply(p, true);
          s.sendMessage(Text.c("&araces.yml перечитан, расы переприменены."));
        }
        default -> help(s);
      }
    } catch (IllegalArgumentException | IllegalStateException e) {
      s.sendMessage(Text.c("&c" + e.getMessage()));
    }
  }

  private static void help(CommandSender s) {
    s.sendMessage(Text.c("&6/twm race &7— ваша раса или выбор (/race)  &6/twm racial &7— расовая способность (/racial)"));
    s.sendMessage(Text.c("&6/twm race set <ник> <раса> [male|female] &7| &6/twm race reset <ник> &7| &6/twm race info [ник]"));
    s.sendMessage(Text.c("&6/twm race open <ник> &7— окно выбора &7| &6/twm race reload"));
  }

  private static void need(String[] a, int n) {
    if (a.length < n) throw new IllegalArgumentException("Не хватает аргументов. /twm race help");
  }

  private static Player player(CommandSender s) {
    if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игрока");
    return p;
  }

  private static Player online(String name) {
    Player p = Bukkit.getPlayerExact(name);
    if (p == null) throw new IllegalArgumentException("Игрок не в сети: " + name);
    return p;
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
    return cmd.getName().equals("race") ? tab(s, a) : List.of();
  }

  /** Tab completion for /twm race … and /race. */
  public List<String> tab(CommandSender s, String[] a) {
    if (a.length == 0) return List.of();
    if (!s.hasPermission("twm.admin")) return a.length == 1 ? List.of("help") : List.of();
    List<String> out = switch (a.length) {
      case 1 -> List.of("help", "set", "reset", "info", "open", "reload");
      case 2 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
      case 3 -> a[0].equalsIgnoreCase("set") ? new ArrayList<>(config.races.keySet()) : List.of();
      case 4 -> a[0].equalsIgnoreCase("set") ? List.of("male", "female") : List.of();
      default -> List.<String>of();
    };
    return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(a[a.length - 1].toLowerCase(Locale.ROOT))).toList();
  }

  // ---------------------------------------------------------------- text
  static void msg(Player p, String s) {
    p.sendMessage(Text.c(s));
  }

  static void bar(Player p, String s) {
    p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(Text.c(s)));
  }
}
