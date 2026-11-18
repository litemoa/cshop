package net.n1e.litemoa.cshop;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;

public final class Menus implements Listener {
    private final Cshop plugin;
    private final Map<Shop, Set<Screen>> tradeScreens = new HashMap<>();
    private final Set<Shop> pendingChanges = new HashSet<>();
    private boolean refreshScheduled;
    private final int[] tradeButtons =
            {10, 11, 12, 14, 15, 16};
    private final int[] steps =
            {-64, -16, -1, 1, 16, 64};

    private enum Page {
        TRADE, SETTINGS, PRICE_SELECT, DELETE
    }

    private static final class Screen
            implements InventoryHolder {
        final Shop shop;
        final Page page;
        Inventory inventory;

        Screen(Shop shop, Page page) {
            this.shop = shop;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public Menus(Cshop plugin) {
        this.plugin = plugin;
    }

    private boolean manage(Player p, Shop s) {
        return plugin.canManage(p, s);
    }

    private ItemStack icon(
            Material type,
            int amount,
            String name,
            String... lore) {
        ItemStack stack = new ItemStack(
                type,
                Math.min(64, Math.max(1, amount)));

        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(
                ChatColor.translateAlternateColorCodes(
                        '&', name));

        List<String> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(
                    ChatColor.translateAlternateColorCodes(
                            '&', line));
        }

        meta.setLore(lines);
        stack.setItemMeta(meta);
        return stack;
    }

    private void put(
            Inventory inv,
            int slot,
            Material material,
            String title,
            String... lore) {
        inv.setItem(
                slot,
                icon(material, 1, title, lore));
    }

    private void sound(Player p) {
        p.playSound(
                p.getLocation(),
                Sound.UI_BUTTON_CLICK,
                0.5f,
                1.1f);
    }

    public void open(Player p, Shop s) {
        open(p, s, Page.TRADE);
    }

    public void openSettings(Player p, Shop s) {
        if (manage(p, s)) {
            open(p, s, Page.SETTINGS);
        }
    }

    private record TradeState(boolean exists, int stock, int shopSpace, double ownerBalance,
                              int playerItems, int playerSpace, double playerBalance) { }

    private TradeState tradeState(Player player, Shop shop) {
        Container container = plugin.inventory(shop);
        Inventory inventory = container == null ? null : container.getInventory();
        return new TradeState(container != null,
                shop.admin || inventory == null ? 0 : count(inventory, shop.item),
                shop.admin || inventory == null ? 0 : capacity(inventory, shop.item),
                shop.admin ? 0 : plugin.economy().getBalance(Bukkit.getOfflinePlayer(shop.owner)),
                count(player.getInventory(), shop.item), capacity(player.getInventory(), shop.item),
                plugin.economy().getBalance(player));
    }

    private String unavailableReason(Shop s, boolean buying, int amount, TradeState state) {
        double total = (buying ? s.sellPrice : s.buyPrice) * amount;
        if (buying && s.sellPrice <= 0) return "販売停止中 / Selling disabled";
        if (!buying && s.buyPrice <= 0) return "買取停止中 / Buying disabled";
        if (!plugin.valid(total) || total <= 0) return "金額が大きすぎます / Total is too large";
        if (!state.exists()) return "ショップがありません / Shop container missing";
        if (!s.admin && buying && state.stock() < amount)
            return "在庫が足りません / Not enough stock";
        if (!s.admin && !buying && state.shopSpace() < amount)
            return "チェストに空きがありません / Not enough shop space";
        if (!s.admin && !buying && state.ownerBalance() < total)
            return "ショップの残高不足 / Shop owner lacks funds";
        if (buying && state.playerSpace() < amount)
            return "持ち物に空きがありません / No inventory space";
        if (buying && state.playerBalance() < total)
            return "残高が足りません / Insufficient balance";
        if (!buying && state.playerItems() < amount)
            return "アイテムが足りません / Not enough items";
        return null;
    }

    private void tradeControls(Player p, Inventory inv, Shop s) {
        TradeState state = tradeState(p, s);
        for (int i = 0;
             i < tradeButtons.length;
             i++) {
            int n = steps[i];
            int amount = Math.abs(n);
            boolean buying = n > 0;
            double unit =
                    buying ? s.sellPrice : s.buyPrice;

            String reason =
                    unavailableReason(
                            s, buying, amount, state);

            if (reason != null) {
                inv.setItem(
                        tradeButtons[i],
                        icon(
                                Material.GRAY_DYE,
                                amount,
                                "&8"
                                        + (n > 0 ? "+" : "")
                                        + n
                                        + (buying
                                        ? " 購入不可 / Buy unavailable"
                                        : " 売却不可 / Sell unavailable"),
                                "&c" + reason,
                                "&7この数量は取引できません"
                                        + " / Cannot trade this amount"));
            } else {
                inv.setItem(
                        tradeButtons[i],
                        icon(
                                buying
                                        ? Material.LIME_DYE
                                        : Material.RED_DYE,
                                amount,
                                (buying ? "&a+" : "&c")
                                        + n
                                        + (buying
                                        ? " 購入 / Buy"
                                        : " 売却 / Sell"),
                                buying
                                        ? "&a支払い / Pay: "
                                        + plugin.money(
                                        unit * amount)
                                        : "&c受取 / Receive: "
                                        + plugin.money(
                                        unit * amount),
                                "&7クリックで即売買"
                                        + " / Click to trade"));
            }
        }
    }

    /** Coalesce changes until next tick, after hopper/dropper transfers have completed. */
    public void changed(Shop shop) {
        pendingChanges.add(shop);
        if (refreshScheduled) return;
        refreshScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshScheduled = false;
            Set<Shop> changes = new HashSet<>(pendingChanges);
            pendingChanges.clear();
            for (Shop current : changes) {
                if (!plugin.all().contains(current)) continue;
                plugin.updateSign(current);
                Set<Screen> screens = tradeScreens.get(current);
                if (screens == null) continue;
                for (Screen screen : new ArrayList<>(screens)) {
                    for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(screen.inventory.getViewers())) {
                        if (viewer instanceof Player player
                                && player.getOpenInventory().getTopInventory() == screen.inventory) {
                            renderTrade(player, current, screen.inventory);
                        }
                    }
                }
            }
        });
    }

    private void renderTrade(Player p, Shop s, Inventory inv) {
        ItemStack head = inv.getItem(4);
        if (head == null || head.getType() != Material.PLAYER_HEAD) {
            head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta initial = (SkullMeta) head.getItemMeta();
            initial.setOwningPlayer(p);
            head.setItemMeta(initial);
        }

        SkullMeta skull =
                (SkullMeta) head.getItemMeta();

        skull.setDisplayName(
                ChatColor.AQUA
                        + "所持金 / Balance: "
                        + plugin.money(
                        plugin.economy()
                                .getBalance(p)));

        head.setItemMeta(skull);
        inv.setItem(4, head);

        tradeControls(p, inv, s);

        ItemStack product = s.item.clone();
        ItemMeta meta =
                product.getItemMeta();

        meta.setDisplayName(
                ChatColor.GOLD
                        + plugin.itemName(s));

        meta.setLore(List.of(
                ChatColor.WHITE
                        + "在庫 / Stock: "
                        + plugin.stockLabel(s),
                ChatColor.GREEN
                        + "購入単価 / Buy price: "
                        + (s.sellPrice > 0
                        ? plugin.money(s.sellPrice)
                        : "OFF"),
                ChatColor.RED
                        + "売却単価 / Sell price: "
                        + (s.buyPrice > 0
                        ? plugin.money(s.buyPrice)
                        : "OFF"),
                ChatColor.GRAY
                        + "赤は売却、緑は購入"
                        + " / Red sells, green buys"));

        product.setItemMeta(meta);
        inv.setItem(13, product);

    }

    private void open(
            Player p, Shop s, Page page) {
        Screen holder = new Screen(s, page);

        String title = switch (page) {
            case TRADE ->
                    "SHOP | 売買 / Trade";
            case SETTINGS ->
                    "ショップ設定 / Settings";
            case PRICE_SELECT ->
                    "値段を変更 / Change Prices";
            case DELETE ->
                    "削除確認 / Confirm Delete";
        };

        Inventory inv = Bukkit.createInventory(
                holder,
                27,
                title);
        holder.inventory = inv;

        // 使わない枠は空白。
        if (page == Page.TRADE) {
            renderTrade(p, s, inv);

            if (manage(p, s)) {
                put(inv, 18, Material.COMPARATOR,
                        "&bショップ設定"
                                + " / Shop Settings",
                        "&7所有者 / Owner: "
                                + Bukkit.getOfflinePlayer(
                                        s.owner)
                                .getName());
            }

            put(inv, 26, Material.BARRIER,
                    "&c閉じる / Close");
        } else if (page == Page.SETTINGS) {
            put(inv, 4, Material.COMPARATOR,
                    "&bショップ設定"
                            + " / Shop Settings",
                    "&7所有者 / Owner: "
                            + Bukkit.getOfflinePlayer(
                                    s.owner)
                            .getName());

            put(inv, 11, Material.GOLD_INGOT,
                    "&e値段を変更"
                            + " / Change Prices",
                    "&a販売価格 / Buy Price: "
                            + plugin.money(
                            s.sellPrice),
                    "&c買取価格 / Sell Price: "
                            + plugin.money(
                            s.buyPrice),
                    "&7クリックで価格の種類を選択"
                            + " / Select a price to edit");

            put(inv, 15, Material.BOOK,
                    (s.admin ? "&b在庫・買取資金は無制限" : "&b在庫を開く")
                            + " / Open Stock",
                    "&f在庫 / Stock: "
                            + plugin.stockLabel(s),
                    (s.admin ? "&7補充は不要です" : "&7クリックで実際のコンテナを開く")
                            + " / Open the container");

            put(inv, 13, Material.BARRIER,
                    "&cショップ削除"
                            + " / Remove Shop",
                    "&7確認画面へ"
                            + " / Open confirmation");

            put(inv, 18, Material.ARROW,
                    "&b戻る / Back");
        } else if (page == Page.PRICE_SELECT) {
            put(inv, 4, Material.GOLD_INGOT,
                    "&e値段を変更"
                            + " / Change Prices");

            put(inv, 11, Material.EMERALD,
                    "&a販売価格を変更"
                            + " / Change Sale Price",
                    "&f現在 / Current: "
                            + plugin.money(
                            s.sellPrice),
                    "&7お客さんが購入する価格"
                            + " / Customer purchase price",
                    "&7クリック後にチャット入力"
                            + " / Click, then type in chat");

            put(inv, 15, Material.GOLD_NUGGET,
                    "&6買取価格を変更"
                            + " / Change Purchase Price",
                    "&f現在 / Current: "
                            + plugin.money(
                            s.buyPrice),
                    "&7お客さんが売却する価格"
                            + " / Customer selling price",
                    "&7クリック後にチャット入力"
                            + " / Click, then type in chat");

            put(inv, 18, Material.ARROW,
                    "&b戻る / Back");
        } else {
            put(inv, 4, Material.BARRIER,
                    "&c本当に削除しますか？"
                            + " / Remove this shop?");

            put(inv, 11, Material.LIME_WOOL,
                    "&a削除する"
                            + " / Confirm removal");

            put(inv, 15, Material.RED_WOOL,
                    "&cキャンセル / Cancel");
        }

        p.openInventory(inv);
        if (page == Page.TRADE && p.getOpenInventory().getTopInventory() == inv) {
            tradeScreens.computeIfAbsent(s, ignored -> new HashSet<>()).add(holder);
        }
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!(e.getView()
                .getTopInventory()
                .getHolder()
                instanceof Screen screen)) {
            return;
        }

        e.setCancelled(true);

        if (!(e.getWhoClicked()
                instanceof Player p)
                || e.getClickedInventory()
                != e.getView().getTopInventory()) {
            return;
        }

        Shop s = screen.shop;

        if (plugin.isWorldDisabled(s)
                && screen.page == Page.TRADE) {
            p.closeInventory();
            plugin.msg(p,
                    "このワールドではショップを利用できません"
                            + " / Shops are disabled in this world.");
            return;
        }

        if (!plugin.all().contains(s)
                || plugin.inventory(s) == null) {
            p.closeInventory();
            return;
        }

        int slot = e.getRawSlot();
        sound(p);

        if (screen.page == Page.TRADE) {
            for (int i = 0;
                 i < tradeButtons.length;
                 i++) {
                if (slot != tradeButtons[i]) {
                    continue;
                }

                boolean buying =
                        steps[i] > 0;
                int amount =
                        Math.abs(steps[i]);

                String reason =
                        unavailableReason(
                                s, buying, amount, tradeState(p, s));

                if (reason != null) {
                    plugin.msg(p, reason);
                    p.playSound(
                            p.getLocation(),
                            Sound.ENTITY_VILLAGER_NO,
                            0.8f,
                            1.1f);
                    open(p, s);
                    return;
                }

                trade(p, s, buying, amount);
                return;
            }

            if (slot == 18 && manage(p, s)) {
                open(p, s, Page.SETTINGS);
            }

            if (slot == 26) {
                p.closeInventory();
            }

            return;
        }

        if (!manage(p, s)) {
            p.closeInventory();
            return;
        }

        if (screen.page == Page.SETTINGS) {
            if (slot == 11) {
                open(p, s, Page.PRICE_SELECT);
            }

            if (slot == 15 && !s.admin) {
                Container container =
                        plugin.inventory(s);

                if (container != null) {
                    p.openInventory(
                            container.getInventory());
                }
            }

            if (slot == 13) {
                open(p, s, Page.DELETE);
            }

            if (slot == 18) {
                if (plugin.isWorldDisabled(s)) {
                    p.closeInventory();
                } else {
                    open(p, s);
                }
            }
        } else if (screen.page
                == Page.PRICE_SELECT) {
            if (slot == 11 || slot == 15) {
                boolean sale =
                        slot == 11;

                Bukkit.getScheduler().runTask(
                        plugin, () -> {
                            if (!p.isOnline()
                                    || !plugin.all()
                                    .contains(s)) {
                                return;
                            }

                            p.closeInventory();

                            plugin.getServer()
                                    .getPluginManager()
                                    .callEvent(
                                            new PricePromptEvent(
                                                    p, s, sale));

                            plugin.msg(
                                    p,
                                    (sale
                                            ? "販売価格 / Sale Price"
                                            : "買取価格 / Purchase Price")
                                            + " — 新しい価格をチャットに入力"
                                            + "（0で無効、cancelで中止）"
                                            + " / Enter a new price"
                                            + " (0 disables,"
                                            + " cancel aborts).");
                        });
            }

            if (slot == 18) {
                open(p, s, Page.SETTINGS);
            }
        } else if (screen.page == Page.DELETE) {
            if (slot == 11) {
                plugin.remove(s);
                p.closeInventory();
                plugin.msg(
                        p,
                        "ショップを削除しました"
                                + " / Shop removed.");
            }

            if (slot == 15) {
                open(p, s, Page.SETTINGS);
            }
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent e) {
        if (e.getView()
                .getTopInventory()
                .getHolder()
                instanceof Screen) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void close(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Screen screen && screen.page == Page.TRADE) {
            Set<Screen> screens = tradeScreens.get(screen.shop);
            if (screens != null) {
                screens.remove(screen);
                if (screens.isEmpty()) tradeScreens.remove(screen.shop);
            }
        }
        if (e.getInventory().getHolder()
                instanceof Container container) {
            Shop shop =
                    plugin.get(
                            container.getBlock());

            if (shop != null) {
                changed(shop);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void stockClick(InventoryClickEvent event) {
        stockEdited(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void stockDrag(InventoryDragEvent event) {
        stockEdited(event.getView().getTopInventory());
    }

    private void stockEdited(Inventory inventory) {
        if (inventory.getHolder() instanceof Container container) {
            Shop shop = plugin.get(container.getBlock());
            if (shop != null) changed(shop);
        }
    }

    private int count(
            Inventory inv, ItemStack item) {
        int total = 0;

        for (ItemStack stack
                : inv.getStorageContents()) {
            if (stack != null
                    && stack.isSimilar(item)) {
                total += stack.getAmount();
            }
        }

        return total;
    }

    private int capacity(
            Inventory inv, ItemStack item) {
        int total = 0;

        for (ItemStack stack
                : inv.getStorageContents()) {
            if (stack == null
                    || stack.getType().isAir()) {
                total +=
                        item.getMaxStackSize();
            } else if (stack.isSimilar(item)) {
                total += Math.max(
                        0,
                        stack.getMaxStackSize()
                                - stack.getAmount());
            }
        }

        return total;
    }

    private void take(
            Inventory inv,
            ItemStack item,
            int amount) {
        ItemStack[] slots =
                inv.getStorageContents();

        for (int i = 0;
             i < slots.length
                     && amount > 0;
             i++) {
            ItemStack stack = slots[i];

            if (stack == null
                    || !stack.isSimilar(item)) {
                continue;
            }

            int used = Math.min(
                    amount,
                    stack.getAmount());

            amount -= used;

            if (stack.getAmount() == used) {
                slots[i] = null;
            } else {
                ItemStack reduced =
                        stack.clone();

                reduced.setAmount(
                        stack.getAmount() - used);
                slots[i] = reduced;
            }
        }

        inv.setStorageContents(slots);
    }

    private void give(
            Inventory inv,
            ItemStack item,
            int amount) {
        while (amount > 0) {
            int part = Math.min(
                    amount,
                    item.getMaxStackSize());

            ItemStack stack =
                    item.clone();

            stack.setAmount(part);

            if (!inv.addItem(stack)
                    .isEmpty()) {
                throw new IllegalStateException(
                        "Inventory changed"
                                + " during trade");
            }

            amount -= part;
        }
    }

    private ItemStack[] snapshot(
            Inventory inv) {
        ItemStack[] copy =
                inv.getStorageContents();

        for (int i = 0;
             i < copy.length;
             i++) {
            if (copy[i] != null) {
                copy[i] =
                        copy[i].clone();
            }
        }

        return copy;
    }

    private void trade(
            Player p,
            Shop s,
            boolean purchasing,
            int amount) {
        double unit =
                purchasing
                        ? s.sellPrice
                        : s.buyPrice;

        if (unit <= 0) {
            plugin.msg(
                    p,
                    "この取引は無効です"
                            + " / This trade is disabled.");
            return;
        }

        double total =
                unit * amount;

        if (!plugin.valid(total)
                || total <= 0) {
            plugin.msg(
                    p,
                    "金額が大きすぎます"
                            + " / Total is too large.");
            return;
        }

        Container container =
                plugin.inventory(s);

        if (container == null) {
            plugin.msg(
                    p,
                    "ショップが見つかりません"
                            + " / Shop container is missing.");
            return;
        }

        if (s.admin) {
            tradeAdmin(p, s, purchasing, amount, total);
            return;
        }

        Inventory stock =
                container.getInventory();
        Inventory player =
                p.getInventory();

        Inventory source =
                purchasing
                        ? stock : player;
        Inventory destination =
                purchasing
                        ? player : stock;

        boolean wasOutOfStock =
                count(stock, s.item) == 0;
        boolean wasFull =
                capacity(stock, s.item) == 0;

        if (count(source, s.item)
                < amount) {
            plugin.msg(
                    p,
                    "アイテムが足りません"
                            + " / Not enough items.");
            return;
        }

        if (capacity(
                destination, s.item)
                < amount) {
            plugin.msg(
                    p,
                    "空きがありません"
                            + " / No inventory space.");
            return;
        }

        org.bukkit.OfflinePlayer owner =
                Bukkit.getOfflinePlayer(
                        s.owner);

        org.bukkit.OfflinePlayer payer =
                purchasing ? p : owner;

        org.bukkit.OfflinePlayer payee =
                purchasing ? owner : p;

        if (plugin.economy()
                .getBalance(payer)
                < total) {
            plugin.msg(
                    p,
                    "残高が足りません"
                            + " / Insufficient balance.");
            return;
        }

        ItemStack[] beforeSource =
                snapshot(source);
        ItemStack[] beforeDestination =
                snapshot(destination);

        EconomyResponse debit =
                plugin.economy()
                        .withdrawPlayer(
                                payer, total);

        if (!debit.transactionSuccess()) {
            plugin.msg(
                    p,
                    "支払いに失敗しました"
                            + " / Payment failed.");
            return;
        }

        try {
            take(
                    source,
                    s.item,
                    amount);

            give(
                    destination,
                    s.item,
                    amount);

            EconomyResponse credit =
                    plugin.economy()
                            .depositPlayer(
                                    payee, total);

            if (!credit
                    .transactionSuccess()) {
                throw new IllegalStateException(
                        "Deposit failed: "
                                + credit.errorMessage);
            }
        } catch (Exception ex) {
            source.setStorageContents(
                    beforeSource);
            destination.setStorageContents(
                    beforeDestination);

            EconomyResponse refund =
                    plugin.economy()
                            .depositPlayer(
                                    payer, total);

            if (!refund
                    .transactionSuccess()) {
                plugin.getLogger().severe(
                        "REFUND FAILED for "
                                + p.getUniqueId()
                                + " amount "
                                + total);
            }

            plugin.msg(
                    p,
                    "取引に失敗しました"
                            + " / Trade failed.");

            plugin.getLogger().warning(
                    "Trade failed: "
                            + ex.getMessage());
            return;
        }

        plugin.msg(
                p,
                (purchasing
                        ? "購入しました / Purchased "
                        : "売却しました / Sold ")
                        + amount
                        + " × "
                        + plugin.itemName(s)
                        + " — "
                        + plugin.money(total));

        changed(s);

        p.playSound(
                p.getLocation(),
                Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                1.0f,
                purchasing ? 1.3f : 0.85f);

        plugin.notifyOwner(
                s.owner,
                (purchasing
                        ? "商品が購入されました"
                        + " / Sold to customer: "
                        : "商品を買い取りました"
                        + " / Bought from customer: ")
                        + amount
                        + " × "
                        + plugin.itemName(s)
                        + " — "
                        + (purchasing
                        ? "+" : "-")
                        + plugin.money(total)
                        + " ("
                        + p.getName()
                        + ") | "
                        + plugin.shopLocation(s));

        if (s.sellPrice > 0
                && !wasOutOfStock
                && count(stock, s.item) == 0) {
            plugin.notifyOwner(
                    s.owner,
                    "在庫切れ / Out of stock: "
                            + plugin.itemName(s)
                            + " | "
                            + plugin.shopLocation(s));
        }

        if (s.buyPrice > 0
                && !wasFull
                && capacity(
                stock, s.item) == 0) {
            plugin.notifyOwner(
                    s.owner,
                    "チェストが満杯"
                            + " / Shop container full: "
                            + plugin.itemName(s)
                            + " | "
                            + plugin.shopLocation(s));
        }

        // changed(s) refreshes all viewers next tick without reopening the GUI.
    }

    // Admin trades touch only the customer's items and balance.
    private void tradeAdmin(Player p, Shop s, boolean purchasing, int amount, double total) {
        Inventory inventory = p.getInventory();
        if (purchasing ? capacity(inventory, s.item) < amount : count(inventory, s.item) < amount) {
            plugin.msg(p, purchasing ? "空きがありません / No inventory space."
                    : "アイテムが足りません / Not enough items.");
            return;
        }
        if (purchasing && plugin.economy().getBalance(p) < total) {
            plugin.msg(p, "残高が足りません / Insufficient balance.");
            return;
        }
        ItemStack[] before = snapshot(inventory);
        try {
            if (purchasing) give(inventory, s.item, amount);
            else take(inventory, s.item, amount);
        } catch (RuntimeException ex) {
            inventory.setStorageContents(before);
            plugin.msg(p, "取引に失敗しました / Trade failed.");
            plugin.getLogger().warning("Admin shop inventory update failed: " + ex.getMessage());
            return;
        }
        EconomyResponse result = purchasing
                ? plugin.economy().withdrawPlayer(p, total)
                : plugin.economy().depositPlayer(p, total);
        if (!result.transactionSuccess()) {
            inventory.setStorageContents(before);
            plugin.msg(p, "支払いに失敗しました / Payment failed.");
            return;
        }
        plugin.msg(p, (purchasing ? "購入しました / Purchased " : "売却しました / Sold ")
                + amount + " × " + plugin.itemName(s) + " — " + plugin.money(total));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                1.0f, purchasing ? 1.3f : 0.85f);
        changed(s);
    }

    public static final class PricePromptEvent
            extends org.bukkit.event.Event {
        private static final
        org.bukkit.event.HandlerList HANDLERS =
                new org.bukkit.event.HandlerList();

        public final Player player;
        public final Shop shop;
        public final boolean sale;

        PricePromptEvent(
                Player player,
                Shop shop,
                boolean sale) {
            this.player = player;
            this.shop = shop;
            this.sale = sale;
        }

        @Override
        public org.bukkit.event.HandlerList
        getHandlers() {
            return HANDLERS;
        }

        public static
        org.bukkit.event.HandlerList
        getHandlerList() {
            return HANDLERS;
        }
    }

    public void reopenPrices(
            Player p, Shop s) {
        open(p, s, Page.PRICE_SELECT);
    }
}
