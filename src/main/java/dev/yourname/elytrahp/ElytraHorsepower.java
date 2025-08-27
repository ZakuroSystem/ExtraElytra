package dev.yourname.elytrahp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ElytraHorsepower extends JavaPlugin implements Listener {
    private NamespacedKey HP_KEY;
    private NamespacedKey FUEL_KEY;
    private NamespacedKey FUEL_CAP_KEY;
    private NamespacedKey LIFE_TOTAL_KEY;
    private NamespacedKey LIFE_USED_KEY;
    private NamespacedKey LIFE_REPAIRED_KEY;

    // --- Defaults (config overrideable) ---
    private static final double DEFAULT_BASE_MASS_KG = 70.0;
    private static final double DEFAULT_WATT_PER_HP = 745.699872;
    private static final double DEFAULT_MIN_SPEED_MPS = 0.5;
    private static final double DEFAULT_MAX_ACCEL_MPS2 = 50.0;

    private static final double DEFAULT_SEA_LEVEL_Y = 64.0;
    private static final double DEFAULT_CDA_BASE_M2 = 0.70;
    private static final double DEFAULT_DRAG_MULTIPLIER = 1.0;

    // --- Vanilla damping neutralizer defaults ---
    private static final boolean DEFAULT_NEUTRALIZE_VANILLA_DRAG = true;
    private static final double  DEFAULT_VANILLA_AIR_DAMP = 0.98;     // airborne
    private static final double  DEFAULT_VANILLA_ELYTRA_DAMP = 0.99;  // while gliding

    // g-force params
    private static final double DEFAULT_GFORCE_SAMPLE_SECONDS = 0.2;
    private static final boolean DEFAULT_GFORCE_KILL_CREATIVE = false;
    private static final double DEFAULT_GFORCE_DAMAGE_START_G = 3.0;
    private static final boolean DEFAULT_GFORCE_WARN_ENABLED = true;
    private static final double DEFAULT_GFORCE_WARN_THRESHOLD_G = 2.8;
    private static final double DEFAULT_GFORCE_WARN_INTERVAL_SEC = 0.6;
    private static final String DEFAULT_GFORCE_WARN_SOUND = "minecraft:block.note_block.snare";
    private static final String DEFAULT_GFORCE_WARN_ACTIONBAR = "警告: 加速度 {g} g";

    // Physics constants
    private static final double G = 9.80665;
    private static final double T0 = 288.15;
    private static final double L = 0.0065;
    private static final double P0 = 101325.0;
    private static final double R = 287.05;

    private static final double DT = 1.0 / 20.0;

    // Effective values from config
    private double BASE_MASS_KG;
    private double WATT_PER_HP;
    private double MIN_SPEED_MPS;
    private double MAX_ACCEL_MPS2;

    private double SEA_LEVEL_Y;
    private double CDA_BASE_M2;
    private double DRAG_MULTIPLIER;

    private double GFORCE_SAMPLE_SECONDS;
    private boolean GFORCE_KILL_CREATIVE;
    private double GFORCE_DAMAGE_START_G;
    private boolean GFORCE_WARN_ENABLED;
    private double GFORCE_WARN_THRESHOLD_G;
    private double GFORCE_WARN_INTERVAL_SEC;
    private String GFORCE_WARN_SOUND;
    private String GFORCE_WARN_ACTIONBAR;

    // g-damage table
    private static class GStep { double g; double dmg; GStep(double g, double dmg){this.g=g; this.dmg=dmg;} }
    private List<GStep> gDamageTable = new ArrayList<>();

    private static class Zone {
        String id;
        double minX, minY, minZ, maxX, maxY, maxZ;
        double dragMul, fuelMul;
        Double speedCapMps;
        int priority;
        boolean contains(double x, double y, double z) {
            return x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ;
        }
    }

    private Zone findZone(org.bukkit.Location loc) {
        if (!ZONES_ENABLED) return null;
        List<Zone> list = zonesByWorld.get(loc.getWorld().getName());
        if (list == null) return null;
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        Zone best = null;
        for (Zone zc : list) {
            if (zc.contains(x, y, z)) {
                if (best == null || zc.priority < best.priority) best = zc;
            }
        }
        return best;
    }

    // fuel config
    private boolean FUEL_ENABLED;
    private int FUEL_CAPACITY;
    private double FUEL_SAMPLE_COST_PER_HP;
    private int FUEL_COAL_PER_CHARGE;
    private int FUEL_GUNPOWDER_PER_CHARGE;
    private int FUEL_POINTS_PER_CHARGE;
    private double FUEL_NOTIFY_COOLDOWN_SECS;
    private boolean FUEL_BULK_CHARGE_ON_SNEAK;
    private int FUEL_MAX_SETS_PER_CLICK;

    // life config
    private boolean LIFE_ENABLED;
    private boolean LIFE_PREFER_REPAIR_WHEN_BROKEN;
    private boolean LIFE_BULK_REPAIR_ON_SNEAK;
    private int LIFE_MAX_SETS_PER_CLICK;
    private Material LIFE_REPAIR_MATERIAL;
    private int LIFE_MINUTES_PER_ITEM;
    private double LIFE_NOTIFY_COOLDOWN_SECS;

    // thrust vs altitude
    private boolean THRUST_ALT_ENABLED;
    private String  THRUST_MODEL;
    private double  THRUST_ALPHA;
    private double  THRUST_MIN_FACTOR;
    private double  THRUST_MAX_FACTOR;

    // boost config
    private boolean BOOST_ENABLED;
    private double  BOOST_DURATION_SEC;
    private double  BOOST_HP_MULT;
    private double  BOOST_FUEL_MULT;
    private int     BOOST_COST_REDSTONE;
    private boolean BOOST_CANCEL_IF_ECO;
    private String  BOOST_SOUND;
    private double  BOOST_COOLDOWN_SEC;

    // eco config
    private boolean ECO_ENABLED;
    private double  ECO_HP_MULT;
    private double  ECO_FUEL_MULT;
    private boolean ECO_CANCEL_IF_BOOST;
    private String  ECO_SOUND_ON;
    private String  ECO_SOUND_OFF;

    // zones config
    private boolean ZONES_ENABLED;
    private Map<String, List<Zone>> zonesByWorld = new HashMap<>();

    // state
    private final Map<UUID, ArrayDeque<Vector>> velHistoryMps = new HashMap<>();
    private final Map<UUID, Long> lastFuelNotify = new HashMap<>();
    private final Map<UUID, Long> lastLifeNotify = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private final Map<UUID, Long> lastStatus = new HashMap<>();
    private final Map<UUID, Long> lastGWarn = new HashMap<>();
    private final Map<UUID, Double> lastGValue = new HashMap<>();
    private final Map<UUID, Boolean> ecoEnabled = new HashMap<>();
    private final Map<UUID, Long> boostUntilTick = new HashMap<>();
    private final Map<UUID, Long> lastBoostUse = new HashMap<>();
    private static final long STATUS_INTERVAL_MS = 3000L;
    private long tickCounter = 0L;

    private static final Pattern HP_PATTERN = Pattern.compile("(?i)(?:hp|馬力)[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)");

    // Vanilla damping neutralizer (config)
    private boolean NEUTRALIZE_VANILLA_DRAG;
    private double VANILLA_AIR_DAMP;
    private double VANILLA_ELYTRA_DAMP;

    // ui
    private String UI_INFO_PERMISSION;
    private String UI_INFO_FORMAT;

    @Override
    public void onEnable() {
        HP_KEY = new NamespacedKey(this, "horsepower");
        FUEL_KEY = new NamespacedKey(this, "fuel");
        FUEL_CAP_KEY = new NamespacedKey(this, "fuelcap");
        LIFE_TOTAL_KEY = new NamespacedKey(this, "life_total_min");
        LIFE_USED_KEY = new NamespacedKey(this, "life_used_sec");
        LIFE_REPAIRED_KEY = new NamespacedKey(this, "life_repaired_min");

        saveDefaultConfig();
        reloadFromConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tickCounter++;
            final int sampleTicks = Math.max(1, (int)Math.round(GFORCE_SAMPLE_SECONDS / DT)); // normally 4

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.isGliding()) {
                    velHistoryMps.remove(p.getUniqueId());
                    continue;
                }

                // mass from scale
                double scale = getScaleOrDefault(p, 1.0);
                double massKg = BASE_MASS_KG * scale * scale;

                // current velocity
                Vector velBt = p.getVelocity();

                // --- Neutralize vanilla air/elytra damping (相殺) ---
                if (NEUTRALIZE_VANILLA_DRAG) {
                    double f = p.isGliding() ? VANILLA_ELYTRA_DAMP : VANILLA_AIR_DAMP;
                    if (f > 0.0 && f < 1.0) {
                        // この tick ですでに掛かっている減衰を 1/f 掛け戻して相殺
                        velBt.multiply(1.0 / f);
                    }
                }

                double speedBt = velBt.length();
                double speedMps = speedBt * 20.0;
                if (speedMps < MIN_SPEED_MPS) speedMps = MIN_SPEED_MPS;

                // air density
                double y = p.getLocation().getY();
                double h = y - SEA_LEVEL_Y;
                double rho = airDensityAtAltitude(h);

                // engine
                ItemStack engine = getEngineItem(p);
                double hp = extractHorsepower(engine);

                // Show fuel/life status while gliding (every 3s, no warnings)
                if (engine != null && (FUEL_ENABLED || LIFE_ENABLED)) {
                    long now = System.currentTimeMillis();
                    UUID id = p.getUniqueId();
                    long lastWarnAt = lastWarn.getOrDefault(id, 0L);
                    long lastStatusAt = lastStatus.getOrDefault(id, 0L);
                    if (now - lastWarnAt >= STATUS_INTERVAL_MS && now - lastStatusAt >= STATUS_INTERVAL_MS) {
                        StringBuilder sb = new StringBuilder();
                        if (FUEL_ENABLED) {
                            int fuelCur = getFuel(engine);
                            int fuelCap = getFuelCap(engine);
                            sb.append("燃料: ").append(fuelCur).append("/").append(fuelCap);
                        }
                        if (LIFE_ENABLED) {
                            if (sb.length() > 0) sb.append(" / ");
                            int total = getLifeTotal(engine);
                            if (total > 0) {
                                int repaired = getLifeRepaired(engine);
                                int usedTicks = getLifeUsedTicks(engine);
                                int remain = total + repaired - (int)Math.ceil((usedTicks / 20.0) / 60.0);
                                sb.append("寿命: ").append(remain).append("分");
                            } else {
                                sb.append("寿命:∞");
                            }
                        }
                        if (sb.length() > 0) {
                            p.sendActionBar(Component.text(sb.toString(), NamedTextColor.AQUA));
                            lastStatus.put(id, now);
                        }
                    }
                }

                // zone
                Zone zone = findZone(p.getLocation());
                double dragZoneMul = 1.0;
                double fuelZoneMul = 1.0;
                Double speedCapMps = null;
                if (zone != null) {
                    dragZoneMul = zone.dragMul;
                    fuelZoneMul = zone.fuelMul;
                    speedCapMps = zone.speedCapMps;
                }

                // drag
                double cdA = CDA_BASE_M2 * DRAG_MULTIPLIER * scale;
                double aDragBase = (0.5 * rho * cdA * speedMps * speedMps) / massKg;
                double aDrag = aDragBase * dragZoneMul;

                // thrust gating
                boolean thrustAllowed = (hp > 0.0) && !p.isSneaking();
                if (FUEL_ENABLED && thrustAllowed) {
                    int fuel = getFuel(engine);
                    if (fuel <= 0) {
                        thrustAllowed = false;
                        notifyFuelHint(p);
                    }
                }
                if (LIFE_ENABLED && thrustAllowed) {
                    int total = getLifeTotal(engine);
                    if (total > 0) {
                        int repaired = getLifeRepaired(engine);
                        int usedTicks = getLifeUsedTicks(engine);
                        int remain = total + repaired - (int)Math.ceil((usedTicks / 20.0) / 60.0);
                        if (remain <= 0) {
                            thrustAllowed = false;
                            notifyLifeHint(p);
                        } else if (tickCounter % sampleTicks == 0) {
                            usedTicks += sampleTicks;
                            setLifeUsedTicks(engine, usedTicks);
                            int remainAfter = total + repaired - (int)Math.ceil((usedTicks / 20.0) / 60.0);
                            if (remainAfter <= 0) notifyLifeHint(p);
                        }
                    }
                }
                double powerW = (thrustAllowed ? hp * WATT_PER_HP : 0.0);
                double aThrust = powerW / (massKg * speedMps);

                // altitude factor
                if (THRUST_ALT_ENABLED) {
                    double rho0 = airDensityAtAltitude(0.0);
                    double rhoRel = Math.max(0.0, Math.min(1.0, rho / rho0));
                    double fAlt = Math.pow(rhoRel, THRUST_ALPHA);
                    if (fAlt < THRUST_MIN_FACTOR) fAlt = THRUST_MIN_FACTOR;
                    if (fAlt > THRUST_MAX_FACTOR) fAlt = THRUST_MAX_FACTOR;
                    aThrust *= fAlt;
                }

                // mode multipliers
                double modeHpMul = 1.0;
                double modeFuelMul = 1.0;
                UUID id = p.getUniqueId();
                if (boostUntilTick.getOrDefault(id, 0L) > tickCounter) {
                    modeHpMul = BOOST_HP_MULT;
                    modeFuelMul = BOOST_FUEL_MULT;
                } else if (ecoEnabled.getOrDefault(id, false)) {
                    modeHpMul = ECO_HP_MULT;
                    modeFuelMul = ECO_FUEL_MULT;
                }
                double aThrustMode = aThrust * modeHpMul;

                // speed cap ratio
                double r = 1.0;
                if (speedCapMps != null) {
                    double v = speedMps;
                    double cap = speedCapMps;
                    double predicted = v + (aThrustMode - aDrag) * DT;
                    if (predicted > cap) {
                        r = (cap - (v - aDrag * DT)) / (aThrustMode * DT);
                        if (r < 0) r = 0;
                        if (r > 1) r = 1;
                    }
                }
                double aThrustEff = aThrustMode * r;

                // update velocity
                Vector dirFacing = p.getLocation().getDirection();
                if (dirFacing.lengthSquared() > 1e-6) dirFacing.normalize();
                Vector dirVel = speedBt > 1e-6 ? velBt.clone().normalize() : dirFacing.clone();

                double dvThrust_bt = (aThrustEff * DT) / 20.0;
                double dvDrag_bt   = (aDrag   * DT) / 20.0;

                double maxDv_bt = (MAX_ACCEL_MPS2 * DT) / 20.0;
                if (dvThrust_bt > maxDv_bt) dvThrust_bt = maxDv_bt;

                Vector newVel = velBt.clone();
                if (dvThrust_bt > 0 && dirFacing.lengthSquared() > 0) newVel.add(dirFacing.multiply(dvThrust_bt));
                if (dvDrag_bt   > 0 && dirVel.lengthSquared() > 0) {
                    double dragMag = Math.min(dvDrag_bt, newVel.length());
                    if (dragMag > 0) newVel.subtract(dirVel.multiply(dragMag));
                }
                p.setVelocity(newVel);

                // fuel consumption per 0.2s
                if (FUEL_ENABLED && thrustAllowed && (tickCounter % sampleTicks == 0)) {
                    double base = hp * FUEL_SAMPLE_COST_PER_HP;
                    double modeCost = base * modeFuelMul;
                    double zoneCost = modeCost * fuelZoneMul;
                    int cost = (int)Math.ceil(zoneCost * r);
                    if (cost > 0) {
                        int before = getFuel(engine);
                        int after = Math.max(0, before - cost);
                        setFuel(engine, after);
                        if (after == 0 && before > 0) notifyFuelHint(p);
                    }
                }

                // g-force damage per 0.2s
                updateGForceDamage(p, velBt, sampleTicks);
            }
        }, 1L, 1L);
    }

    // Commands
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("giveengine")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player p = (Player) sender;
            if (!p.hasPermission("elytrahp.giveengine")) {
                p.sendMessage(Component.text("権限がありません", NamedTextColor.RED));
                return true;
            }
            if (args.length != 1) {
                p.sendMessage(Component.text("/giveengine <hp>", NamedTextColor.YELLOW));
                return true;
            }
            double hp;
            try {
                hp = Double.parseDouble(args[0]);
            } catch (NumberFormatException e) {
                p.sendMessage(Component.text("数値で指定してください", NamedTextColor.RED));
                return true;
            }
            if (hp <= 0) {
                p.sendMessage(Component.text("hp は正の値にしてください", NamedTextColor.RED));
                return true;
            }
            ItemStack engine = createEngineItem(hp);
            p.getInventory().addItem(engine);
            p.sendMessage(Component.text("エンジンを付与しました: " + new DecimalFormat("0.##").format(hp) + " hp", NamedTextColor.GREEN));
            return true;
        }
        if (name.equals("elytrahp")) {
            if (args.length >= 1) {
                if (args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("elytrahp.admin")) {
                        sender.sendMessage(Component.text("権限がありません", NamedTextColor.RED));
                        return true;
                    }
                    reloadConfig();
                    reloadFromConfig();
                    sender.sendMessage(Component.text("ElytraHorsepower: config reloaded.", NamedTextColor.GREEN));
                    return true;
                }
                if (args[0].equalsIgnoreCase("info")) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                        return true;
                    }
                    Player p = (Player)sender;
                    if (!p.hasPermission(UI_INFO_PERMISSION)) {
                        p.sendMessage(Component.text("権限がありません", NamedTextColor.RED));
                        return true;
                    }
                    ItemStack engine = getEngineItem(p);
                    if (engine == null) {
                        p.sendMessage(Component.text("エンジンを手に持ってください", NamedTextColor.YELLOW));
                        return true;
                    }
                    double hp = extractHorsepower(engine);
                    double speedKmh = p.getVelocity().length() * 20.0 * 3.6;
                    int fuel = getFuel(engine);
                    int cap = getFuelCap(engine);
                    int total = getLifeTotal(engine);
                    int repaired = getLifeRepaired(engine);
                    int usedTicks = getLifeUsedTicks(engine);
                    int remain = (total > 0) ? total + repaired - (int)Math.ceil((usedTicks / 20.0) / 60.0) : -1;
                    double gVal = lastGValue.getOrDefault(p.getUniqueId(), 0.0);
                    UUID id = p.getUniqueId();
                    String mode = "NORMAL";
                    if (boostUntilTick.getOrDefault(id, 0L) > tickCounter) mode = "BOOST";
                    else if (ecoEnabled.getOrDefault(id, false)) mode = "ECO";
                    Zone z = findZone(p.getLocation());
                    String zoneId = (z != null ? z.id : "");
                    String out = UI_INFO_FORMAT
                            .replace("<hp>", new DecimalFormat("0.##").format(hp))
                            .replace("<speed_kmh>", String.format("%.1f", speedKmh))
                            .replace("<fuel>", String.valueOf(fuel))
                            .replace("<cap>", String.valueOf(cap))
                            .replace("<life_remain>", (total > 0 ? String.valueOf(remain) : "∞"))
                            .replace("<g>", String.format("%.1f", gVal))
                            .replace("<mode>", mode)
                            .replace("<zone>", zoneId);
                    p.sendMessage(Component.text(out, NamedTextColor.AQUA));
                    return true;
                }
                if (args[0].equalsIgnoreCase("life")) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                        return true;
                    }
                    Player p = (Player)sender;
                    if (!p.hasPermission("elytrahp.admin")) {
                        p.sendMessage(Component.text("権限がありません", NamedTextColor.RED));
                        return true;
                    }
                    ItemStack engine = getEngineItem(p);
                    if (engine == null) {
                        p.sendMessage(Component.text("手に持っているアイテムがエンジンではありません", NamedTextColor.YELLOW));
                        return true;
                    }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
                        if (args.length != 3) {
                            p.sendMessage(Component.text("/elytrahp life set <minutes>", NamedTextColor.YELLOW));
                            return true;
                        }
                        int minutes;
                        try {
                            minutes = Integer.parseInt(args[2]);
                        } catch (NumberFormatException ex) {
                            p.sendMessage(Component.text("数値で指定してください", NamedTextColor.RED));
                            return true;
                        }
                        if (minutes < 0) minutes = 0;
                        setLifeTotal(engine, minutes);
                        setLifeUsedTicks(engine, 0);
                        setLifeRepaired(engine, 0);
                        p.sendMessage(Component.text("寿命を " + minutes + " 分に設定しました", NamedTextColor.GREEN));
                        return true;
                    }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("info")) {
                        int total = getLifeTotal(engine);
                        int repaired = getLifeRepaired(engine);
                        int usedTicks = getLifeUsedTicks(engine);
                        int usedMin = (int)Math.ceil((usedTicks / 20.0) / 60.0);
                        int remain = total + repaired - usedMin;
                        int pool = total - repaired;
                        String state = remain <= 0 ? "故障中" : "故障していない";
                        p.sendMessage(Component.text("寿命: " + total + "分 / 使用: " + usedMin + "分 / 修理: " + repaired + "分 / 残り: " + remain + "分 / 修理可能残り: " + pool + "分 / 状態: " + state, NamedTextColor.YELLOW));
                        return true;
                    }
                    p.sendMessage(Component.text("Usage: /elytrahp life set <minutes> | /elytrahp life info", NamedTextColor.YELLOW));
                    return true;
                }
            }
            sender.sendMessage(Component.text("Usage: /elytrahp reload", NamedTextColor.YELLOW));
            return true;
        }
        return false;
    }

    // Right click to charge
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack engine = getEngineItem(p);
        if (engine == null) return;

        // gliding actions
        if (p.isGliding()) {
            if (a == Action.LEFT_CLICK_AIR && BOOST_ENABLED) {
                handleBoost(p, engine);
            } else if (a == Action.RIGHT_CLICK_AIR && ECO_ENABLED) {
                handleEcoToggle(p);
            }
            return;
        }

        if (!(a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK)) return;

        PlayerInventory inv = p.getInventory();

        if (LIFE_ENABLED) {
            int total = getLifeTotal(engine);
            if (total > 0) {
                int repaired = getLifeRepaired(engine);
                int usedTicks = getLifeUsedTicks(engine);
                int remain = total + repaired - (int)Math.ceil((usedTicks / 20.0) / 60.0);
                int haveRepair = countItem(inv, LIFE_REPAIR_MATERIAL);
                if (LIFE_PREFER_REPAIR_WHEN_BROKEN && remain <= 0) {
                    handleRepair(p, engine, p.isSneaking() && LIFE_BULK_REPAIR_ON_SNEAK);
                    return;
                }
                if (p.isSneaking() && LIFE_BULK_REPAIR_ON_SNEAK && haveRepair > 0) {
                    handleRepair(p, engine, true);
                    return;
                }
            }
        }

        if (!FUEL_ENABLED) {
            sendActionBarMessage(p, Component.text("燃料システムは無効です (config: fuel.enabled=false)", NamedTextColor.GRAY));
            return;
        }

        final int coalNeed = FUEL_COAL_PER_CHARGE;
        final int gunNeed  = FUEL_GUNPOWDER_PER_CHARGE;
        final int ptsPerSet = FUEL_POINTS_PER_CHARGE;

        // まとめてチャージ（スニーク右クリック & 有効時）
        if (FUEL_BULK_CHARGE_ON_SNEAK && p.isSneaking()) {
            if (ptsPerSet <= 0) {
                sendActionBarMessage(p, Component.text("設定エラー: fuel.charge.points が 0 以下です", NamedTextColor.RED));
                return;
            }
            int haveCoal = countItem(inv, Material.COAL);
            int haveGun  = countItem(inv, Material.GUNPOWDER);
            int setsByCoal = coalNeed > 0 ? (haveCoal / coalNeed) : Integer.MAX_VALUE;
            int setsByGun  = gunNeed > 0 ? (haveGun / gunNeed) : Integer.MAX_VALUE;
            int cap = getFuelCap(engine);
            int cur = getFuel(engine);
            int roomPts = Math.max(0, cap - cur);
            int setsByCap = ptsPerSet > 0 ? (roomPts / ptsPerSet) : 0;

            int sets = Math.min(Math.min(setsByCoal, setsByGun), setsByCap);
            sets = Math.min(sets, Math.max(1, FUEL_MAX_SETS_PER_CLICK));

            if (sets <= 0) {
                if (setsByCap <= 0) {
                    if (roomPts <= 0) {
                        sendActionBarMessage(p, Component.text("燃料はすでに満タンです (" + cur + "/" + cap + ")", NamedTextColor.YELLOW));
                    } else {
                        sendActionBarMessage(p, Component.text("燃料の残容量が不足しています (" + cur + "/" + cap + ")", NamedTextColor.YELLOW));
                    }
                } else {
                    sendActionBarMessage(p, Component.text("チャージに必要: 石炭×" + coalNeed + " + 火薬×" + gunNeed, NamedTextColor.YELLOW));
                }
                return;
            }
            if (coalNeed > 0) removeItems(inv, Material.COAL, coalNeed * sets);
            if (gunNeed  > 0) removeItems(inv, Material.GUNPOWDER, gunNeed * sets);
            int addPts = ptsPerSet * sets;
            int newVal = Math.min(cap, cur + addPts);
            setFuel(engine, newVal);
            sendActionBarMessage(p, Component.text("まとめてチャージ +" + addPts + "pt (" + sets + "セット消費)  燃料: " + newVal + "/" + cap, NamedTextColor.GOLD));
            return;
        }

        // 通常（非スニーク）: 1 セットだけチャージ
        int cap = getFuelCap(engine);
        int cur = getFuel(engine);
        int room = cap - cur;
        if (room <= 0) {
            sendActionBarMessage(p, Component.text("燃料はすでに満タンです (" + cur + "/" + cap + ")", NamedTextColor.YELLOW));
            return;
        }
        if (room < ptsPerSet) {
            sendActionBarMessage(p, Component.text("燃料の残容量が不足しています (" + cur + "/" + cap + ")", NamedTextColor.YELLOW));
            return;
        }
        if (countItem(inv, Material.COAL) >= coalNeed && countItem(inv, Material.GUNPOWDER) >= gunNeed) {
            removeItems(inv, Material.COAL, coalNeed);
            removeItems(inv, Material.GUNPOWDER, gunNeed);
            int add = ptsPerSet;
            int newVal = Math.min(cap, cur + add);
            setFuel(engine, newVal);
            sendActionBarMessage(p, Component.text("チャージ +" + add + "pt  (燃料: " + newVal + "/" + cap + ")", NamedTextColor.GOLD));
        } else {
            sendActionBarMessage(p, Component.text("チャージに必要: 石炭×" + coalNeed + " + 火薬×" + gunNeed, NamedTextColor.YELLOW));
        }
    }

    // Inventory helpers
    private int countItem(PlayerInventory inv, Material m) {
        int n = 0;
        for (ItemStack it : inv.getContents()) if (it != null && it.getType() == m) n += it.getAmount();
        return n;
    }
    private void removeItems(PlayerInventory inv, Material m, int amount) {
        for (int i=0; i<inv.getSize() && amount>0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != m) continue;
            int take = Math.min(amount, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) inv.setItem(i, null);
            amount -= take;
        }
    }

    // Fuel on item
    private int getFuel(ItemStack is) {
        if (is == null) return 0;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return 0;
        Integer v = meta.getPersistentDataContainer().get(FUEL_KEY, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }
    private void setFuel(ItemStack is, int value) {
        if (is == null) return;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(FUEL_KEY, PersistentDataType.INTEGER, Math.max(0, value));
        is.setItemMeta(meta);
    }
    private int getFuelCap(ItemStack is) {
        if (is == null) return FUEL_CAPACITY;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return FUEL_CAPACITY;
        Integer v = meta.getPersistentDataContainer().get(FUEL_CAP_KEY, PersistentDataType.INTEGER);
        return v == null ? FUEL_CAPACITY : v;
    }

    // Life on item
    private int getLifeTotal(ItemStack is) {
        if (is == null) return 0;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return 0;
        Integer v = meta.getPersistentDataContainer().get(LIFE_TOTAL_KEY, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }
    private void setLifeTotal(ItemStack is, int value) {
        if (is == null) return;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(LIFE_TOTAL_KEY, PersistentDataType.INTEGER, Math.max(0, value));
        is.setItemMeta(meta);
    }
    private int getLifeUsedTicks(ItemStack is) {
        if (is == null) return 0;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return 0;
        Integer v = meta.getPersistentDataContainer().get(LIFE_USED_KEY, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }
    private void setLifeUsedTicks(ItemStack is, int value) {
        if (is == null) return;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(LIFE_USED_KEY, PersistentDataType.INTEGER, Math.max(0, value));
        is.setItemMeta(meta);
    }
    private int getLifeRepaired(ItemStack is) {
        if (is == null) return 0;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return 0;
        Integer v = meta.getPersistentDataContainer().get(LIFE_REPAIRED_KEY, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, v);
    }
    private void setLifeRepaired(ItemStack is, int value) {
        if (is == null) return;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(LIFE_REPAIRED_KEY, PersistentDataType.INTEGER, Math.max(0, value));
        is.setItemMeta(meta);
    }

    private void notifyLifeHint(Player p) {
        long now = System.currentTimeMillis();
        long last = lastLifeNotify.getOrDefault(p.getUniqueId(), 0L);
        if ((now - last) < (long)(LIFE_NOTIFY_COOLDOWN_SECS * 1000.0)) return;
        lastLifeNotify.put(p.getUniqueId(), now);
        sendActionBarMessage(p, Component.text("エンジン故障: エンジンを手に持って右クリック → " + LIFE_REPAIR_MATERIAL + "(1個=" + LIFE_MINUTES_PER_ITEM + "分)で修理", NamedTextColor.RED));
    }

    private void handleRepair(Player p, ItemStack engine, boolean bulk) {
        int total = getLifeTotal(engine);
        if (total <= 0) {
            sendActionBarMessage(p, Component.text("このエンジンは寿命が設定されていません（管理者: /elytrahp life set <分>）", NamedTextColor.YELLOW));
            return;
        }
        int repaired = getLifeRepaired(engine);
        int usedTicks = getLifeUsedTicks(engine);
        int usedMin = (int)Math.ceil((usedTicks / 20.0) / 60.0);
        int remain = total + repaired - usedMin;
        int pool = total - repaired;
        PlayerInventory inv = p.getInventory();
        int have = countItem(inv, LIFE_REPAIR_MATERIAL);
        int minutesByInv = have * LIFE_MINUTES_PER_ITEM;
        if (pool <= 0) {
            sendActionBarMessage(p, Component.text("修理不能: 修理上限に達しました（累計 " + repaired + "/" + total + " 分）", NamedTextColor.YELLOW));
            return;
        }
        if (minutesByInv <= 0) {
            sendActionBarMessage(p, Component.text("修理に必要: " + LIFE_REPAIR_MATERIAL + " ×1（" + LIFE_MINUTES_PER_ITEM + "分）", NamedTextColor.YELLOW));
            return;
        }
        int minutes;
        if (bulk) {
            minutes = Math.min(pool, minutesByInv);
            minutes = Math.min(minutes, Math.max(1, LIFE_MAX_SETS_PER_CLICK));
        } else {
            minutes = Math.min(Math.min(LIFE_MINUTES_PER_ITEM, pool), minutesByInv);
        }
        if (minutes <= 0) {
            sendActionBarMessage(p, Component.text("修理不能: 修理上限に達しました（累計 " + repaired + "/" + total + " 分）", NamedTextColor.YELLOW));
            return;
        }
        int items = (int)Math.ceil((double)minutes / LIFE_MINUTES_PER_ITEM);
        removeItems(inv, LIFE_REPAIR_MATERIAL, items);
        repaired += minutes;
        setLifeRepaired(engine, repaired);
        remain += minutes;
        int poolAfter = total - repaired;
        sendActionBarMessage(p, Component.text("修理 +" + minutes + "分（" + LIFE_REPAIR_MATERIAL + " " + items + " 個） 残り: " + remain + "分 / 修理可能残り: " + poolAfter + "分", NamedTextColor.GOLD));
    }

    private void handleBoost(Player p, ItemStack engine) {
        long now = tickCounter;
        UUID id = p.getUniqueId();
        long until = boostUntilTick.getOrDefault(id, 0L);
        long last = lastBoostUse.getOrDefault(id, 0L);
        long cooldownTicks = (long)Math.round(BOOST_COOLDOWN_SEC * 20.0);
        if (now < until) {
            sendActionBarMessage(p, Component.text("BOOST継続中", NamedTextColor.YELLOW));
            return;
        }
        if (now - last < cooldownTicks) {
            return;
        }
        PlayerInventory inv = p.getInventory();
        int need = BOOST_COST_REDSTONE;
        if (countItem(inv, Material.REDSTONE) < need) {
            sendActionBarMessage(p, Component.text("ブーストに必要: レッドストーン×" + need, NamedTextColor.YELLOW));
            return;
        }
        if (need > 0) removeItems(inv, Material.REDSTONE, need);
        if (ecoEnabled.getOrDefault(id, false) && BOOST_CANCEL_IF_ECO) {
            ecoEnabled.put(id, false);
        }
        long durTicks = (long)Math.round(BOOST_DURATION_SEC * 20.0);
        boostUntilTick.put(id, now + durTicks);
        lastBoostUse.put(id, now);
        if (BOOST_SOUND != null && !BOOST_SOUND.isEmpty()) p.playSound(p.getLocation(), BOOST_SOUND, 1f, 1f);
        sendActionBarMessage(p, Component.text("+20% 出力 (" + BOOST_DURATION_SEC + "s)", NamedTextColor.GOLD));
    }

    private void handleEcoToggle(Player p) {
        UUID id = p.getUniqueId();
        boolean on = ecoEnabled.getOrDefault(id, false);
        if (on) {
            ecoEnabled.put(id, false);
            if (ECO_SOUND_OFF != null && !ECO_SOUND_OFF.isEmpty()) p.playSound(p.getLocation(), ECO_SOUND_OFF, 1f, 1f);
            sendActionBarMessage(p, Component.text("ECO OFF", NamedTextColor.GREEN));
        } else {
            if (boostUntilTick.getOrDefault(id, 0L) > tickCounter && ECO_CANCEL_IF_BOOST) {
                boostUntilTick.put(id, 0L);
            }
            ecoEnabled.put(id, true);
            if (ECO_SOUND_ON != null && !ECO_SOUND_ON.isEmpty()) p.playSound(p.getLocation(), ECO_SOUND_ON, 1f, 1f);
            sendActionBarMessage(p, Component.text("ECO ON", NamedTextColor.GOLD));
        }
    }

    private void sendActionBarMessage(Player p, Component c) {
        p.sendActionBar(c);
        lastWarn.put(p.getUniqueId(), System.currentTimeMillis());
    }

    private void notifyFuelHint(Player p) {
        long now = System.currentTimeMillis();
        long last = lastFuelNotify.getOrDefault(p.getUniqueId(), 0L);
        if ((now - last) < (long)(FUEL_NOTIFY_COOLDOWN_SECS * 1000.0)) return;
        lastFuelNotify.put(p.getUniqueId(), now);
        sendActionBarMessage(p, Component.text("燃料切れ: エンジンを手に持って右クリック → 石炭×" + FUEL_COAL_PER_CHARGE + " + 火薬×" + FUEL_GUNPOWDER_PER_CHARGE + " で +" + FUEL_POINTS_PER_CHARGE + "pt", NamedTextColor.RED));
    }

    // g-force damage
    private void updateGForceDamage(Player p, Vector velBtCurrent, int sampleTicks) {
        UUID id = p.getUniqueId();
        ArrayDeque<Vector> q = velHistoryMps.computeIfAbsent(id, k -> new ArrayDeque<>(sampleTicks + 1));

        Vector vNowMps = velBtCurrent.clone().multiply(20.0);
        q.addLast(vNowMps);
        if (q.size() > sampleTicks + 1) q.removeFirst();

        if (tickCounter % sampleTicks != 0) return;
        if (q.size() < sampleTicks + 1) return;

        Vector vPast = q.peekFirst().clone();
        Vector dv = vNowMps.clone().subtract(vPast);
        double a = dv.length() / (sampleTicks * DT);
        double gForce = a / G;

        lastGValue.put(id, gForce);

        if (GFORCE_WARN_ENABLED && gForce >= GFORCE_WARN_THRESHOLD_G) {
            long now = System.currentTimeMillis();
            long last = lastGWarn.getOrDefault(id, 0L);
            if ((now - last) >= (long)(GFORCE_WARN_INTERVAL_SEC * 1000.0)) {
                lastGWarn.put(id, now);
                String msg = GFORCE_WARN_ACTIONBAR.replace("{g}", String.format("%.1f", gForce));
                sendActionBarMessage(p, Component.text(msg, NamedTextColor.YELLOW));
                if (GFORCE_WARN_SOUND != null && !GFORCE_WARN_SOUND.isEmpty()) {
                    p.playSound(p.getLocation(), GFORCE_WARN_SOUND, 1f, 1f);
                }
            }
        }

        if (gForce < GFORCE_DAMAGE_START_G) return;

        double dmg = computeDamageFromTable(gForce);
        if (dmg <= 0) return;

        if (!GFORCE_KILL_CREATIVE && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) return;

        p.damage(dmg);
        sendActionBarMessage(p, Component.text(String.format("%.1f g : -%.1f", Math.min(gForce, 100.0), dmg), NamedTextColor.RED));
    }

    private double computeDamageFromTable(double g) {
        double best = 0.0;
        for (GStep s : gDamageTable) {
            if (g >= s.g && s.dmg > best) best = s.dmg;
        }
        return best;
    }

    // Physics
    private double airDensityAtAltitude(double hMeters) {
        double term = 1.0 - (L * hMeters) / T0;
        if (term < 0.0) term = 0.0;
        double exponent = G / (R * L);
        double T = T0 - L * hMeters;
        if (T < 1.0) T = 1.0;
        double P = P0 * Math.pow(term, exponent);
        return P / (R * T);
    }

    private double getScaleOrDefault(Player p, double def) {
        try {
            Attribute scaleAttrEnum = Attribute.valueOf("GENERIC_SCALE");
            AttributeInstance inst = p.getAttribute(scaleAttrEnum);
            if (inst != null) return inst.getValue();
        } catch (IllegalArgumentException ignored) {}
        return def;
    }

    // Engine utils
    private ItemStack getEngineItem(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isEngine(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isEngine(off)) return off;
        return null;
    }

    private boolean isEngine(ItemStack is) {
        if (is == null || is.getType() == Material.AIR) return false;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(HP_KEY, PersistentDataType.DOUBLE)) return true;
        String name = meta.hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : "";
        if (HP_PATTERN.matcher(name).find()) return true;
        List<Component> lore = meta.lore();
        if (lore != null) for (Component c : lore) {
            if (HP_PATTERN.matcher(PlainTextComponentSerializer.plainText().serialize(c)).find()) return true;
        }
        return false;
    }

    private double extractHorsepower(ItemStack is) {
        if (is == null || is.getType() == Material.AIR) return 0.0;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return 0.0;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Double hp = pdc.get(HP_KEY, PersistentDataType.DOUBLE);
        if (hp != null && hp > 0) return hp;
        if (meta.hasDisplayName()) {
            Double parsed = parseHpFromString(PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
            if (parsed != null) return parsed;
        }
        List<Component> lore = meta.lore();
        if (lore != null) for (Component c : lore) {
            Double parsed = parseHpFromString(PlainTextComponentSerializer.plainText().serialize(c));
            if (parsed != null) return parsed;
        }
        return 0.0;
    }

    private Double parseHpFromString(String s) {
        if (s == null) return null;
        Matcher m = HP_PATTERN.matcher(s);
        if (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                return v > 0 ? v : null;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private ItemStack createEngineItem(double hp) {
        ItemStack engine = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = engine.getItemMeta();
        DecimalFormat df = new DecimalFormat("0.##");
        meta.displayName(Component.text("Elytra Engine (" + df.format(hp) + " hp)", NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("馬力: " + df.format(hp) + " hp", NamedTextColor.YELLOW));
        lore.add(Component.text("手に持って滑空で加速 (スニークでカット)", NamedTextColor.GRAY));
        meta.lore(lore);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(HP_KEY, PersistentDataType.DOUBLE, hp);
        pdc.set(FUEL_KEY, PersistentDataType.INTEGER, 0);
        pdc.set(FUEL_CAP_KEY, PersistentDataType.INTEGER, FUEL_CAPACITY);
        engine.setItemMeta(meta);
        return engine;
    }

    // Config loader
    private void reloadFromConfig() {
        BASE_MASS_KG = getConfig().getDouble("physics.base_mass_kg", DEFAULT_BASE_MASS_KG);
        WATT_PER_HP  = getConfig().getDouble("physics.watt_per_hp",   DEFAULT_WATT_PER_HP);
        MIN_SPEED_MPS = getConfig().getDouble("physics.min_speed_mps", DEFAULT_MIN_SPEED_MPS);
        MAX_ACCEL_MPS2 = getConfig().getDouble("physics.max_accel_mps2", DEFAULT_MAX_ACCEL_MPS2);

        SEA_LEVEL_Y = getConfig().getDouble("aero.sea_level_y", DEFAULT_SEA_LEVEL_Y);
        CDA_BASE_M2 = getConfig().getDouble("aero.cda_base_m2", DEFAULT_CDA_BASE_M2);
        DRAG_MULTIPLIER = getConfig().getDouble("aero.drag_multiplier", DEFAULT_DRAG_MULTIPLIER);

        GFORCE_SAMPLE_SECONDS = getConfig().getDouble("gforce.sample_seconds", DEFAULT_GFORCE_SAMPLE_SECONDS);
        GFORCE_KILL_CREATIVE = getConfig().getBoolean("gforce.kill_in_creative", DEFAULT_GFORCE_KILL_CREATIVE);
        GFORCE_DAMAGE_START_G = getConfig().getDouble("gforce.damage_start_g", DEFAULT_GFORCE_DAMAGE_START_G);
        GFORCE_WARN_ENABLED = getConfig().getBoolean("gforce.warn.enabled", DEFAULT_GFORCE_WARN_ENABLED);
        GFORCE_WARN_THRESHOLD_G = getConfig().getDouble("gforce.warn.threshold_g", DEFAULT_GFORCE_WARN_THRESHOLD_G);
        GFORCE_WARN_INTERVAL_SEC = getConfig().getDouble("gforce.warn.interval_sec", DEFAULT_GFORCE_WARN_INTERVAL_SEC);
        GFORCE_WARN_SOUND = getConfig().getString("gforce.warn.sound", DEFAULT_GFORCE_WARN_SOUND);
        GFORCE_WARN_ACTIONBAR = getConfig().getString("gforce.warn.actionbar", DEFAULT_GFORCE_WARN_ACTIONBAR);

        // g damage table
        gDamageTable.clear();
        List<Map<?,?>> list = getConfig().getMapList("gforce.damage_table");
        if (list != null && !list.isEmpty()) {
            for (Map<?,?> m : list) {
                try {
                    double g = Double.parseDouble(String.valueOf(m.get("g")));
                    double dmg = Double.parseDouble(String.valueOf(m.get("dmg")));
                    gDamageTable.add(new GStep(g, dmg));
                } catch (Exception ignored) {}
            }
            gDamageTable.sort(Comparator.comparingDouble(s -> s.g));
        } else {
            for (int i=0;i<=13;i++) {
                double g = GFORCE_DAMAGE_START_G + 0.5*i;
                double dmg = 3.0 + 1.5*i;
                gDamageTable.add(new GStep(g, dmg));
            }
        }

        // fuel
        FUEL_ENABLED = getConfig().getBoolean("fuel.enabled", true);
        FUEL_CAPACITY = getConfig().getInt("fuel.capacity", 3000);
        FUEL_SAMPLE_COST_PER_HP = getConfig().getDouble("fuel.sample_cost_per_hp", 1.0);
        FUEL_COAL_PER_CHARGE = getConfig().getInt("fuel.charge.coal", 1);
        FUEL_GUNPOWDER_PER_CHARGE = getConfig().getInt("fuel.charge.gunpowder", 2);
        FUEL_POINTS_PER_CHARGE = getConfig().getInt("fuel.charge.points", 300);
        FUEL_NOTIFY_COOLDOWN_SECS = getConfig().getDouble("fuel.notify_cooldown_seconds", 2.0);
        FUEL_BULK_CHARGE_ON_SNEAK = getConfig().getBoolean("fuel.bulk_charge_on_sneak", true);
        FUEL_MAX_SETS_PER_CLICK   = getConfig().getInt("fuel.max_sets_per_click", 9999);

        // vanilla damping neutralizer
        NEUTRALIZE_VANILLA_DRAG = getConfig().getBoolean("vanilla.neutralize_drag", DEFAULT_NEUTRALIZE_VANILLA_DRAG);
        VANILLA_AIR_DAMP        = getConfig().getDouble("vanilla.air_damping_per_tick", DEFAULT_VANILLA_AIR_DAMP);
        VANILLA_ELYTRA_DAMP     = getConfig().getDouble("vanilla.elytra_damping_per_tick", DEFAULT_VANILLA_ELYTRA_DAMP);

        // life
        LIFE_ENABLED = getConfig().getBoolean("life.enabled", true);
        LIFE_PREFER_REPAIR_WHEN_BROKEN = getConfig().getBoolean("life.prefer_repair_when_broken", true);
        LIFE_BULK_REPAIR_ON_SNEAK = getConfig().getBoolean("life.bulk_repair_on_sneak", true);
        LIFE_MAX_SETS_PER_CLICK = getConfig().getInt("life.max_sets_per_click", 9999);
        String matName = getConfig().getString("life.repair_material", "LAPIS_BLOCK");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.LAPIS_BLOCK;
        LIFE_REPAIR_MATERIAL = mat;
        LIFE_MINUTES_PER_ITEM = getConfig().getInt("life.minutes_per_item", 1);
        LIFE_NOTIFY_COOLDOWN_SECS = getConfig().getDouble("life.notify_cooldown_seconds", 2.0);

        // thrust altitude
        THRUST_ALT_ENABLED = getConfig().getBoolean("thrust_altitude.enabled", true);
        THRUST_MODEL = getConfig().getString("thrust_altitude.model", "rho_power");
        THRUST_ALPHA = getConfig().getDouble("thrust_altitude.alpha", 1.0);
        THRUST_MIN_FACTOR = getConfig().getDouble("thrust_altitude.min_factor", 0.40);
        THRUST_MAX_FACTOR = getConfig().getDouble("thrust_altitude.max_factor", 1.00);

        // boost
        BOOST_ENABLED = getConfig().getBoolean("boost.enabled", true);
        BOOST_DURATION_SEC = getConfig().getDouble("boost.duration_sec", 4.0);
        BOOST_HP_MULT = getConfig().getDouble("boost.hp_multiplier", 1.20);
        BOOST_FUEL_MULT = getConfig().getDouble("boost.fuel_multiplier", 1.30);
        BOOST_COST_REDSTONE = getConfig().getInt("boost.activation_cost.redstone", 1);
        BOOST_CANCEL_IF_ECO = getConfig().getBoolean("boost.cancel_if_eco", true);
        BOOST_SOUND = getConfig().getString("boost.sound", "minecraft:item.totem.use");
        BOOST_COOLDOWN_SEC = getConfig().getDouble("boost.cooldown_sec", 0.0);

        // eco
        ECO_ENABLED = getConfig().getBoolean("eco.enabled", true);
        ECO_HP_MULT = getConfig().getDouble("eco.hp_multiplier", 0.65);
        ECO_FUEL_MULT = getConfig().getDouble("eco.fuel_multiplier", 0.60);
        ECO_CANCEL_IF_BOOST = getConfig().getBoolean("eco.cancel_if_boost", true);
        ECO_SOUND_ON = getConfig().getString("eco.sound_on", "minecraft:block.note_block.hat");
        ECO_SOUND_OFF = getConfig().getString("eco.sound_off", "minecraft:block.note_block.bass");

        // zones
        ZONES_ENABLED = getConfig().getBoolean("zones.enabled", true);
        zonesByWorld.clear();
        if (ZONES_ENABLED) {
            List<Map<?,?>> zl = getConfig().getMapList("zones.list");
            for (Map<?,?> m : zl) {
                try {
                    Zone z = new Zone();
                    z.id = String.valueOf(m.get("id"));
                    z.priority = ((Number)m.getOrDefault("priority", 0)).intValue();
                    z.dragMul = m.get("drag_multiplier") == null ? 1.0 : Double.parseDouble(String.valueOf(m.get("drag_multiplier")));
                    z.fuelMul = m.get("fuel_multiplier") == null ? 1.0 : Double.parseDouble(String.valueOf(m.get("fuel_multiplier")));
                    Object capObj = m.get("speed_cap_kmh");
                    if (capObj != null && !capObj.equals("null")) {
                        double capKmh = Double.parseDouble(String.valueOf(capObj));
                        z.speedCapMps = capKmh / 3.6;
                    }
                    Map<?,?> min = (Map<?,?>)m.get("min");
                    Map<?,?> max = (Map<?,?>)m.get("max");
                    z.minX = Double.parseDouble(String.valueOf(min.get("x")));
                    z.minY = Double.parseDouble(String.valueOf(min.get("y")));
                    z.minZ = Double.parseDouble(String.valueOf(min.get("z")));
                    z.maxX = Double.parseDouble(String.valueOf(max.get("x")));
                    z.maxY = Double.parseDouble(String.valueOf(max.get("y")));
                    z.maxZ = Double.parseDouble(String.valueOf(max.get("z")));
                    String world = String.valueOf(m.get("world"));
                    zonesByWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(z);
                } catch (Exception ignored) {}
            }
            for (List<Zone> l : zonesByWorld.values()) {
                l.sort(Comparator.comparingInt(z -> z.priority));
            }
        }

        // ui
        UI_INFO_PERMISSION = getConfig().getString("ui.player_info_permission", "elytrahp.info");
        UI_INFO_FORMAT = getConfig().getString("ui.info_format", "<hp>hp | v=<speed_kmh>km/h | fuel=<fuel>/<cap> | life=<life_remain>min | g=<g> | mode=<mode>");
    }
}
