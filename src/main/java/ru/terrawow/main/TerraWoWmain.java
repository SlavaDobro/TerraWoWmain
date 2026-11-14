package ru.terrawow.main;

import static ru.terrawow.main.Database.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class TerraWoWmain extends JavaPlugin implements Listener {
  public Ledger ledger;
  public Custody custody;
  public Menus menus;
  public Commands commands;
  public ru.terrawow.main.collection.CollectionModule collections;
  public ru.terrawow.main.race.RaceModule races;
  public ru.terrawow.main.chat.ChatModule chat;
  public ru.terrawow.main.menu.MenuModule menuModule;
  private Database db;
  private ThreadPoolExecutor worker;
  private YamlConfiguration messages;
  private YamlConfiguration labels;
  public volatile boolean economyHealthy = true;
  private VaultBridge vault;
  public Map<String, Long> units = new HashMap<>();
  private final Map<String, Long> countdown = new HashMap<>();

  @Override
  public void onEnable() {
    try {
      saveDefaultConfig();
      saveResourceIfMissing("messages.yml");
      saveResourceIfMissing("ui.yml");
      labels = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "ui.yml"));
      Money.primaryFormat = getConfig().getString("money-format", "{g} з {s} с {c} м");
      messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
      Map<String, Money.Currency> currencies = new LinkedHashMap<>();
      var section = getConfig().getConfigurationSection("currencies");
      if (section == null) throw new IllegalArgumentException("Нет валют");
      for (String id : section.getKeys(false)) {
        var c = section.getConfigurationSection(id);
        currencies.put(
            id,
            new Money.Currency(
                id,
                c.getString("name", id),
                c.getString("symbol", id),
                c.getString("icon", "GOLD_NUGGET"),
                c.getInt("precision"),
                c.getLong("start"),
                c.getLong("limit"),
                c.getBoolean("transfer"),
                c.getBoolean("trade"),
                c.getBoolean("physical"),
                c.getString("permission", "")));
      }
      if (!currencies.containsKey("gold"))
        throw new IllegalArgumentException("Основная валюта gold обязательна");
      var unit = getConfig().getConfigurationSection("units");
      for (String key : unit.getKeys(false)) {
        long n = unit.getLong(key);
        if (n <= 0) throw new IllegalArgumentException("Неверный номинал");
        units.put(key, n);
      }
      validateRates();
      String backend = getConfig().getString("storage.type", "sqlite");
      if (!List.of("sqlite", "mariadb").contains(backend))
        throw new IllegalArgumentException("storage.type: sqlite или mariadb");
      String identity =
          backend + (backend.equals("mariadb") ? "|" + getConfig().getString("storage.url") : "");
      java.nio.file.Path marker = getDataFolder().toPath().resolve("storage.identity");
      if (java.nio.file.Files.exists(marker)
          && !java.nio.file.Files.readString(marker).equals(identity))
        throw new IllegalStateException(
            "Изменён backend/адрес БД. Сначала выполните проверяемый перенос данных;"
                + " storage.identity не меняется автоматически.");
      if (!java.nio.file.Files.exists(marker)
          && backend.equals("mariadb")
          && new File(getDataFolder(), "economy.sqlite").exists())
        throw new IllegalStateException(
            "Обнаружена SQLite БД. Автоматическое переключение запрещено.");
      db =
          getConfig().getString("storage.type", "sqlite").equals("mariadb")
              ? new Database(
                  new MariaDbStorage(
                      getConfig().getString("storage.url"),
                      getConfig().getString("storage.user"),
                      System.getenv()
                          .getOrDefault(
                              getConfig().getString("storage.password-env", "AE_DB_PASSWORD"), "")))
              : new Database(new File(getDataFolder(), "economy.sqlite").toPath());
      ledger = new Ledger(db, currencies);
      java.nio.file.Files.writeString(marker, identity);
      ledger.recover();
      ledger.maxPlayerMails = getConfig().getInt("limits.player-mails", 100);
      ledger.maxCustodyItems = getConfig().getInt("limits.custody-items", 500);
      ledger.maxListings = getConfig().getInt("limits.listings", 50);
      ledger.committed =
          changes -> {
            if (!changes.isEmpty())
              main(() -> Bukkit.getPluginManager().callEvent(new LedgerCommittedEvent(changes)));
          };
      worker =
          new ThreadPoolExecutor(
              1,
              1,
              0,
              TimeUnit.SECONDS,
              new ArrayBlockingQueue<>(512),
              r -> {
                Thread t = new Thread(r, "TerraWoWmain-storage");
                t.setDaemon(true);
                return t;
              });
      custody = new Custody(this);
      menus = new Menus(this);
      commands = new Commands(this);
      TwmCommand twm = new TwmCommand(this); // /twm economy|mount|pet|race|racial|reload
      Objects.requireNonNull(getCommand("twm")).setExecutor(twm);
      getCommand("twm").setTabCompleter(twm);
      getServer().getPluginManager().registerEvents(this, this);
      getServer().getPluginManager().registerEvents(custody, this);
      getServer().getPluginManager().registerEvents(menus, this);
      if (getConfig().getBoolean("vault.enabled")) {
        if (!getServer().getServicesManager().getRegistrations(Economy.class).isEmpty())
          throw new IllegalStateException(
              "Уже зарегистрирован другой провайдер Vault; переключение запрещено");
        vault = new VaultBridge(this);
        getServer()
            .getServicesManager()
            .register(Economy.class, vault, this, ServicePriority.Normal);
      }
      getServer()
          .getServicesManager()
          .register(EconomyApi.class, new EconomyApi(this), this, ServicePriority.Normal);
      if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null)
        new Placeholders(this).register();
      for (String alias : getConfig().getStringList("aliases")) {
        if (!alias.matches("[a-z]+") || getServer().getCommandMap().getCommand(alias) != null) {
          getLogger().warning("Алиас занят или неверен: " + alias);
          continue;
        }
        getServer()
            .getCommandMap()
            .register(
                "terrawowmain",
                new Command(alias) {
                  public boolean execute(CommandSender s, String label, String[] a) {
                    return commands.onCommand(s, this, label, a);
                  }
                });
      }
      Bukkit.getScheduler().runTaskTimer(this, this::tick, 20, 20);
      for (Player pl : Bukkit.getOnlinePlayers()) register(pl);
      if (getConfig().getBoolean("modules.collections", true))
        try { // mounts & pets; a broken collections.yml must not stop the economy
          collections = new ru.terrawow.main.collection.CollectionModule(this);
          collections.enable();
        } catch (Exception e) {
          collections = null;
          getLogger().log(java.util.logging.Level.SEVERE, "Коллекция маунтов и питомцев не запущена", e);
        }
      if (getConfig().getBoolean("modules.races", true))
        try { // WoW races; a broken races.yml must not stop the economy
          races = new ru.terrawow.main.race.RaceModule(this);
          races.enable();
        } catch (Exception e) {
          races = null;
          getLogger().log(java.util.logging.Level.SEVERE, "Модуль рас не запущен", e);
        }
      if (getConfig().getBoolean("modules.chat", true))
        try { // WoW chat with languages; a broken languages.yml must not stop the economy
          chat = new ru.terrawow.main.chat.ChatModule(this, races);
          chat.enable();
        } catch (Exception e) {
          chat = null;
          getLogger().log(java.util.logging.Level.SEVERE, "Чат с языками не запущен (languages.yml)", e);
        }
      ru.terrawow.main.ui.UiIcons.enable(getConfig().getBoolean("ui-icons", true));
      if (getConfig().getBoolean("ui-sounds.enabled", true)
          && getConfig().getConfigurationSection("ui-sounds") != null)
        getServer().getPluginManager().registerEvents(
            new ru.terrawow.main.ui.UiSounds(this, getConfig().getConfigurationSection("ui-sounds")), this);
      if (getConfig().getBoolean("ui-backgrounds", true))
        getServer().getPluginManager().registerEvents(new ru.terrawow.main.ui.UiBackgrounds(
            ru.terrawow.main.ui.UiSounds.windows(getConfig().getConfigurationSection("ui-sounds"))), this);
      if (getConfig().getBoolean("modules.menus", true))
        try { // server menus (/menu); a broken menus/*.yml must not stop the economy
          menuModule = new ru.terrawow.main.menu.MenuModule(this);
          menuModule.enable();
        } catch (Exception e) {
          menuModule = null;
          getLogger().log(java.util.logging.Level.SEVERE, "Меню не запущены (plugins/TerraWoWmain/menus)", e);
        }
      getLogger()
          .info("SQLite готова; Vault=1 медная; владелец=UUID аккаунта. " + ledger.diagnostics());
      if (getConfig().getBoolean("testing.self-test"))
        Bukkit.getScheduler().runTaskLater(this, () -> RuntimeChecks.run(this), 100);
    } catch (Exception e) {
      getLogger()
          .log(java.util.logging.Level.SEVERE, "Запуск остановлен без изменения чужих балансов", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  private void saveResourceIfMissing(String name) {
    if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
  }

  private void validateRates() {
    var rates = getConfig().getConfigurationSection("exchange");
    if (rates == null) return;
    List<Money.Rate> list = new ArrayList<>();
    for (String from : rates.getKeys(false)) {
      var row = rates.getConfigurationSection(from);
      for (String to : row.getKeys(false)) {
        var r = row.getConfigurationSection(to);
        list.add(
            new Money.Rate(
                from, to, r.getLong("numerator"), r.getLong("denominator"), r.getInt("fee-bps")));
      }
    }
    Money.validateRates(list);
  }

  @Override
  public void onDisable() {
    if (collections != null) collections.disable();
    if (races != null) races.disable();
    if (chat != null) chat.disable();
    economyHealthy = false;
    Bukkit.getServicesManager().unregisterAll(this);
    for (Player pl : Bukkit.getOnlinePlayers())
      if (pl.getOpenInventory().getTopInventory().getHolder() instanceof Menus.Page)
        pl.closeInventory();
    if (worker != null) {
      worker.shutdown();
      try {
        worker.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (db != null)
      try {
        db.close();
      } catch (Exception e) {
        getLogger().severe(e.toString());
      }
  }

  public <T> void async(Callable<T> job, Consumer<T> done, Consumer<Throwable> fail) {
    try {
      worker.execute(
          () -> {
            try {
              T value = job.call();
              main(
                  () -> {
                    try {
                      done.accept(value);
                    } catch (Throwable e) {
                      fail.accept(e);
                    }
                  });
            } catch (Throwable e) {
              main(() -> fail.accept(e));
            }
          });
    } catch (RejectedExecutionException e) {
      fail.accept(new IllegalStateException("Очередь хранилища заполнена", e));
    }
  }

  public void main(Runnable action) {
    if (isEnabled()) Bukkit.getScheduler().runTask(this, action);
  }

  public void action(CommandSender sender, Runnable task, Runnable done) {
    async(
        () -> {
          task.run();
          return true;
        },
        v -> {
          tell(sender, "done");
          done.run();
        },
        e -> error(sender, e));
  }

  public void error(CommandSender sender, Throwable e) {
    sender.sendMessage(
        text("error").replace("{error}", Objects.toString(e.getMessage(), "Ошибка")));
    if (e.getCause() != null)
      getLogger().log(java.util.logging.Level.WARNING, "Операция отклонена", e);
  }

  public String label(String key) {
    return ChatColor.translateAlternateColorCodes('&', labels.getString(key, key));
  }

  public String text(String key) {
    return ChatColor.translateAlternateColorCodes('&', messages.getString(key, key));
  }

  public void tell(CommandSender s, String key) {
    s.sendMessage(text(key));
  }

  public void check(CommandSender sender, String action) {
    if (!economyHealthy
        && !List.of("admin.diagnose", "admin.backup", "admin.recovery").contains(action))
      throw new IllegalStateException("Экономика остановлена: конфликт провайдеров");
    if (!sender.hasPermission("twm.economy." + action))
      throw new IllegalArgumentException("Нет права twm.economy." + action);
  }

  public void currencyPermission(CommandSender s, String id) {
    String permission = ledger.currency(id).permission();
    if (!permission.isEmpty() && !s.hasPermission(permission))
      throw new IllegalArgumentException("Нет доступа к валюте");
  }

  public void module(String name) {
    if (!getConfig().getBoolean("modules." + name))
      throw new IllegalArgumentException("Модуль отключён: " + name);
  }

  public long amount(CommandSender s, String c, String text) {
    currencyPermission(s, c);
    return ledger.currency(c).parse(text, units);
  }

  public void access(Player player, String type) {
    check(player, type);
    module(type);
    if (!getConfig().getBoolean(type + ".remote")
        && !player.hasPermission("twm.economy." + type + ".remote")
        && !menus.localAccess.containsKey(player.getUniqueId() + ":" + type))
      throw new IllegalArgumentException(
          "Подойдите к " + (type.equals("bank") ? "банкиру" : "почтовому ящику"));
  }

  private void register(Player p) {
    UUID id = p.getUniqueId();
    String name = p.getName();
    async(
        () -> {
          ledger.register(id, name);
          return ledger.mails(id.toString(), 0).size();
        },
        n -> {
          if (n > 0 && p.isOnline()) p.sendMessage(text("new-mail").replace("{count}", "" + n));
        },
        e -> error(p, e));
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    register(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    cancelFor(e.getPlayer());
    menus.localAccess.keySet().removeIf(s -> s.startsWith(e.getPlayer().getUniqueId().toString()));
  }

  @EventHandler
  public void died(PlayerDeathEvent e) {
    cancelFor(e.getEntity());
    if (getConfig().getBoolean("modules.death-loss")) {
      String owner = e.getEntity().getUniqueId().toString();
      long loss = Money.fee(ledger.cached(owner, "gold"), getConfig().getInt("death-loss.bps"));
      if (loss > 0)
        action(
            Bukkit.getConsoleSender(),
            () -> ledger.admin("death:" + UUID.randomUUID(), owner, "gold", loss, "take"),
            () -> {});
    }
  }

  @EventHandler
  public void mob(EntityDeathEvent e) {
    if (!getConfig().getBoolean("modules.mob-rewards")
        || e.getEntity() instanceof Player
        || e.getEntity().getKiller() == null) return;
    String owner = e.getEntity().getKiller().getUniqueId().toString();
    long reward = getConfig().getLong("mob-rewards.amounts." + e.getEntityType().name());
    if (reward > 0)
      action(
          Bukkit.getConsoleSender(),
          () -> ledger.admin("mob:" + e.getEntity().getUniqueId(), owner, "gold", reward, "give"),
          () -> {});
  }

  @EventHandler
  public void service(ServiceRegisterEvent e) {
    if (vault != null
        && e.getProvider().getService() == Economy.class
        && e.getProvider().getProvider() != vault) {
      economyHealthy = false;
      getLogger()
          .severe(
              "Обнаружен второй провайдер Vault. Операции остановлены; требуется перезапуск после"
                  + " исправления.");
    }
  }

  private void cancelFor(Player p) {
    String owner = p.getUniqueId().toString();
    async(
        () -> {
          try {
            ledger.cancelTrade(str(ledger.trade(owner), "id"));
          } catch (IllegalArgumentException ignored) {
          }
          return true;
        },
        v -> {},
        e -> getLogger().warning(e.toString()));
  }

  public boolean near(String a, String b) {
    Player pa = Bukkit.getPlayer(UUID.fromString(a)), pb = Bukkit.getPlayer(UUID.fromString(b));
    return pa != null
        && pb != null
        && !pa.isDead()
        && !pb.isDead()
        && pa.getWorld().equals(pb.getWorld())
        && pa.getLocation().distanceSquared(pb.getLocation())
            <= Math.pow(getConfig().getDouble("trade.distance"), 2);
  }

  private boolean ticking;
  private int ticks;

  private void tick() {
    menus.localAccess.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis());
    if (ticking) return;
    ticking = true;
    async(
        () -> ledger.activeTrades(),
        trades -> {
          for (var t : trades) {
            String id = str(t, "id");
            boolean valid = near(str(t, "a"), str(t, "b")) && num(t, "expires") > Ledger.now();
            if (!valid) {
              countdown.remove(id);
              action(Bukkit.getConsoleSender(), () -> ledger.cancelTrade(id), () -> {});
              continue;
            }
            if (str(t, "state").equals("OPEN")
                && num(t, "confirm_a") == num(t, "revision")
                && num(t, "confirm_b") == num(t, "revision")) {
              long revision = num(t, "revision"),
                  deadline =
                      countdown.computeIfAbsent(
                          id + ":" + revision,
                          k ->
                              Ledger.now() + getConfig().getLong("trade.countdown-seconds") * 1000);
              if (Ledger.now() >= deadline) {
                countdown.remove(id + ":" + revision);
                action(
                    Bukkit.getConsoleSender(),
                    () -> ledger.completeTrade(id, revision),
                    () -> {
                      for (String owner : List.of(str(t, "a"), str(t, "b"))) {
                        Player pl = Bukkit.getPlayer(UUID.fromString(owner));
                        if (pl != null) {
                          pl.sendMessage(text("trade-done"));
                          pl.closeInventory();
                        }
                      }
                    });
              } else
                for (String owner : List.of(str(t, "a"), str(t, "b"))) {
                  Player pl = Bukkit.getPlayer(UUID.fromString(owner));
                  if (pl != null)
                    pl.sendActionBar(
                        net.kyori.adventure.text.Component.text(
                            "Обмен через " + ((deadline - Ledger.now() + 999) / 1000) + " с"));
                }
            }
          }
          ticking = false;
        },
        e -> {
          ticking = false;
          getLogger().warning(e.toString());
        });
    if (++ticks % 60 == 0)
      async(
          () -> {
            ledger.expireLots();
            ledger.expireMail();
            runSchedules();
            return true;
          },
          v -> {},
          e -> getLogger().warning(e.toString()));
  }

  private void runSchedules() {
    if (getConfig().getBoolean("modules.interest")) {
      long period = getConfig().getLong("interest.period-seconds", 86400);
      if (period >= 60)
        ledger.interest(
            Long.toString(Ledger.now() / (period * 1000)),
            getConfig().getInt("interest.bps"),
            getConfig().getLong("interest.max-per-period"));
    }
    if (!getConfig().getBoolean("modules.schedules")) return;
    var s = getConfig().getConfigurationSection("schedules");
    if (s == null) return;
    for (String name : s.getKeys(false)) {
      var r = s.getConfigurationSection(name);
      long period = r.getLong("period-seconds");
      if (period < 60) continue;
      long bucket = Ledger.now() / (period * 1000);
      ledger.scheduled(
          name + ":" + bucket,
          r.getString("from", "MINT"),
          r.getString("to"),
          r.getString("currency", "gold"),
          r.getLong("amount"));
    }
  }
}
