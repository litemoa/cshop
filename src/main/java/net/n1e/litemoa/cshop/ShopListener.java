package net.n1e.litemoa.cshop;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ShopListener implements Listener {
    private final Cshop plugin;
    private final Menus menus;
    private final Map<UUID, Prompt> prompts =
            new HashMap<>();

    private record Prompt(
            Shop shop,
            boolean sale,
            long expires) { }

    public ShopListener(
            Cshop plugin,
            Menus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    private boolean protectedBlock(Block b) {
        return plugin.get(b) != null
                || plugin.getSign(b) != null;
    }

    // Claim shop clicks before Landlanru's NORMAL protection handler. Keep the
    // event cancelled so only our GUI opens, never the underlying container or
    // an item-in-hand action. Already-cancelled events remain respected.
    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        if (e.getAction()
                != Action.RIGHT_CLICK_BLOCK
                || e.getClickedBlock() == null) {
            return;
        }

        Shop shop =
                plugin.get(e.getClickedBlock());

        if (shop == null) {
            shop =
                    plugin.getSign(
                            e.getClickedBlock());
        }

        if (shop == null) return;

        e.setCancelled(true);

        if (e.getHand()
                != org.bukkit.inventory
                .EquipmentSlot.HAND) {
            return;
        }

        if (plugin.isWorldDisabled(shop)) {
            Player p = e.getPlayer();

            if (plugin.canManage(p, shop)) {
                menus.openSettings(p, shop);
            } else {
                plugin.msg(
                        p,
                        "このワールドではショップを利用できません"
                                + " / Shops are disabled"
                                + " in this world.");
            }
            return;
        }

        if (!e.getPlayer()
                .hasPermission("chestshop.use")) {
            plugin.msg(
                    e.getPlayer(),
                    "権限がありません"
                            + " / No permission.");
            return;
        }

        plugin.updateSign(shop);
        menus.open(e.getPlayer(), shop);
    }

    @EventHandler(ignoreCancelled = true)
    public void breakBlock(
            BlockBreakEvent e) {
        if (protectedBlock(e.getBlock())) {
            e.setCancelled(true);
            plugin.msg(
                    e.getPlayer(),
                    "設定画面から削除してください"
                            + " / Remove the shop"
                            + " from Settings.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void change(
            SignChangeEvent e) {
        if (plugin.getSign(e.getBlock())
                != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void explode(
            BlockExplodeEvent e) {
        e.blockList()
                .removeIf(
                        this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void explode(
            EntityExplodeEvent e) {
        e.blockList()
                .removeIf(
                        this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void piston(
            BlockPistonExtendEvent e) {
        if (e.getBlocks().stream()
                .anyMatch(
                        this::protectedBlock)
                || e.getBlocks().stream()
                .map(b -> b.getRelative(
                        e.getDirection()))
                .anyMatch(
                        this::protectedBlock)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void piston(
            BlockPistonRetractEvent e) {
        if (e.getBlocks().stream()
                .anyMatch(
                        this::protectedBlock)) {
            e.setCancelled(true);
        }
    }

    private boolean shopInventory(
            Inventory inv) {
        InventoryHolder holder =
                inv.getHolder();

        return holder
                instanceof Container c
                && plugin.get(c.getBlock())
                != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void hopper(
            InventoryMoveItemEvent e) {
        // Allow hopper/dropper restocking, but never let automation take stock
        // out of a shop without going through its paid trade flow.
        if (shopInventory(e.getSource())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void restocked(InventoryMoveItemEvent e) {
        if (!(e.getDestination().getHolder() instanceof Container container)) return;
        Shop shop = plugin.get(container.getBlock());
        if (shop == null) return;
        menus.changed(shop);
    }

    @EventHandler
    public void prompt(
            Menus.PricePromptEvent e) {
        synchronized (prompts) {
            prompts.put(
                    e.player.getUniqueId(),
                    new Prompt(
                            e.shop,
                            e.sale,
                            System.currentTimeMillis()
                                    + 60_000));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void chat(
            AsyncPlayerChatEvent e) {
        UUID id =
                e.getPlayer().getUniqueId();

        Prompt prompt;

        synchronized (prompts) {
            prompt = prompts.remove(id);
        }

        if (prompt == null) return;

        e.setCancelled(true);
        String input =
                e.getMessage().trim();

        Bukkit.getScheduler().runTask(
                plugin, () -> {
                    Player p =
                            Bukkit.getPlayer(id);

                    if (p == null) return;

                    if (input.equalsIgnoreCase(
                            "cancel")) {
                        plugin.msg(
                                p,
                                "キャンセルしました"
                                        + " / Cancelled.");
                        return;
                    }

                    if (System.currentTimeMillis()
                            > prompt.expires
                            || !plugin.all()
                            .contains(
                                    prompt.shop)
                            || !plugin.canManage(p, prompt.shop)) {
                        plugin.msg(
                                p,
                                "入力の期限が切れました"
                                        + " / Input expired.");
                        return;
                    }

                    double price;

                    try {
                        price =
                                Double.parseDouble(
                                        input);
                    } catch (
                            NumberFormatException ex) {
                        plugin.msg(
                                p,
                                "数字を入力してください"
                                        + " / Enter a number.");
                        return;
                    }

                    Shop s = prompt.shop;

                    if (!plugin.valid(price)
                            || (price == 0
                            && (prompt.sale
                            ? s.buyPrice
                            : s.sellPrice)
                            == 0)) {
                        plugin.msg(
                                p,
                                "無効な価格です。両方0にはできません"
                                        + " / Invalid price;"
                                        + " both cannot be 0.");
                        return;
                    }

                    if (prompt.sale) {
                        s.sellPrice = price;
                    } else {
                        s.buyPrice = price;
                    }

                    menus.changed(s);
                    plugin.saveShops();
                    menus.reopenPrices(p, s);

                    plugin.msg(
                            p,
                            "価格を変更しました"
                                    + " / Price updated.");
                });
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> plugin.deliverNotifications(
                        e.getPlayer()),
                20L);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        synchronized (prompts) {
            prompts.remove(
                    e.getPlayer().getUniqueId());
        }
    }
}
