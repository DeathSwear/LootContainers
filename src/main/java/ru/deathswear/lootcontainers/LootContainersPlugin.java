package ru.deathswear.lootcontainers;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LootContainersPlugin extends JavaPlugin implements Listener {
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("(?:skill|s)=([a-zA-Z0-9_.-]+)");

    private NamespacedKey idKey;
    private int expiryTask = -1;
    private final Map<UUID, Container> containers = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        idKey = new NamespacedKey(this, "container_id");
        getServer().getPluginManager().registerEvents(this, this);
        startExpiryTask();
    }

    @Override
    public void onDisable() {
        if (expiryTask != -1) {
            getServer().getScheduler().cancelTask(expiryTask);
            expiryTask = -1;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ContainerHolder) {
                player.closeInventory();
            }
        }
        // Автоматическое удаление всех активных сундуков и голограмм при остановке/перезагрузке
        for (Container container : containers.values()) {
            Block block = container.location.getBlock();
            if (block.getType() == Material.PLAYER_HEAD) {
                block.setType(Material.AIR, false);
            }
            for (UUID entityId : container.hologramIds) {
                Entity entity = getServer().getEntity(entityId);
                if (entity != null) entity.remove();
            }
        }
        containers.clear();
    }

    private void startExpiryTask() {
        if (expiryTask != -1) getServer().getScheduler().cancelTask(expiryTask);
        long period = 20L;
        expiryTask = getServer().getScheduler().runTaskTimer(this, this::expireContainers, period, period).getTaskId();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lootcontainers")) return false;
        if (args.length != 1) {
            sender.sendMessage(color("&eИспользование: /lootcontainers <reload|restart|clear>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("restart") || args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            startExpiryTask();
            sender.sendMessage(color("&aКонфигурация LootContainers перезагружена."));
            return true;
        }

        if (args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("purge")) {
            int count = containers.size();
            for (Container container : new ArrayList<>(containers.values())) {
                removeContainer(container, "expire", null);
            }
            sender.sendMessage(color("&aУдалено активных лут-контейнеров: " + count));
            return true;
        }

        sender.sendMessage(color("&eИспользование: /lootcontainers <reload|restart|clear>"));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMythicDeath(MythicMobDeathEvent event) {
        processDeath(event.getEntity(), event.getDrops(), event.getKiller());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaDeath(EntityDeathEvent event) {
        if (MythicBukkit.inst().getAPIHelper().isMythicMob(event.getEntity())) return;
        processDeath(event.getEntity(), event.getDrops(), event.getEntity().getKiller());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.PLAYER_HEAD) return;
        Container container = getContainerAt(event.getBlock().getLocation());

        if (container != null) {
            // Администратор в креативе может вручную удалить сундук
            if (event.getPlayer().getGameMode() == GameMode.CREATIVE && event.getPlayer().hasPermission("lootcontainers.admin")) {
                removeContainer(container, "expire", event.getPlayer());
                event.setDropItems(false);
                event.getPlayer().sendMessage(color("&eЛут-контейнер удален администратором."));
                return;
            }
            // Обычным игрокам запрещено ломать активный сундук (открывается по ПКМ)
            event.setCancelled(true);
            return;
        }

        // Если блока нет в памяти, но в PDC остался ключ от прошлой сессии/краша — позволяем сломать начисто
        if (event.getBlock().getState() instanceof Skull skull
                && skull.getPersistentDataContainer().has(idKey, PersistentDataType.STRING)) {
            event.setDropItems(false);
            event.getBlock().setType(Material.AIR, false);
            event.getPlayer().sendMessage(color("&7Устаревший лут-контейнер был удален."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isContainerAt(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isContainerAt(block.getLocation()));
    }

    private void processDeath(Entity entity, List<ItemStack> eventDrops, Entity killerEntity) {
        if (eventDrops == null || eventDrops.isEmpty()) return;
        List<ItemStack> drops = compactItemList(eventDrops);
        if (drops.isEmpty()) return;

        Player killer = killerEntity instanceof Player player ? player : null;
        UUID killerId = killer == null ? null : killer.getUniqueId();
        ActiveMob activeMob = MythicBukkit.inst().getAPIHelper().getMythicMobInstance(entity);

        String profileId = null;
        if (activeMob != null && activeMob.getVariables().has("LootContainer")) {
            profileId = activeMob.getVariables().getString("LootContainer");
        }
        if (profileId == null || profileId.isBlank()) {
            for (String tag : entity.getScoreboardTags()) {
                String lower = tag.toLowerCase(Locale.ROOT);
                if (lower.startsWith("lootcontainer:") || lower.startsWith("lc:")) {
                    profileId = tag.substring(tag.indexOf(':') + 1).trim();
                    break;
                }
            }
        }
        if ((profileId == null || profileId.isBlank()) && activeMob != null) {
            String mobType = activeMob.getType().getInternalName();
            if (getConfig().isConfigurationSection("profiles." + mobType)) {
                profileId = mobType;
            }
        }
        if (profileId == null || profileId.isBlank() || !getConfig().isConfigurationSection("profiles." + profileId)) {
            profileId = getConfig().getString("container.default-profile", "normal");
        }

        String profilePath = "profiles." + profileId;
        if (!getConfig().isConfigurationSection(profilePath)) {
            profilePath = "profiles.normal";
        }

        List<ItemStack> remainingDrops = drops;
        double mergeRadius = getConfig().getDouble("container.merge-radius", 5.0);

        // Попытка объединить дроп с уже существующим контейнером неподалеку
        if (mergeRadius >= 0) {
            Set<UUID> attempted = new HashSet<>();
            while (!remainingDrops.isEmpty()) {
                Container nearby = findNearbyMergeableContainer(entity.getLocation(), profilePath, killerId, mergeRadius, attempted);
                if (nearby == null) break;
                attempted.add(nearby.id);

                int beforeTotal = remainingDrops.stream().mapToInt(ItemStack::getAmount).sum();
                remainingDrops = addItemsToContainer(nearby, remainingDrops);
                int afterTotal = remainingDrops.stream().mapToInt(ItemStack::getAmount).sum();

                if (afterTotal < beforeTotal) {
                    nearby.createdAt = System.currentTimeMillis();
                    runSkills("merge", nearby, killer);
                }
            }
        }

        // Если весь лут поместился в существующие сундуки, новый сундук и голограмму не создаем
        if (remainingDrops.isEmpty()) {
            eventDrops.clear();
            return;
        }

        // Ищем свободное место на твердом полу
        Block block = findFreeBlock(entity.getLocation());
        if (block == null) {
            block = entity.getLocation().getBlock();
        }
        Location location = block.getLocation();

        Player lookTarget = killer;
        if (lookTarget == null) {
            lookTarget = entity.getWorld().getNearbyPlayers(location, 16).stream()
                    .min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(location)))
                    .orElse(null);
        }

        block.setType(Material.PLAYER_HEAD, false);
        if (lookTarget != null && getConfig().getBoolean("container.face-killer", true)) {
            BlockFace facing = horizontalFace(location, lookTarget.getLocation());
            applyFacing(block, facing);
            location.setDirection(facing.getDirection());
        }

        UUID id = UUID.randomUUID();
        Container container = new Container(id, remainingDrops, killerId, System.currentTimeMillis(), location, profilePath);
        containers.put(id, container);
        eventDrops.clear();

        Skull skull = (Skull) block.getState();
        skull.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        applyTexture(skull, profilePath);
        skull.update(true, false);

        spawnHologram(container);
        runSkills("spawn", container, killer);
    }

    private Container findNearbyMergeableContainer(Location loc, String profilePath, UUID killerId, double maxRadius, Set<UUID> exclude) {
        double maxDistSq = maxRadius * maxRadius;
        Container best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Container c : containers.values()) {
            if (exclude != null && exclude.contains(c.id)) continue;
            if (!c.location.getWorld().equals(loc.getWorld())) continue;
            if (!c.profilePath.equals(profilePath)) continue;

            long killerSecs = getConfig().getLong("access.killer-only-seconds", 10) * 1000L;
            boolean protectionExpired = (System.currentTimeMillis() - c.createdAt) >= killerSecs;
            boolean sameKiller = Objects.equals(c.killer, killerId);
            if (!sameKiller && !protectionExpired && c.killer != null) {
                continue;
            }

            Block b = c.location.getBlock();
            if (b.getType() != Material.PLAYER_HEAD) continue;

            if (!hasRoom(c)) continue;

            double distSq = c.location.distanceSquared(loc);
            if (distSq <= maxDistSq && distSq < bestDistSq) {
                best = c;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private boolean hasRoom(Container c) {
        if (c.items.size() < inventorySize(c)) return true;
        for (ItemStack is : c.items) {
            if (is != null && is.getAmount() < is.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private List<ItemStack> addItemsToContainer(Container container, List<ItemStack> drops) {
        int maxSlots = inventorySize(container);
        ItemStack[] dropArray = drops.stream().map(ItemStack::clone).toArray(ItemStack[]::new);

        if (container.activeHolder != null && container.activeHolder.inventory != null && !container.activeHolder.inventory.getViewers().isEmpty()) {
            Inventory openInv = container.activeHolder.inventory;
            HashMap<Integer, ItemStack> notFit = openInv.addItem(dropArray);
            container.items.clear();
            for (ItemStack is : openInv.getContents()) {
                if (is != null && !is.getType().isAir()) {
                    container.items.add(is.clone());
                }
            }
            return new ArrayList<>(notFit.values());
        }

        Inventory sim = getServer().createInventory(null, maxSlots);
        for (int i = 0; i < Math.min(container.items.size(), maxSlots); i++) {
            sim.setItem(i, container.items.get(i).clone());
        }
        HashMap<Integer, ItemStack> notFit = sim.addItem(dropArray);
        container.items.clear();
        for (ItemStack is : sim.getContents()) {
            if (is != null && !is.getType().isAir()) {
                container.items.add(is.clone());
            }
        }
        return new ArrayList<>(notFit.values());
    }

    private List<ItemStack> compactItemList(List<ItemStack> raw) {
        List<ItemStack> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;

        Inventory temp = getServer().createInventory(null, 54);
        for (ItemStack item : raw) {
            if (item != null && !item.getType().isAir()) {
                temp.addItem(item.clone());
            }
        }
        for (ItemStack item : temp.getContents()) {
            if (item != null && !item.getType().isAir()) {
                result.add(item.clone());
            }
        }
        return result;
    }

    private Block findFreeBlock(Location origin) {
        Block center = origin.getBlock();

        if (canPlaceContainerAt(center) && isSolidGround(center.getRelative(BlockFace.DOWN))) {
            return center;
        }

        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int dy = 0; dy >= -2; dy--) {
                        Block b = center.getRelative(dx, dy, dz);
                        if (canPlaceContainerAt(b) && isSolidGround(b.getRelative(BlockFace.DOWN))) {
                            return b;
                        }
                    }
                    Block up = center.getRelative(dx, 1, dz);
                    if (canPlaceContainerAt(up) && isSolidGround(up.getRelative(BlockFace.DOWN))) {
                        return up;
                    }
                }
            }
        }

        for (int dy = 1; dy <= 3; dy++) {
            Block b = center.getRelative(0, dy, 0);
            if (canPlaceContainerAt(b)) {
                return b;
            }
        }

        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = center.getRelative(dx, 0, dz);
                    if (canPlaceContainerAt(b)) return b;
                }
            }
        }

        return null;
    }

    private boolean canPlaceContainerAt(Block block) {
        if (block == null) return false;
        if (isContainerAt(block.getLocation())) return false;
        Material type = block.getType();
        if (type.isAir()) return true;
        return block.isPassable() && !block.isLiquid() && type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD;
    }

    private boolean isSolidGround(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        return mat.isSolid() && mat != Material.PLAYER_HEAD && mat != Material.PLAYER_WALL_HEAD;
    }

    private boolean isContainerAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        for (Container c : containers.values()) {
            if (c.location.getWorld().equals(loc.getWorld())
                    && c.location.getBlockX() == loc.getBlockX()
                    && c.location.getBlockY() == loc.getBlockY()
                    && c.location.getBlockZ() == loc.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    private Container getContainerAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        for (Container c : containers.values()) {
            if (c.location.getWorld().equals(loc.getWorld())
                    && c.location.getBlockX() == loc.getBlockX()
                    && c.location.getBlockY() == loc.getBlockY()
                    && c.location.getBlockZ() == loc.getBlockZ()) {
                return c;
            }
        }
        return null;
    }

    @EventHandler
    public void onHeadInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.PLAYER_HEAD) return;
        if (!(event.getClickedBlock().getState() instanceof Skull skull)) return;
        String raw = skull.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (raw == null) return;

        Container container = null;
        try { container = containers.get(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) {}
        if (container == null) {
            container = getContainerAt(event.getClickedBlock().getLocation());
        }

        // Если сундук имеет наш ключ, но отсутствует в памяти (остался с прошлой сессии/краша) — автоудаление
        if (container == null) {
            event.setCancelled(true);
            event.getClickedBlock().setType(Material.AIR, false);
            event.getPlayer().sendMessage(color("&7Устаревший лут-контейнер был автоматически удален."));
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (container.killer != null && !container.killer.equals(player.getUniqueId())
                && System.currentTimeMillis() - container.createdAt < getConfig().getLong("access.killer-only-seconds", 10) * 1000L) {
            player.sendMessage(color("&cДоступ к сундуку пока есть только у убийцы."));
            return;
        }

        ContainerHolder holder = new ContainerHolder(container);
        Inventory inventory = getServer().createInventory(holder, inventorySize(container), color(getConfig().getString(container.profilePath + ".name", "&6Сундук добычи")));
        holder.inventory = inventory;
        container.activeHolder = holder;

        for (int i = 0; i < Math.min(container.items.size(), inventory.getSize()); i++) {
            inventory.setItem(i, container.items.get(i));
        }

        runSkills("open", container, player);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ContainerHolder holder)) return;
        Container container = holder.container;
        container.items.clear();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                container.items.add(item.clone());
            }
        }
        if (container.items.isEmpty()) {
            removeContainer(container, "expire", event.getPlayer() instanceof Player p ? p : null);
        } else {
            if (container.activeHolder == holder) {
                container.activeHolder = null;
            }
        }
    }

    private int inventorySize(Container container) {
        int configured = getConfig().getInt("container.size", 27);
        return Math.max(9, Math.min(54, ((configured + 8) / 9) * 9));
    }

    private void spawnHologram(Container container) {
        List<String> lines = getConfig().getStringList(container.profilePath + ".hologram");
        if (lines.isEmpty()) return;
        Location base = container.location.clone().add(0.5, 1.15, 0.5);
        double spacing = getConfig().getDouble("hologram.line-spacing", 0.25);
        for (int i = 0; i < lines.size(); i++) {
            Location at = base.clone().add(0, (lines.size() - 1 - i) * spacing, 0);
            String line = lines.get(i);
            ArmorStand stand = container.location.getWorld().spawn(at, ArmorStand.class, as -> {
                as.setInvisible(true);
                as.setMarker(true);
                as.setGravity(false);
                as.setInvulnerable(true);
                as.setCustomName(color(line));
                as.setCustomNameVisible(true);
                as.setPersistent(false);
            });
            container.hologramIds.add(stand.getUniqueId());
        }
    }

    private void runSkills(String event, Container container, Player trigger) {
        List<String> skills;
        String profileEventPath = container.profilePath + ".events." + event;
        if (getConfig().isList(profileEventPath)) {
            skills = getConfig().getStringList(profileEventPath);
        } else {
            skills = getConfig().getStringList("events." + event);
        }
        if (skills == null || skills.isEmpty()) return;

        Location chestLoc = container.location.clone().add(0.5, 0.0, 0.5);
        List<Entity> entityTargets = trigger != null ? List.of(trigger) : List.of();
        List<Location> locationTargets = List.of(chestLoc);

        for (String skill : skills) {
            String name = parseSkillName(skill);
            if (name.isBlank()) continue;

            try {
                boolean cast = MythicBukkit.inst().getAPIHelper().castSkill(
                        null,
                        name,
                        trigger,
                        chestLoc,
                        entityTargets,
                        locationTargets,
                        1.0f
                );
                if (!cast) {
                    getLogger().warning("MythicMobs skill not found or could not be cast: " + name + " (event " + event + ")");
                }
            } catch (Exception e) {
                getLogger().warning("Error executing MythicMobs skill '" + name + "': " + e.getMessage());
            }
        }
    }

    private String parseSkillName(String skill) {
        if (skill == null) return "";
        String name = skill.trim();
        if (name.contains("{") && name.endsWith("}")) {
            Matcher matcher = SKILL_NAME_PATTERN.matcher(name);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return name;
    }

    private void applyTexture(Skull skull, String profilePath) {
        String encoded = getConfig().getString(profilePath + ".texture", "").trim();
        if (encoded.isEmpty()) return;
        try {
            String json;
            if (encoded.startsWith("http://") || encoded.startsWith("https://")) {
                json = encoded;
            } else {
                json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            }
            String url = json;
            if (!json.startsWith("http://") && !json.startsWith("https://")) {
                url = json.replaceAll("(?s).*?\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
            }
            if (url.equals(json)) return;
            PlayerProfile profile = getServer().createPlayerProfile(UUID.randomUUID(), "LootChest");
            profile.getTextures().setSkin(URI.create(url).toURL());
            skull.setOwnerProfile(profile);
        } catch (Exception exception) {
            getLogger().warning("Invalid texture for " + profilePath + ".texture: " + exception.getMessage());
        }
    }

    private void expireContainers() {
        long lifetime = Math.max(1, getConfig().getLong("container.lifetime-seconds", 300)) * 1000L;
        long now = System.currentTimeMillis();
        for (Container container : new ArrayList<>(containers.values())) {
            // Не удалять сундук прямо во время взаимодействия игрока с инвентарем
            if (container.activeHolder != null && container.activeHolder.inventory != null && !container.activeHolder.inventory.getViewers().isEmpty()) {
                continue;
            }
            if (now - container.createdAt >= lifetime) {
                removeContainer(container, "expire", null);
            }
        }
    }

    private void removeContainer(Container container, String event, Player trigger) {
        if (!containers.containsKey(container.id)) return;
        containers.remove(container.id);

        runSkills(event, container, trigger);

        if (container.location.getBlock().getType() == Material.PLAYER_HEAD) {
            Skull skull = (Skull) container.location.getBlock().getState();
            String id = skull.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (container.id.toString().equals(id)) {
                container.location.getBlock().setType(Material.AIR, false);
            }
        }
        for (UUID entityId : container.hologramIds) {
            Entity entity = getServer().getEntity(entityId);
            if (entity != null) entity.remove();
        }
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private BlockFace horizontalFace(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /**
     * Поворачивает контейнер так, чтобы он "смотрел" в сторону {@code facing}.
     * Стоящая на блоке голова (PLAYER_HEAD) реализует {@link Rotatable} (свойство 'rotation'),
     * а не {@link Directional}, поэтому поворот нужно задавать именно через него.
     */
    private void applyFacing(Block block, BlockFace facing) {
        BlockData data = block.getBlockData();
        if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(facing.getOppositeFace());
            block.setBlockData(data, false);
        } else if (data instanceof Directional directional) {
            directional.setFacing(facing);
            block.setBlockData(data, false);
        }
    }

    private static final class Container {
        private final UUID id;
        private final List<ItemStack> items = new ArrayList<>();
        private final UUID killer;
        private long createdAt;
        private final Location location;
        private final String profilePath;
        private final List<UUID> hologramIds = new ArrayList<>();
        private ContainerHolder activeHolder;

        private Container(UUID id, List<ItemStack> items, UUID killer, long createdAt, Location location, String profilePath) {
            this.id = id;
            items.forEach(item -> this.items.add(item.clone()));
            this.killer = killer;
            this.createdAt = createdAt;
            this.location = location;
            this.profilePath = profilePath;
        }
    }

    private static final class ContainerHolder implements InventoryHolder {
        private final Container container;
        private Inventory inventory;

        private ContainerHolder(Container container) {
            this.container = container;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
