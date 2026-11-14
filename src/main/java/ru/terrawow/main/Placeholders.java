package ru.terrawow.main;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class Placeholders extends PlaceholderExpansion {
  private final TerraWoWmain p;

  public Placeholders(TerraWoWmain p) {
    this.p = p;
  }

  public String getIdentifier() {
    return "twm";
  }

  public String getAuthor() {
    return "TerraWoW";
  }

  public String getVersion() {
    return "1.0.0";
  }

  public boolean persist() {
    return true;
  }

  /**
   * %twm_faction% — alliance / horde / none (no character yet), %twm_race% — race id or none;
   * used by the HUD (faction shield). Everything else is a currency: %twm_gold%, %twm_raw_gold%.
   */
  public String onRequest(OfflinePlayer player, String param) {
    if (player == null) return "";
    if (param.equals("race") || param.equals("faction")) {
      String race = p.races == null ? null : p.races.raceId(player.getUniqueId());
      if (race == null) return "none";
      if (param.equals("race")) return race;
      String f = p.races.factionOf(race);
      return f == null ? "none" : f;
    }
    String c = param.startsWith("raw_") ? param.substring(4) : param;
    try {
      long n = p.ledger.cached(player.getUniqueId().toString(), c);
      return param.startsWith("raw_") ? Long.toString(n) : p.ledger.currency(c).format(n);
    } catch (Exception e) {
      return null;
    }
  }
}
