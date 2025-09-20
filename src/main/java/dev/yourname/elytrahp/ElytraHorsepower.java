package dev.yourname.elytrahp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

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
    private static final double VANILLA_SPEED_CAP_BT = 3.92; // blocks per tick (vanilla velocity cap)
    private static final double TELEPORT_SPEED_FACTOR = 3.0; // allow up to 3x vanilla cap

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
    private final Set<UUID> GFORCE_DAMAGE_IMMUNE = new HashSet<>();

    // g-damage table
    private static class GStep { double g; double dmg; GStep(double g, double dmg){this.g=g; this.dmg=dmg;} }
    private List<GStep> gDamageTable = new ArrayList<>();

    private static class BoostItem {
        String id;
        Material material;
        int amount;
        double durationSec;
        double hpMultiplier;
        double fuelMultiplier;
        double lifeMultiplier;
    }

    private static class ActiveBoost {
        long untilTick;
        BoostItem item;
    }

    private enum FlightMode {
        NORMAL,
        ECO,
        ENGINE_STOP
    }

    private static final double ENGINE_STOP_FUEL_RATIO = 0.10;
    private static final double ENGINE_RAMP_SECONDS = 2.0;

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
    private boolean BOOST_CANCEL_IF_ECO;
    private String  BOOST_SOUND;
    private double  BOOST_COOLDOWN_SEC;
    private final List<BoostItem> BOOST_ITEMS = new ArrayList<>();

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
    private final Map<UUID, Location> lastTickLocation = new HashMap<>();
    private final Map<UUID, Vector> effectiveVelocityBt = new HashMap<>();
    private final Map<UUID, Long> lastFuelNotify = new HashMap<>();
    private final Map<UUID, Long> lastLifeNotify = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private final Map<UUID, Long> lastStatus = new HashMap<>();
    private final Map<UUID, Long> lastGWarn = new HashMap<>();
    private final Map<UUID, Double> lastGValue = new HashMap<>();
    private final Map<UUID, FlightMode> flightModes = new HashMap<>();
    private final Map<UUID, ActiveBoost> activeBoosts = new HashMap<>();
    private final Map<UUID, Long> lastBoostUse = new HashMap<>();
    private final Map<UUID, Double> lifeTickFraction = new HashMap<>();
    private final Map<UUID, Long> engineHoldStartTick = new HashMap<>();
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
        saveBundledDefaultConfig();
        reloadFromConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tickCounter++;
            final int sampleTicks = Math.max(1, (int)Math.round(GFORCE_SAMPLE_SECONDS / DT)); // normally 4

            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (!p.isGliding()) {
                    velHistoryMps.remove(id);
                    engineHoldStartTick.remove(id);
                    lastTickLocation.remove(id);
                    effectiveVelocityBt.remove(id);
                    continue;
                }

                // mass from scale
                double scale = getScaleOrDefault(p, 1.0);
                double massKg = BASE_MASS_KG * scale * scale;

                Location currentLocation = p.getLocation();

                // current velocity (use displacement between ticks if available)
                Vector velBt = p.getVelocity();
                Location prevLocation = lastTickLocation.get(id);
                if (prevLocation != null && prevLocation.getWorld() == currentLocation.getWorld()) {
                    Vector displacement = currentLocation.toVector().subtract(prevLocation.toVector());
                    velBt = displacement;
                }

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
                double y = currentLocation.getY();
                double h = y - SEA_LEVEL_Y;
                double rho = airDensityAtAltitude(h);

                // engine
                ItemStack engine = getEngineItem(p);
                double holdFactor = 0.0;
                if (engine != null) {
                    long startTick = engineHoldStartTick.computeIfAbsent(id, k -> tickCounter);
                    double elapsed = (tickCounter - startTick) * DT;
                    double factor = elapsed / ENGINE_RAMP_SECONDS;
                    if (factor < 0.0) factor = 0.0;
                    if (factor > 1.0) factor = 1.0;
                    holdFactor = factor;
                } else {
                    engineHoldStartTick.remove(id);
                    setFlightMode(id, FlightMode.NORMAL);
                }
                double hp = extractHorsepower(engine);

                ActiveBoost activeBoost = activeBoosts.get(id);
                if (activeBoost != null && activeBoost.untilTick <= tickCounter) {
                    activeBoosts.remove(id);
                    activeBoost = null;
                }
                FlightMode mode = getFlightMode(id);
                double modeHpMul = 1.0;
                double modeFuelMul = 1.0;
                double modeLifeMul = 1.0;
                if (activeBoost != null) {
                    modeHpMul = activeBoost.item.hpMultiplier;
                    modeFuelMul = activeBoost.item.fuelMultiplier;
                    modeLifeMul = activeBoost.item.lifeMultiplier;
                } else {
                    if (mode == FlightMode.ECO) {
                        modeHpMul = ECO_HP_MULT;
                        modeFuelMul = ECO_FUEL_MULT;
                    } else if (mode == FlightMode.ENGINE_STOP) {
                        modeHpMul = 0.0;
                        modeFuelMul = ENGINE_STOP_FUEL_RATIO;
                    }
                }

                // Show fuel/life status while gliding (every 3s, no warnings)
                if (engine != null && (FUEL_ENABLED || LIFE_ENABLED)) {
                    long now = System.currentTimeMillis();
                    UUID id = p.getUniqueId();
                    long lastWarnAt = lastWarn.getOrDefault(id, 0L);
                    long lastStatusAt = lastStatus.getOrDefault(id, 0L);
                    if (now - lastWarnAt >= STATUS_INTERVAL_MS && now - lastStatusAt >= STATUS_INTERVAL_MS) {
                        StringBuilder sb = new StringBuilder();
                        if (FUEL_ENABLED) {
                            int fuelCur = displayFuel(engine);
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
                Zone zone = findZone(currentLocation);
                double dragZoneMul = 1.0;
                double fuelZoneMul = 1.0;
                Double speedCapMps = null;
                if (zone != null) {
                    dragZoneMul = zone.dragMul;
                    fuelZoneMul = zone.fuelMul;
                    speedCapMps = zone.speedCapMps;
                }

                // drag
                // Cross-sectional area grows with the square of the scale, otherwise
                // large players (scale > 1) retain too much kinetic energy when diving.
                double cdA = CDA_BASE_M2 * DRAG_MULTIPLIER * scale * scale;
                double aDragBase = (0.5 * rho * cdA * speedMps * speedMps) / massKg;
                double aDrag = aDragBase * dragZoneMul;

                // thrust gating
                boolean thrustAllowed = (hp > 0.0) && !p.isSneaking() && mode != FlightMode.ENGINE_STOP;
                if (FUEL_ENABLED && thrustAllowed) {
                    double fuel = getFuel(engine);
                    if (fuel <= 1e-6) {
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
                            double incTicks = sampleTicks * modeLifeMul * holdFactor;
                            double carry = lifeTickFraction.getOrDefault(id, 0.0);
                            double totalInc = carry + incTicks;
                            int addTicks = (int)Math.floor(totalInc + 1e-9);
                            double newCarry = totalInc - addTicks;
                            if (addTicks > 0) {
                                usedTicks += addTicks;
                                setLifeUsedTicks(engine, usedTicks);
                            }
                            if (newCarry > 1e-9) {
                                lifeTickFraction.put(id, newCarry);
                            } else {
                                lifeTickFraction.remove(id);
                            }
                            int remainAfter = total + repaired - (int)Math.ceil((usedTicks / 20.0) / 60.0);
                            if (remainAfter <= 0) notifyLifeHint(p);
                        }
                    }
                } else {
                    lifeTickFraction.remove(id);
                }
                double powerW = (thrustAllowed ? hp * WATT_PER_HP * holdFactor : 0.0);
                double aThrust = powerW / (massKg * speedMps);
                double thrustAltFactor = 1.0;

                // altitude factor
                if (THRUST_ALT_ENABLED) {
                    double rho0 = airDensityAtAltitude(0.0);
                    double rhoRel = Math.max(0.0, Math.min(1.0, rho / rho0));
                    double fAlt = Math.pow(rhoRel, THRUST_ALPHA);
                    if (fAlt < THRUST_MIN_FACTOR) fAlt = THRUST_MIN_FACTOR;
                    if (fAlt > THRUST_MAX_FACTOR) fAlt = THRUST_MAX_FACTOR;
                    thrustAltFactor = fAlt;
                    aThrust *= thrustAltFactor;
                }

                // mode multipliers already resolved above
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
                Vector dirFacing = currentLocation.getDirection();
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

                double desiredSpeedBt = newVel.length();
                Vector desiredDir = desiredSpeedBt > 1e-6 ? newVel.clone().normalize() : dirFacing.clone();
                double maxDesiredBt = VANILLA_SPEED_CAP_BT * TELEPORT_SPEED_FACTOR;
                if (desiredSpeedBt > maxDesiredBt) {
                    desiredSpeedBt = maxDesiredBt;
                }

                double appliedSpeedBt = Math.min(desiredSpeedBt, VANILLA_SPEED_CAP_BT);
                Vector appliedVelocity = desiredDir.clone().multiply(appliedSpeedBt);
                double extraDistanceBt = desiredSpeedBt - appliedSpeedBt;

                p.setVelocity(appliedVelocity);
                if (extraDistanceBt > 1e-6 && desiredDir.lengthSquared() > 0) {
                    Location loc = p.getLocation().clone().add(desiredDir.clone().multiply(extraDistanceBt));
                    p.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                }

                effectiveVelocityBt.put(id, desiredDir.clone().multiply(desiredSpeedBt));
                lastTickLocation.put(id, p.getLocation().clone());

                // fuel consumption per 0.2s
                if (FUEL_ENABLED && engine != null && (tickCounter % sampleTicks == 0)) {
                    double throttleForFuel = holdFactor;
                    if (!thrustAllowed) {
                        if (mode == FlightMode.ENGINE_STOP) {
                            throttleForFuel = 1.0;
                        } else {
                            throttleForFuel = 0.0;
                        }
                    }
                    if (throttleForFuel > 1e-6) {
                        double base = hp * FUEL_SAMPLE_COST_PER_HP;
                        double altCost = base * thrustAltFactor;
                        double modeCost = altCost * modeFuelMul;
                        double zoneCost = modeCost * fuelZoneMul;
                        double cost = zoneCost * r * throttleForFuel;
                        if (cost > 1e-9) {
                            double before = getFuel(engine);
                            double after = Math.max(0.0, before - cost);
                            setFuel(engine, after);
                            if (after <= 1e-6 && before > 1e-6) notifyFuelHint(p);
                        }
                    }
                }

                // g-force damage per 0.2s
                Vector effectiveVelocityForG = desiredDir.clone().multiply(desiredSpeedBt);
                updateGForceDamage(p, effectiveVelocityForG, sampleTicks);
            }
        }, 1L, 1L);
    }

    private void saveBundledDefaultConfig() {
        File outFile = new File(getDataFolder(), "default_config.yml");
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                getLogger().warning("config.yml resource not found; default_config.yml was not saved");
                return;
            }
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                getLogger().warning("Could not create plugin data directory for default_config.yml");
                return;
            }
            Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Failed to save default_config.yml", ex);
        }
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
            if (args.length != 3) {
                p.sendMessage(Component.text("/giveengine <hp> <life_min> <fuel_capacity>", NamedTextColor.YELLOW));
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
            int lifeMinutes;
            try {
                lifeMinutes = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                p.sendMessage(Component.text("寿命は整数で指定してください", NamedTextColor.RED));
                return true;
            }
            if (lifeMinutes < 0) lifeMinutes = 0;

            int fuelCapacity;
            try {
                fuelCapacity = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                p.sendMessage(Component.text("燃料容量は整数で指定してください", NamedTextColor.RED));
                return true;
            }
            if (fuelCapacity <= 0) {
                p.sendMessage(Component.text("燃料容量は正の値にしてください", NamedTextColor.RED));
                return true;
            }

            ItemStack engine = createEngineItem(hp, lifeMinutes, fuelCapacity);
            p.getInventory().addItem(engine);
            DecimalFormat df = new DecimalFormat("0.##");
            p.sendMessage(Component.text("エンジンを付与しました: " + df.format(hp) + " hp / 寿命 " + (lifeMinutes > 0 ? lifeMinutes + "分" : "∞") +
                    " / 燃料容量 " + fuelCapacity + "pt", NamedTextColor.GREEN));
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
                    Vector effective = effectiveVelocityBt.get(p.getUniqueId());
                    double speedKmh = (effective != null ? effective.length() : p.getVelocity().length()) * 20.0 * 3.6;
                    int fuel = displayFuel(engine);
                    int cap = getFuelCap(engine);
                    int total = getLifeTotal(engine);
                    int repaired = getLifeRepaired(engine);
                    int usedTicks = getLifeUsedTicks(engine);
                    int remain = (total > 0) ? total + repaired - (int)Math.ceil((usedTicks / 20.0) / 60.0) : -1;
                    double gVal = lastGValue.getOrDefault(p.getUniqueId(), 0.0);
                    UUID id = p.getUniqueId();
                    String mode = "NORMAL";
                    ActiveBoost active = activeBoosts.get(id);
                    if (active != null && active.untilTick <= tickCounter) {
                        activeBoosts.remove(id);
                        active = null;
                    }
                    if (active != null) {
                        String label = (active.item.id != null && !active.item.id.isEmpty()) ? "(" + active.item.id + ")" : "";
                        mode = "BOOST" + label;
                    } else {
                        FlightMode state = getFlightMode(id);
                        if (state == FlightMode.ECO) {
                            mode = "ECO";
                        } else if (state == FlightMode.ENGINE_STOP) {
                            mode = "STOP";
                        }
                    }
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
                        if (total <= 0) {
                            p.sendMessage(Component.text("寿命: ∞ / 使用: " + usedMin + "分 / 修理: " + repaired + "分 / 残り: ∞ / 修理可能残り: ∞ / 状態: 故障していない", NamedTextColor.YELLOW));
                            return true;
                        }
                        int remain = total + repaired - usedMin;
                        int pool = Math.max(0, total - repaired);
                        String state = remain <= 0 ? "故障中" : "故障していない";
                        p.sendMessage(Component.text("寿命: " + total + "分 / 使用: " + usedMin + "分 / 修理: " + repaired + "分 / 残り: " + remain + "分 / 修理可能残り: " + pool + "分 / 状態: " + state, NamedTextColor.YELLOW));
                        return true;
                    }
                    p.sendMessage(Component.text("Usage: /elytrahp life set <minutes> | /elytrahp life info", NamedTextColor.YELLOW));
                    return true;
                }
                if (args[0].equalsIgnoreCase("fuel")) {
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
                    if (args.length >= 2 && args[1].equalsIgnoreCase("setcap")) {
                        if (args.length != 3) {
                            p.sendMessage(Component.text("/elytrahp fuel setcap <points>", NamedTextColor.YELLOW));
                            return true;
                        }
                        int cap;
                        try {
                            cap = Integer.parseInt(args[2]);
                        } catch (NumberFormatException ex) {
                            p.sendMessage(Component.text("数値で指定してください", NamedTextColor.RED));
                            return true;
                        }
                        if (cap <= 0) {
                            p.sendMessage(Component.text("燃料容量は正の値にしてください", NamedTextColor.RED));
                            return true;
                        }
                        setFuelCap(engine, cap);
                        double current = getFuel(engine);
                        int newCap = getFuelCap(engine);
                        if (current > newCap) setFuel(engine, newCap);
                        p.sendMessage(Component.text("燃料容量を " + newCap + " pt に設定しました", NamedTextColor.GREEN));
                        return true;
                    }
                    p.sendMessage(Component.text("Usage: /elytrahp fuel setcap <points>", NamedTextColor.YELLOW));
                    return true;
                }
            }
            sender.sendMessage(Component.text("Usage: /elytrahp reload | /elytrahp life ... | /elytrahp fuel ...", NamedTextColor.YELLOW));
            return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        velHistoryMps.remove(id);
        engineHoldStartTick.remove(id);
        lastTickLocation.remove(id);
        effectiveVelocityBt.remove(id);
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
            double cur = getFuel(engine);
            double roomPts = Math.max(0.0, cap - cur);
            int setsByCap = ptsPerSet > 0 ? (int)Math.floor(roomPts / ptsPerSet) : 0;

            int sets = Math.min(Math.min(setsByCoal, setsByGun), setsByCap);
            sets = Math.min(sets, Math.max(1, FUEL_MAX_SETS_PER_CLICK));

            if (sets <= 0) {
                int displayCur = (int)Math.floor(cur + 1e-6);
                if (setsByCap <= 0) {
                    if (roomPts <= 1e-6) {
                        sendActionBarMessage(p, Component.text("燃料はすでに満タンです (" + displayCur + "/" + cap + ")", NamedTextColor.YELLOW));
                    } else {
                        sendActionBarMessage(p, Component.text("燃料の残容量が不足しています (" + displayCur + "/" + cap + ")", NamedTextColor.YELLOW));
                    }
                } else {
                    sendActionBarMessage(p, Component.text("チャージに必要: 石炭×" + coalNeed + " + 火薬×" + gunNeed, NamedTextColor.YELLOW));
                }
                return;
            }
            if (coalNeed > 0) removeItems(inv, Material.COAL, coalNeed * sets);
            if (gunNeed  > 0) removeItems(inv, Material.GUNPOWDER, gunNeed * sets);
            int addPts = ptsPerSet * sets;
            double newVal = Math.min(cap, cur + addPts);
            setFuel(engine, newVal);
            int displayNew = (int)Math.floor(newVal + 1e-6);
            sendActionBarMessage(p, Component.text("まとめてチャージ +" + addPts + "pt (" + sets + "セット消費)  燃料: " + displayNew + "/" + cap, NamedTextColor.GOLD));
            return;
        }

        // 通常（非スニーク）: 1 セットだけチャージ
        int cap = getFuelCap(engine);
        double cur = getFuel(engine);
        double room = cap - cur;
        int displayCur = (int)Math.floor(cur + 1e-6);
        if (room <= 1e-6) {
            sendActionBarMessage(p, Component.text("燃料はすでに満タンです (" + displayCur + "/" + cap + ")", NamedTextColor.YELLOW));
            return;
        }
        if (room + 1e-6 < ptsPerSet) {
            sendActionBarMessage(p, Component.text("燃料の残容量が不足しています (" + displayCur + "/" + cap + ")", NamedTextColor.YELLOW));
            return;
        }
        if (countItem(inv, Material.COAL) >= coalNeed && countItem(inv, Material.GUNPOWDER) >= gunNeed) {
            removeItems(inv, Material.COAL, coalNeed);
            removeItems(inv, Material.GUNPOWDER, gunNeed);
            int add = ptsPerSet;
            double newVal = Math.min(cap, cur + add);
            setFuel(engine, newVal);
            int displayNew = (int)Math.floor(newVal + 1e-6);
            sendActionBarMessage(p, Component.text("チャージ +" + add + "pt  (燃料: " + displayNew + "/" + cap + ")", NamedTextColor.GOLD));
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
    private double getFuel(ItemStack is) {
        if (is == null) return 0.0;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return 0.0;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Double v = pdc.get(FUEL_KEY, PersistentDataType.DOUBLE);
        if (v != null) return Math.max(0.0, v);
        Integer legacy = pdc.get(FUEL_KEY, PersistentDataType.INTEGER);
        if (legacy != null) return Math.max(0.0, legacy.doubleValue());
        return 0.0;
    }
    private void setFuel(ItemStack is, double value) {
        if (is == null) return;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(FUEL_KEY);
        pdc.set(FUEL_KEY, PersistentDataType.DOUBLE, Math.max(0.0, value));
        is.setItemMeta(meta);
    }
    private int displayFuel(ItemStack is) {
        return (int)Math.floor(getFuel(is) + 1e-6);
    }
    private int getFuelCap(ItemStack is) {
        if (is == null) return FUEL_CAPACITY;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return FUEL_CAPACITY;
        Integer v = meta.getPersistentDataContainer().get(FUEL_CAP_KEY, PersistentDataType.INTEGER);
        if (v == null || v <= 0) return FUEL_CAPACITY;
        return v;
    }

    private void setFuelCap(ItemStack is, int value) {
        if (is == null) return;
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return;
        int cap = Math.max(1, value);
        meta.getPersistentDataContainer().set(FUEL_CAP_KEY, PersistentDataType.INTEGER, cap);
        is.setItemMeta(meta);
        double current = getFuel(is);
        if (current > cap) {
            setFuel(is, cap);
        }
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
        ActiveBoost existing = activeBoosts.get(id);
        if (existing != null && existing.untilTick <= now) {
            activeBoosts.remove(id);
            existing = null;
        }
        if (existing != null && existing.untilTick > now) {
            sendActionBarMessage(p, Component.text("BOOST継続中", NamedTextColor.YELLOW));
            return;
        }
        long last = lastBoostUse.getOrDefault(id, 0L);
        long cooldownTicks = (long)Math.round(BOOST_COOLDOWN_SEC * 20.0);
        if (now - last < cooldownTicks) {
            return;
        }
        if (BOOST_ITEMS.isEmpty()) {
            sendActionBarMessage(p, Component.text("ブースト設定が存在しません", NamedTextColor.RED));
            return;
        }
        PlayerInventory inv = p.getInventory();
        BoostItem chosen = selectBoostItem(p);
        if (chosen == null) {
            sendBoostRequirements(p);
            return;
        }
        if (chosen.amount > 0) {
            removeItems(inv, chosen.material, chosen.amount);
        }
        if (BOOST_CANCEL_IF_ECO) {
            FlightMode mode = getFlightMode(id);
            if (mode != FlightMode.NORMAL) {
                setFlightMode(id, FlightMode.NORMAL);
            }
        }
        long durTicks = (long)Math.round(chosen.durationSec * 20.0);
        if (durTicks <= 0) durTicks = 1;
        ActiveBoost active = new ActiveBoost();
        active.item = chosen;
        active.untilTick = now + durTicks;
        activeBoosts.put(id, active);
        lastBoostUse.put(id, now);
        if (BOOST_SOUND != null && !BOOST_SOUND.isEmpty()) p.playSound(p.getLocation(), BOOST_SOUND, 1f, 1f);
        StringBuilder msg = new StringBuilder();
        msg.append("BOOST");
        if (chosen.id != null && !chosen.id.isEmpty()) {
            msg.append("(").append(chosen.id).append(")");
        }
        msg.append(" ");
        msg.append(formatMultiplierDelta(chosen.hpMultiplier)).append(" 出力 (")
           .append(formatNumber(chosen.durationSec)).append("s)");
        if (Math.abs(chosen.fuelMultiplier - 1.0) > 1e-4) {
            msg.append(" / 燃料").append(formatMultiplierDelta(chosen.fuelMultiplier));
        }
        if (Math.abs(chosen.lifeMultiplier - 1.0) > 1e-4) {
            msg.append(" / 寿命").append(formatMultiplierDelta(chosen.lifeMultiplier));
        }
        sendActionBarMessage(p, Component.text(msg.toString(), NamedTextColor.GOLD));
    }

    private BoostItem selectBoostItem(Player p) {
        if (BOOST_ITEMS.isEmpty()) return null;
        PlayerInventory inv = p.getInventory();
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() != Material.AIR) {
            BoostItem held = findBoostItem(off.getType());
            if (held != null && (held.amount <= 0 || countItem(inv, held.material) >= held.amount)) {
                return held;
            }
        }
        for (BoostItem item : BOOST_ITEMS) {
            if (item.amount <= 0 || countItem(inv, item.material) >= item.amount) {
                return item;
            }
        }
        return null;
    }

    private BoostItem findBoostItem(Material material) {
        if (material == null || material == Material.AIR) return null;
        for (BoostItem item : BOOST_ITEMS) {
            if (item.material == material) return item;
        }
        return null;
    }

    private void sendBoostRequirements(Player p) {
        StringBuilder sb = new StringBuilder("ブーストに必要: ");
        boolean first = true;
        for (BoostItem item : BOOST_ITEMS) {
            if (!first) sb.append(" / ");
            first = false;
            String label = (item.id != null && !item.id.isEmpty()) ? item.id : item.material.name();
            sb.append(label).append("×");
            sb.append(item.amount > 0 ? item.amount : 0);
        }
        sendActionBarMessage(p, Component.text(sb.toString(), NamedTextColor.YELLOW));
    }

    private int parseInt(Object value, int def) {
        if (value instanceof Number) {
            return ((Number)value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String)value);
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private double parseDouble(Object value, double def) {
        if (value instanceof Number) {
            return ((Number)value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String)value);
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private void handleEcoToggle(Player p) {
        UUID id = p.getUniqueId();
        FlightMode current = getFlightMode(id);
        FlightMode next;
        switch (current) {
            case ECO -> next = FlightMode.ENGINE_STOP;
            case ENGINE_STOP -> next = FlightMode.NORMAL;
            default -> next = FlightMode.ECO;
        }

        if (next == FlightMode.ECO && ECO_CANCEL_IF_BOOST) {
            ActiveBoost active = activeBoosts.get(id);
            if (active != null && active.untilTick > tickCounter) {
                activeBoosts.remove(id);
            }
        }

        if (next == FlightMode.ENGINE_STOP && ECO_CANCEL_IF_BOOST) {
            ActiveBoost active = activeBoosts.get(id);
            if (active != null && active.untilTick > tickCounter) {
                activeBoosts.remove(id);
            }
        }

        setFlightMode(id, next);

        switch (next) {
            case ECO -> {
                if (ECO_SOUND_ON != null && !ECO_SOUND_ON.isEmpty()) p.playSound(p.getLocation(), ECO_SOUND_ON, 1f, 1f);
                String msg = "ECO ON: 推力" + formatMultiplierAbsolute(ECO_HP_MULT) +
                             " / 燃料" + formatMultiplierAbsolute(ECO_FUEL_MULT);
                sendActionBarMessage(p, Component.text(msg, NamedTextColor.GOLD));
            }
            case ENGINE_STOP -> {
                if (ECO_SOUND_OFF != null && !ECO_SOUND_OFF.isEmpty()) p.playSound(p.getLocation(), ECO_SOUND_OFF, 1f, 1f);
                sendActionBarMessage(p, Component.text("ENGINE STOP: 推力0% / 燃料10%", NamedTextColor.YELLOW));
            }
            case NORMAL -> {
                if (ECO_SOUND_OFF != null && !ECO_SOUND_OFF.isEmpty()) p.playSound(p.getLocation(), ECO_SOUND_OFF, 1f, 1f);
                sendActionBarMessage(p, Component.text("ECO OFF", NamedTextColor.GREEN));
            }
        }
    }

    private FlightMode getFlightMode(UUID id) {
        return flightModes.getOrDefault(id, FlightMode.NORMAL);
    }

    private void setFlightMode(UUID id, FlightMode mode) {
        if (mode == FlightMode.NORMAL) {
            flightModes.remove(id);
        } else {
            flightModes.put(id, mode);
        }
    }

    private String formatMultiplierDelta(double multiplier) {
        return formatPercent((multiplier - 1.0) * 100.0, true);
    }

    private String formatMultiplierAbsolute(double multiplier) {
        return formatPercent(multiplier * 100.0, false);
    }

    private String formatPercent(double value, boolean includeSign) {
        if (Math.abs(value) < 0.005) value = 0.0;
        double rounded = Math.abs(value - Math.round(value)) < 0.0001 ? Math.round(value) : Math.round(value * 10.0) / 10.0;
        String text = (Math.abs(rounded - Math.round(rounded)) < 0.0001)
            ? String.format("%.0f", Math.abs(rounded))
            : String.format("%.1f", Math.abs(rounded));
        if (!includeSign) {
            return text + "%";
        }
        if (value > 0) return "+" + text + "%";
        if (value < 0) return "-" + text + "%";
        return "0%";
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.round(value)) < 0.0001) {
            return String.format("%.0f", value);
        }
        return String.format("%.1f", value);
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

        if (GFORCE_DAMAGE_IMMUNE.contains(id)) return;

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
        return createEngineItem(hp, 0, FUEL_CAPACITY);
    }

    private ItemStack createEngineItem(double hp, int lifeMinutes, int fuelCapacity) {
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
        pdc.set(FUEL_KEY, PersistentDataType.DOUBLE, 0.0);
        int cap = fuelCapacity > 0 ? fuelCapacity : FUEL_CAPACITY;
        pdc.set(FUEL_CAP_KEY, PersistentDataType.INTEGER, cap);
        int life = Math.max(0, lifeMinutes);
        pdc.set(LIFE_TOTAL_KEY, PersistentDataType.INTEGER, life);
        pdc.set(LIFE_USED_KEY, PersistentDataType.INTEGER, 0);
        pdc.set(LIFE_REPAIRED_KEY, PersistentDataType.INTEGER, 0);
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
        GFORCE_DAMAGE_IMMUNE.clear();
        List<String> immune = getConfig().getStringList("gforce.damage_immunity_uuids");
        for (String raw : immune) {
            if (raw == null || raw.trim().isEmpty()) continue;
            try {
                GFORCE_DAMAGE_IMMUNE.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ex) {
                getLogger().log(Level.WARNING, "Invalid UUID in gforce.damage_immunity_uuids: " + raw);
            }
        }
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
        BOOST_CANCEL_IF_ECO = getConfig().getBoolean("boost.cancel_if_eco", true);
        BOOST_SOUND = getConfig().getString("boost.sound", "minecraft:item.totem.use");
        BOOST_COOLDOWN_SEC = getConfig().getDouble("boost.cooldown_sec", 0.0);
        BOOST_ITEMS.clear();
        List<Map<?, ?>> rawBoostItems = getConfig().getMapList("boost.items");
        if (!rawBoostItems.isEmpty()) {
            int added = 0;
            for (Map<?, ?> raw : rawBoostItems) {
                if (raw == null) continue;
                if (added >= 3) break;
                String matName = Objects.toString(raw.get("material"), "");
                if (matName.isEmpty()) {
                    getLogger().warning("boost.items entry is missing material");
                    continue;
                }
                Material mat = Material.matchMaterial(matName);
                if (mat == null) {
                    mat = Material.matchMaterial(matName.toUpperCase(Locale.ROOT));
                }
                if (mat == null) {
                    getLogger().warning("Unknown boost material: " + matName);
                    continue;
                }
                BoostItem item = new BoostItem();
                item.id = Objects.toString(raw.get("id"), "");
                item.material = mat;
                item.amount = parseInt(raw.get("amount"), 1);
                if (item.amount < 0) item.amount = 0;
                item.durationSec = parseDouble(raw.get("duration_sec"), getConfig().getDouble("boost.duration_sec", 4.0));
                item.hpMultiplier = parseDouble(raw.get("hp_multiplier"), getConfig().getDouble("boost.hp_multiplier", 1.20));
                item.fuelMultiplier = parseDouble(raw.get("fuel_multiplier"), getConfig().getDouble("boost.fuel_multiplier", 1.30));
                item.lifeMultiplier = parseDouble(raw.get("life_multiplier"), getConfig().getDouble("boost.life_multiplier", 1.0));
                BOOST_ITEMS.add(item);
                added++;
            }
        }
        if (BOOST_ITEMS.isEmpty()) {
            BoostItem legacy = new BoostItem();
            legacy.id = "redstone";
            legacy.material = Material.REDSTONE;
            legacy.amount = Math.max(0, getConfig().getInt("boost.activation_cost.redstone", 1));
            legacy.durationSec = getConfig().getDouble("boost.duration_sec", 4.0);
            legacy.hpMultiplier = getConfig().getDouble("boost.hp_multiplier", 1.20);
            legacy.fuelMultiplier = getConfig().getDouble("boost.fuel_multiplier", 1.30);
            legacy.lifeMultiplier = getConfig().getDouble("boost.life_multiplier", 1.0);
            BOOST_ITEMS.add(legacy);
        }
        activeBoosts.clear();
        flightModes.clear();
        engineHoldStartTick.clear();

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
                    Object prObj = m.get("priority");
                    z.priority = prObj == null ? 0 : ((Number) prObj).intValue();
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
