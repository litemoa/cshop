package net.n1e.litemoa.cshop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public final class Shop {
    public final UUID owner;
    public final UUID world;
    public final int x, y, z, sx, sy, sz;
    public final ItemStack item;
    public double sellPrice, buyPrice;
    public boolean admin;

    public Shop(UUID owner, Location chest, Location sign, ItemStack item,
                double sellPrice, double buyPrice) {
        this.owner = owner;
        this.world = chest.getWorld().getUID();
        this.x = chest.getBlockX();
        this.y = chest.getBlockY();
        this.z = chest.getBlockZ();
        this.sx = sign.getBlockX();
        this.sy = sign.getBlockY();
        this.sz = sign.getBlockZ();
        this.item = item.clone();
        this.item.setAmount(1);
        this.sellPrice = sellPrice;
        this.buyPrice = buyPrice;
    }

    public static Shop stored(UUID owner, UUID world,
                              int x, int y, int z, int sx, int sy, int sz,
                              ItemStack item, double sellPrice, double buyPrice) {
        return new Shop(owner, world, x, y, z, sx, sy, sz,
                item, sellPrice, buyPrice);
    }

    private Shop(UUID owner, UUID world,
                 int x, int y, int z, int sx, int sy, int sz,
                 ItemStack item, double sellPrice, double buyPrice) {
        this.owner = owner;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.item = item.clone();
        this.item.setAmount(1);
        this.sellPrice = sellPrice;
        this.buyPrice = buyPrice;
    }

    public Location block() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    public Location sign() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, sx, sy, sz);
    }

    public String key() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public String signKey() {
        return world + ":" + sx + ":" + sy + ":" + sz;
    }
}