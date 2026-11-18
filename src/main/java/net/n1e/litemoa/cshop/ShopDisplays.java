package net.n1e.litemoa.cshop;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Temporary visuals only: shop inventories and signs remain the source of truth. */
final class ShopDisplays implements Listener {
    private record Visual(Item item, TextDisplay text) {
        void remove() {
            item.remove();
            text.remove();
        }
    }

    private final Cshop plugin;
    private final Map<String, Visual> visuals = new HashMap<>();
    private final BukkitTask task;
    private final NamespacedKey previewKey;
    private final double displayRangeSquared;
    private final float textViewRange;

    ShopDisplays(Cshop plugin) {
        this.plugin = plugin;
        previewKey = new NamespacedKey(plugin, "shop_preview");
        double range = plugin.getConfig().getDouble("hologram-display-distance", 24.0);
        if (!Double.isFinite(range) || range < 0 || !Double.isFinite(range * range)) {
            plugin.getLogger().warning("Invalid hologram-display-distance; using 24 blocks.");
            range = 24.0;
        }
        displayRangeSquared = range * range;
        textViewRange = (float) Math.max(0.75, range / 64.0);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 1L, 20L);
    }

    private void refreshAll() {
        Set<String> active = new HashSet<>();
        for (Shop shop : plugin.all()) {
            active.add(shop.key());
            plugin.restoreMissingSign(shop);
            refresh(shop);
        }
        for (String key : new HashSet<>(visuals.keySet())) {
            if (!active.contains(key)) remove(key);
        }
    }

    void refresh(Shop shop) {
        World world = Bukkit.getWorld(shop.world);
        // Do not load chunks just to display a shop.
        if (world == null || !world.isChunkLoaded(shop.x >> 4, shop.z >> 4)) {
            remove(shop.key());
            return;
        }
        // Remove the actual entities, rather than only hiding them on clients.
        // Another nearby player keeps the shared display alive.
        Location displayLocation = new Location(world, shop.x + 0.5, shop.y + 1.0, shop.z + 0.5);
        if (displayRangeSquared == 0 || world.getPlayers().stream().noneMatch(player ->
                player.getLocation().distanceSquared(displayLocation) <= displayRangeSquared)) {
            remove(shop.key());
            return;
        }
        // Keep the dropped item low and reserve the upper half for two full-size text lines.
        // Signs above the chest also count as occupied space.
        if (plugin.inventory(shop) == null
                || !world.getBlockAt(shop.x, shop.y + 1, shop.z).getType().isAir()) {
            remove(shop.key());
            return;
        }

        Visual visual = visuals.get(shop.key());
        if (visual == null || !visual.item().isValid() || !visual.text().isValid()) {
            remove(shop.key());
            Location center = new Location(world, shop.x + 0.5, shop.y + 1.0, shop.z + 0.5);
            // Shared dropped-item rendering for Java and Bedrock, with no artificial scaling.
            Item item = world.dropItem(center,
                    shop.item.clone(), entity -> {
                        entity.setPersistent(false);
                        entity.setGravity(false);
                        entity.setVelocity(new Vector());
                        entity.setInvulnerable(true);
                        entity.setPickupDelay(Integer.MAX_VALUE);
                        entity.setUnlimitedLifetime(true);
                        entity.setOwner(UUID.randomUUID());
                        entity.getPersistentDataContainer().set(
                                previewKey, PersistentDataType.BYTE, (byte) 1);
                    });
            TextDisplay text;
            try {
                text = world.spawn(center.clone().add(0, 0.48, 0), TextDisplay.class, entity -> {
                    entity.setPersistent(false);
                    entity.setInvulnerable(true);
                    // Face each viewer horizontally. Staying upright prevents long text
                    // tilting into the ceiling when viewed from above or below.
                    entity.setBillboard(Display.Billboard.VERTICAL);
                    entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                    entity.setDefaultBackground(false);
                    entity.setShadowed(true);
                    entity.setLineWidth(Integer.MAX_VALUE);
                    entity.setViewRange(textViewRange);
                });
            } catch (RuntimeException ex) {
                item.remove();
                throw ex;
            }
            visual = new Visual(item, text);
            visuals.put(shop.key(), visual);
        }

        String prices = ChatColor.GREEN + "Buy: "
                + (shop.sellPrice > 0 ? plugin.money(shop.sellPrice) : "OFF")
                + ChatColor.GRAY + "  /  " + ChatColor.RED + "Sell: "
                + (shop.buyPrice > 0 ? plugin.money(shop.buyPrice) : "OFF");
        String text = ChatColor.WHITE + plugin.itemName(shop) + "\n" + prices;
        if (!text.equals(visual.text().getText())) visual.text().setText(text);
    }

    void remove(String key) {
        Visual visual = visuals.remove(key);
        if (visual != null) visual.remove();
    }

    private boolean preview(org.bukkit.entity.Entity entity) {
        return entity.getPersistentDataContainer().has(previewKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (preview(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopperPickup(InventoryPickupItemEvent event) {
        if (preview(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void merge(ItemMergeEvent event) {
        if (preview(event.getEntity()) || preview(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void despawn(ItemDespawnEvent event) {
        if (preview(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(EntityPortalEvent event) {
        if (preview(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (preview(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fish(PlayerFishEvent event) {
        if (event.getCaught() != null && preview(event.getCaught())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void unload(ChunkUnloadEvent event) {
        for (Shop shop : plugin.all()) {
            if (shop.world.equals(event.getWorld().getUID())
                    && (shop.x >> 4) == event.getChunk().getX()
                    && (shop.z >> 4) == event.getChunk().getZ()) remove(shop.key());
        }
    }

    void close() {
        task.cancel();
        for (Visual visual : visuals.values()) visual.remove();
        visuals.clear();
    }
}
