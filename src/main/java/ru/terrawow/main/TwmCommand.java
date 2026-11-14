package ru.terrawow.main;

import java.util.*;
import org.bukkit.command.*;
import ru.terrawow.main.collection.CollectionConfig.Kind;
import ru.terrawow.main.collection.Text;

/**
 * The one root command of the plugin: /twm &lt;module&gt; … . Each module keeps its own logic; this
 * class only routes. Short player commands (/mounts, /pets, /race, /racial) call the same code.
 *
 * <pre>
 * /twm economy …   (eco, money)  кошелёк, банк, почта, торговля, аукцион   → Commands
 * /twm mount …     (mounts)      журнал маунтов и админ-команды            → CollectionModule
 * /twm pet …       (pets)        журнал питомцев и админ-команды           → CollectionModule
 * /twm race …                    раса: карточка, выбор, set/reset/info      → RaceModule
 * /twm racial                    расовая способность                        → RaceModule
 * /twm menu [id] [ник]            меню сервера (/menu = главное)             → MenuModule
 * /twm lang give|take|list …       языки игрока (twm.admin); /lang — свой язык → ChatModule
 * /twm reload                    перечитать collections.yml и races.yml (twm.admin)
 * </pre>
 */
final class TwmCommand implements CommandExecutor, TabCompleter {
  private static final List<String> ROOT = List.of("help", "menu", "economy", "mount", "pet", "race", "racial", "lang", "reload");
  private final TerraWoWmain p;

  TwmCommand(TerraWoWmain p) {
    this.p = p;
  }

  @Override
  public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
    if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
      help(s);
      return true;
    }
    String[] rest = Arrays.copyOfRange(a, 1, a.length);
    switch (a[0].toLowerCase(Locale.ROOT)) {
      case "economy", "eco", "money" -> p.commands.onCommand(s, cmd, label, rest);
      case "mount", "mounts" -> {
        if (p.collections == null) off(s, "маунтов и питомцев (modules.collections)");
        else p.collections.command(s, Kind.MOUNT, rest);
      }
      case "pet", "pets" -> {
        if (p.collections == null) off(s, "маунтов и питомцев (modules.collections)");
        else p.collections.command(s, Kind.PET, rest);
      }
      case "race" -> {
        if (p.races == null) off(s, "рас (modules.races)");
        else p.races.race(s, rest);
      }
      case "racial" -> {
        if (p.races == null) off(s, "рас (modules.races)");
        else p.races.racial(s);
      }
      case "menu", "menus" -> {
        if (p.menuModule == null) off(s, "меню (modules.menus)");
        else p.menuModule.command(s, rest);
      }
      case "lang", "language" -> {
        if (p.chat == null) off(s, "чата и языков (modules.chat)");
        else p.chat.admin(s, rest);
      }
      case "reload" -> reload(s);
      default -> s.sendMessage(Text.c("&cНеизвестный раздел: " + a[0] + ". &7/twm help"));
    }
    return true;
  }

  private static void off(CommandSender s, String what) {
    s.sendMessage(Text.c("&cМодуль " + what + " выключен или не запустился — см. консоль."));
  }

  private void reload(CommandSender s) {
    if (!s.hasPermission("twm.admin")) {
      s.sendMessage(Text.c("&cНет права twm.admin"));
      return;
    }
    if (p.collections != null) p.collections.command(s, Kind.MOUNT, new String[] {"reload"});
    if (p.races != null) p.races.race(s, new String[] {"reload"});
    if (p.menuModule != null) p.menuModule.command(s, new String[] {"reload"});
    if (p.chat != null) p.chat.admin(s, new String[] {"reload"});
    s.sendMessage(Text.c("&7Экономика (config.yml, messages.yml, ui.yml) применяется только перезапуском сервера."));
  }

  private static void help(CommandSender s) {
    s.sendMessage(Text.c("<#FFD100>TerraWoWmain &7— команды /twm:"));
    s.sendMessage(Text.c("&6/twm menu [меню] &7— меню сервера &8(/menu, /wow)"));
    s.sendMessage(Text.c("&6/twm economy &7— кошелёк, банк, почта, торговля, аукцион &8(/twm economy help)"));
    s.sendMessage(Text.c("&6/twm mount &7— маунты &8(/mounts, /twm mount help)"));
    s.sendMessage(Text.c("&6/twm pet &7— питомцы &8(/pets, /twm pet help)"));
    s.sendMessage(Text.c("&6/twm race &7— раса &8(/race, /twm race help)  &6/twm racial &7— расовая способность &8(/racial)"));
    s.sendMessage(Text.c("&6/lang [язык] &7— на каком языке вы говорите"));
    if (s.hasPermission("twm.admin")) s.sendMessage(Text.c("&6/twm lang give|take|list <ник> [язык] &7— выучить / забыть язык"));
    if (s.hasPermission("twm.admin")) s.sendMessage(Text.c("&6/twm reload &7— перечитать collections.yml и races.yml"));
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
    if (a.length <= 1) {
      String last = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
      return ROOT.stream().filter(x -> x.startsWith(last)).toList();
    }
    String[] rest = Arrays.copyOfRange(a, 1, a.length);
    return switch (a[0].toLowerCase(Locale.ROOT)) {
      case "economy", "eco", "money" -> p.commands.onTabComplete(s, cmd, label, rest);
      case "mount", "mounts" -> p.collections == null ? List.of() : p.collections.tab(s, Kind.MOUNT, rest);
      case "pet", "pets" -> p.collections == null ? List.of() : p.collections.tab(s, Kind.PET, rest);
      case "race" -> p.races == null ? List.of() : p.races.tab(s, rest);
      case "menu", "menus" -> p.menuModule == null ? List.of() : p.menuModule.tab(s, rest);
      case "lang", "language" -> p.chat == null ? List.of() : p.chat.adminTab(rest);
      default -> List.of();
    };
  }
}
