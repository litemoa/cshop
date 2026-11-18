package net.n1e.litemoa.cshop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Cshop extends JavaPlugin {
    private final Map<String, Shop> byBlock = new HashMap<>();
    private final Map<String, Shop> bySign = new HashMap<>();
    private final Map<UUID, List<String>> pendingNotifications =
            new HashMap<>();

    private Economy economy;
    private File dataFile;
    private File notificationFile;
    private ShopDisplays displays;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getDataFolder().exists()
                && !getDataFolder().mkdirs()) {
            getLogger().severe(
                    "Cannot create plugin data directory: "
                            + getDataFolder());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RegisteredServiceProvider<Economy> reg =
                getServer().getServicesManager()
                        .getRegistration(Economy.class);

        if (reg == null || reg.getProvider() == null) {
            getLogger().severe(
                    "Vault economy provider is required.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = reg.getProvider();
        dataFile = new File(getDataFolder(), "shops.yml");
        notificationFile =
                new File(getDataFolder(), "notifications.yml");

        loadNotifications();
        loadShops();
        displays = new ShopDisplays(this);

        if (!dataFile.exists()) {
            saveShops();
        }

        Menus menus = new Menus(this);
        getServer().getPluginManager().registerEvents(
                new ShopListener(this, menus), this);
        getServer().getPluginManager().registerEvents(
                menus, this);

        org.bukkit.command.CommandExecutor creator = (sender, command, label, args) -> {
                    boolean admin = command.getName().equalsIgnoreCase("admincshop");
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(
                                "Players only / プレイヤー専用です");
                        return true;
                    }

                    if (!p.hasPermission(admin ? "chestshop.admin" : "chestshop.create")) {
                        msg(p, "権限がありません / No permission.");
                        return true;
                    }

                    if (isWorldDisabled(p.getWorld())) {
                        msg(p, "このワールドではショップを作成できません"
                                + " / Shops are disabled in this world.");
                        return true;
                    }

                    if (args.length != 2) {
                        msg(p, "使い方 / Usage: /" + label
                                + " <販売価格 / Buy>"
                                + " <買取価格 / Sell>");
                        return true;
                    }

                    double sell;
                    double buy;

                    try {
                        sell = Double.parseDouble(args[0]);
                        buy = Double.parseDouble(args[1]);
                    } catch (NumberFormatException ex) {
                        msg(p, "価格は数字で指定してください"
                                + " / Prices must be numbers.");
                        return true;
                    }

                    if (!valid(sell)
                            || !valid(buy)
                            || (sell == 0 && buy == 0)) {
                        msg(p, "0以上の価格を指定し、両方0にしないでください"
                                + " / Use non-negative prices;"
                                + " both cannot be 0.");
                        return true;
                    }

                    ItemStack held =
                            p.getInventory().getItemInMainHand();

                    if (held.getType().isAir()) {
                        msg(p, "商品を手に持ってください"
                                + " / Hold the shop item.");
                        return true;
                    }

                    RayTraceResult hit =
                            p.getWorld().rayTraceBlocks(
                                    p.getEyeLocation(),
                                    p.getEyeLocation().getDirection(),
                                    6,
                                    FluidCollisionMode.NEVER,
                                    true);

                    Block target =
                            hit == null ? null : hit.getHitBlock();

                    if (target == null || !container(target)) {
                        msg(p, "チェスト・樽・シュルカーボックスを"
                                + "見てください"
                                + " / Look at a chest, barrel"
                                + " or shulker box.");
                        return true;
                    }

                    BlockFace face = hit.getHitBlockFace();

                    if (face != BlockFace.UP
                            && face != BlockFace.NORTH
                            && face != BlockFace.SOUTH
                            && face != BlockFace.EAST
                            && face != BlockFace.WEST) {
                        msg(p, "側面か上面を見てください"
                                + " / Look at a side or the top face.");
                        return true;
                    }

                    if (target.getType() == Material.CHEST
                            && target.getState()
                            instanceof org.bukkit.block.Chest chest
                            && chest.getInventory().getHolder()
                            instanceof org.bukkit.block.DoubleChest) {
                        msg(p, "ラージチェストは使用できません"
                                + " / Double chests are not supported.");
                        return true;
                    }

                    if (get(target) != null) {
                        msg(p, "既にショップです"
                                + " / Shop already exists.");
                        return true;
                    }

                    long count = byBlock.values().stream()
                            .filter(s -> !s.admin && s.owner.equals(
                                    p.getUniqueId()))
                            .count();

                    int maxShops = getConfig().getInt("max-shops-per-player", 10);
                    if (!admin && !p.isOp() && maxShops != -1
                            && count >= maxShops) {
                        msg(p, "作成上限です / Shop limit reached.");
                        return true;
                    }

                    Block signBlock = target.getRelative(face);

                    if (!signBlock.getType().isAir()) {
                        msg(p, "見ている面に看板を置く空間がありません"
                                + " / No room on the face"
                                + " you are looking at.");
                        return true;
                    }

                    if (face == BlockFace.UP) {
                        signBlock.setType(
                                Material.OAK_SIGN, false);

                        Rotatable data =
                                (Rotatable) signBlock.getBlockData();

                        data.setRotation(
                                p.getFacing().getOppositeFace());
                        signBlock.setBlockData(data, false);
                    } else {
                        signBlock.setType(
                                Material.OAK_WALL_SIGN, false);

                        Directional data =
                                (Directional) signBlock.getBlockData();

                        data.setFacing(face);
                        signBlock.setBlockData(data, false);
                    }

                    Shop shop = new Shop(
                            p.getUniqueId(),
                            target.getLocation(),
                            signBlock.getLocation(),
                            held,
                            sell,
                            buy);

                    shop.admin = admin;
                    byBlock.put(shop.key(), shop);
                    bySign.put(shop.signKey(), shop);

                    updateSign(shop);
                    saveShops();

                    msg(p, "ショップを作成しました"
                            + " / Shop created.");
                    return true;
                };
        Objects.requireNonNull(getCommand("chestshop")).setExecutor(creator);
        Objects.requireNonNull(getCommand("admincshop")).setExecutor(creator);
    }

    @Override
    public void onDisable() {
        if (displays != null) displays.close();
        saveNotifications();

        if (dataFile != null) {
            saveShops();
        }
    }

    public void notifyOwner(UUID owner, String message) {
        Player online = Bukkit.getPlayer(owner);

        if (online != null && online.isOnline()) {
            msg(online, message);
            notificationSound(online, isWarning(message));
            return;
        }

        pendingNotifications
                .computeIfAbsent(owner, id -> new ArrayList<>())
                .add(message);
        saveNotifications();
    }

    public void deliverNotifications(Player player) {
        if (!player.isOnline()) return;

        List<String> messages =
                pendingNotifications.get(player.getUniqueId());

        if (messages == null || messages.isEmpty()) return;

        for (String message : messages) {
            msg(player, message);
        }

        notificationSound(
                player,
                messages.stream().anyMatch(this::isWarning));

        pendingNotifications.remove(player.getUniqueId());
        saveNotifications();
    }

    private boolean isWarning(String message) {
        return message.startsWith("在庫切れ / ")
                || message.startsWith("チェストが満杯 / ");
    }

    private void notificationSound(
            Player player, boolean warning) {
        player.playSound(
                player.getLocation(),
                warning
                        ? Sound.BLOCK_NOTE_BLOCK_BELL
                        : Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f,
                warning ? 0.7f : 1.25f);
    }

    public String shopLocation(Shop shop) {
        Location location = shop.block();

        if (location == null) {
            return "World UUID: " + shop.world
                    + " (" + shop.x + ", "
                    + shop.y + ", " + shop.z + ")";
        }

        return location.getWorld().getName()
                + " ["
                + location.getWorld().getEnvironment().name()
                + "] ("
                + shop.x + ", "
                + shop.y + ", "
                + shop.z + ")";
    }

    private void loadNotifications() {
        if (!notificationFile.exists()) return;

        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(
                        notificationFile);

        ConfigurationSection root =
                yaml.getConfigurationSection("pending");

        if (root == null) return;

        for (String key : root.getKeys(false)) {
            try {
                List<String> messages =
                        root.getStringList(key);

                if (!messages.isEmpty()) {
                    pendingNotifications.put(
                            UUID.fromString(key),
                            new ArrayList<>(messages));
                }
            } catch (IllegalArgumentException ex) {
                getLogger().warning(
                        "Invalid pending notification owner: "
                                + key);
            }
        }
    }

    private void saveNotifications() {
        if (notificationFile == null) return;

        YamlConfiguration yaml =
                new YamlConfiguration();

        for (Map.Entry<UUID, List<String>> entry
                : pendingNotifications.entrySet()) {
            yaml.set(
                    "pending." + entry.getKey(),
                    entry.getValue());
        }

        try {
            yaml.save(notificationFile);
        } catch (IOException ex) {
            getLogger().severe(
                    "Cannot save notifications.yml: " + ex);
        }
    }

    public boolean canManage(Player p, Shop s) {
        return s.admin ? p.hasPermission("chestshop.admin")
                : p.isOp() || p.getUniqueId().equals(s.owner);
    }

    public String stockLabel(Shop s) {
        return s.admin ? "無制限 / Unlimited" : Integer.toString(stockCount(s));
    }

    public Economy economy() {
        return economy;
    }

    public Collection<Shop> all() {
        return byBlock.values();
    }

    public boolean isWorldDisabled(World world) {
        if (world == null) return false;

        return getConfig().getStringList("disabled-worlds")
                .stream()
                .anyMatch(name ->
                        name.trim().equalsIgnoreCase(
                                world.getName()));
    }

    public boolean isWorldDisabled(Shop shop) {
        return isWorldDisabled(Bukkit.getWorld(shop.world));
    }

    public boolean valid(double n) {
        return Double.isFinite(n)
                && n >= 0
                && n <= 1_000_000_000_000d;
    }

    public String money(double n) {
        DecimalFormat formatter = new DecimalFormat(
                "#,##0.##",
                DecimalFormatSymbols.getInstance(Locale.US));

        return getConfig().getString(
                "currency-symbol", "$")
                + formatter.format(n);
    }

    public void msg(Player p, String message) {
        p.sendMessage(
                ChatColor.GOLD + "[ChestShop] "
                        + ChatColor.RESET + message);
    }

    public String key(Block block) {
        return block.getWorld().getUID()
                + ":" + block.getX()
                + ":" + block.getY()
                + ":" + block.getZ();
    }

    public Shop get(Block block) {
        return byBlock.get(key(block));
    }

    public Shop getSign(Block block) {
        return bySign.get(key(block));
    }

    public boolean container(Block block) {
        return block.getState() instanceof Container
                && (block.getType() == Material.CHEST
                || block.getType() == Material.BARREL
                || block.getType().name()
                .endsWith("SHULKER_BOX"));
    }

    public Container inventory(Shop shop) {
        Location loc = shop.block();

        return loc != null
                && loc.getBlock().getState()
                instanceof Container container
                ? container : null;
    }

    public String itemName(Shop shop) {
        if (shop.item.hasItemMeta()
                && shop.item.getItemMeta()
                .hasDisplayName()) {
            return ChatColor.stripColor(
                    shop.item.getItemMeta()
                            .getDisplayName());
        }

        String raw = shop.item.getType().name()
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ');

        StringBuilder out = new StringBuilder();

        for (String part : raw.split(" ")) {
            out.append(Character.toUpperCase(
                    part.charAt(0)));
            out.append(part.substring(1));
            out.append(' ');
        }

        return out.toString().trim();
    }

    public int stockCount(Shop shop) {
        Container container = inventory(shop);

        if (container == null) {
            return 0;
        }

        int total = 0;

        for (ItemStack stack
                : container.getInventory()
                .getStorageContents()) {
            if (stack != null
                    && stack.isSimilar(shop.item)) {
                total += stack.getAmount();
            }
        }

        return total;
    }

    /** Restore only an empty, loaded sign location belonging to an existing container. */
    public void restoreMissingSign(Shop shop) {
        World world = Bukkit.getWorld(shop.world);
        if (world == null
                || !world.isChunkLoaded(shop.x >> 4, shop.z >> 4)
                || !world.isChunkLoaded(shop.sx >> 4, shop.sz >> 4)
                || shop.sy < world.getMinHeight() || shop.sy >= world.getMaxHeight()) return;

        Block chest = world.getBlockAt(shop.x, shop.y, shop.z);
        Block target = world.getBlockAt(shop.sx, shop.sy, shop.sz);
        if (!container(chest) || !target.getType().isAir()) return;

        int dx = shop.sx - shop.x;
        int dy = shop.sy - shop.y;
        int dz = shop.sz - shop.z;
        org.bukkit.block.data.BlockData data;
        if (dx == 0 && dy == 1 && dz == 0) {
            Rotatable standing = (Rotatable) Material.OAK_SIGN.createBlockData();
            // Old shop data does not store the original standing-sign rotation.
            standing.setRotation(BlockFace.SOUTH);
            data = standing;
        } else if (dy == 0 && Math.abs(dx) + Math.abs(dz) == 1) {
            BlockFace face = dx == 1 ? BlockFace.EAST : dx == -1 ? BlockFace.WEST
                    : dz == 1 ? BlockFace.SOUTH : BlockFace.NORTH;
            Directional wall = (Directional) Material.OAK_WALL_SIGN.createBlockData();
            wall.setFacing(face);
            data = wall;
        } else {
            return;
        }
        if (!data.isSupported(target)) return;
        target.setBlockData(data, false);
        updateSign(shop);
    }

    public void updateSign(Shop shop) {
        if (displays != null) displays.refresh(shop);
        Location loc = shop.sign();

        if (loc == null
                || !(loc.getBlock().getState()
                instanceof Sign sign)) {
            return;
        }

        String name = itemName(shop);
        int count = shop.admin ? 0 : stockCount(shop);
        String stock = shop.admin ? " x∞" : " x" + count;
        int maxName =
                Math.max(3, 18 - stock.length());

        if (name.length() > maxName) {
            name = name.substring(
                    0, maxName - 1) + "…";
        }

        String owner = Bukkit.getOfflinePlayer(shop.owner).getName();
        sign.setLine(0, shop.admin
                ? ChatColor.LIGHT_PURPLE + "[管理者ショップ]"
                : ChatColor.AQUA + (owner == null ? "オーナー不明" : owner));
        sign.setLine(1, ChatColor.GOLD + name
                + (shop.admin || count > 0 ? ChatColor.WHITE : ChatColor.RED) + stock);

        sign.setLine(
                2,
                shop.sellPrice == 0
                        ? ChatColor.DARK_GRAY + "Buy: OFF"
                        : ChatColor.GREEN + "Buy: " + money(shop.sellPrice));

        sign.setLine(
                3,
                shop.buyPrice == 0
                        ? ChatColor.DARK_GRAY + "Sell: OFF"
                        : ChatColor.RED + "Sell: " + money(shop.buyPrice));

        sign.setGlowingText(false);
        sign.setWaxed(true);
        sign.update(true, false);
    }

    public void remove(Shop shop) {
        if (displays != null) displays.remove(shop.key());
        byBlock.remove(shop.key());
        bySign.remove(shop.signKey());

        Location sign = shop.sign();

        if (sign != null
                && sign.getBlock().getState()
                instanceof Sign) {
            sign.getBlock().setType(Material.AIR);
        }

        saveShops();
    }

    private void loadShops() {
        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection root =
                yaml.getConfigurationSection("shops");

        if (root == null) {
            return;
        }

        for (String id : root.getKeys(false)) {
            try {
                ConfigurationSection v =
                        root.getConfigurationSection(id);

                if (v == null) {
                    continue;
                }

                UUID worldId = UUID.fromString(
                        Objects.requireNonNull(
                                v.getString("world")));

                ItemStack item =
                        v.getItemStack("item");

                if (item == null) {
                    continue;
                }

                Shop shop = Shop.stored(
                        UUID.fromString(
                                Objects.requireNonNull(
                                        v.getString("owner"))),
                        worldId,
                        v.getInt("x"),
                        v.getInt("y"),
                        v.getInt("z"),
                        v.getInt("sx"),
                        v.getInt("sy"),
                        v.getInt("sz"),
                        item,
                        v.getDouble("sell"),
                        v.getDouble("buy"));

                shop.admin = v.getBoolean("admin", false);
                byBlock.put(shop.key(), shop);
                bySign.put(shop.signKey(), shop);
            } catch (Exception ex) {
                getLogger().warning(
                        "Skipped invalid shop "
                                + id + ": "
                                + ex.getMessage());
            }
        }
    }

    public void saveShops() {
        if (dataFile == null) {
            return;
        }

        YamlConfiguration yaml =
                new YamlConfiguration();

        int i = 0;

        for (Shop shop : byBlock.values()) {
            String path =
                    "shops." + i++ + ".";

            yaml.set(path + "admin", shop.admin);
            yaml.set(path + "owner",
                    shop.owner.toString());
            yaml.set(path + "world",
                    shop.world.toString());
            yaml.set(path + "x", shop.x);
            yaml.set(path + "y", shop.y);
            yaml.set(path + "z", shop.z);
            yaml.set(path + "sx", shop.sx);
            yaml.set(path + "sy", shop.sy);
            yaml.set(path + "sz", shop.sz);
            yaml.set(path + "item", shop.item);
            yaml.set(path + "sell",
                    shop.sellPrice);
            yaml.set(path + "buy",
                    shop.buyPrice);
        }

        try {
            yaml.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe(
                    "Cannot save shops.yml: " + ex);
        }
    }
}
