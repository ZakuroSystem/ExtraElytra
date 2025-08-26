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
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
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

    // --- Defaults (config overrideable) ---
    private static final double DEFAULT_BASE_MASS_KG = 70.0;        // scale=1.0 mass [kg]
    private static final double DEFAULT_WATT_PER_HP = 745.699872;    // 1 hp [W]
    private static final double DEFAULT_MIN_SPEED_MPS = 0.5;         // avoid divergence near v=0
    private static final double DEFAULT_MAX_ACCEL_MPS2 = 50.0;       // safety clamp

    private static final double DEFAULT_SEA_LEVEL_Y = 64.0;          // y=64 m as sea level
    private static final double DEFAULT_CDA_BASE_M2 = 0.70;          // baseline CdA [m^2]
    private static final double DEFAULT_DRAG_MULTIPLIER = 1.0;       // extra multiplier

    // g-force params
    private static final double DEFAULT_GFORCE_SAMPLE_SECONDS = 0.2; // 4 ticks
    private static final double DEFAULT_GFORCE_DEATH_G_UNUSED = 1e9; // unused, left for compat
    private static final boolean DEFAULT_GFORCE_KILL_CREATIVE = false;

    // Physics constants
    private static final double G = 9.80665;          // m/s^2
    private static final double T0 = 288.15;          // K sea-level temperature
    private static final double L = 0.0065;           // K/m lapse rate
    private static final double P0 = 101325.0;        // Pa sea-level pressure (1013 hPa)
    private static final double R = 287.05;           // J/(kg·K) dry air gas constant

    private static final double DT = 1.0 / 20.0;      // 1 tick [s]

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

    private static final Pattern HP_PATTERN = Pattern.compile("(?i)(?:hp|馬力)[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)");

    // g-force: store recent velocity (m/s) to compute 0.2 s delta; tick counter to sample
    private final Map<UUID, ArrayDeque<Vector>> velHistoryMps = new HashMap<>();
    private long tickCounter = 0L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadFromConfig();

        HP_KEY = new NamespacedKey(this, "horsepower");
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tickCounter++;
            final int sampleTicks = Math.max(1, (int)Math.round(GFORCE_SAMPLE_SECONDS / DT)); // normally 4

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.isGliding()) {
                    velHistoryMps.remove(p.getUniqueId());
                    continue;
                }

                // --- engine hp (0 if none) ---
                double hp = extractHorsepower(getEngineItem(p));

                // --- mass etc. ---
                double scale = getScaleOrDefault(p, 1.0);
                double massKg = BASE_MASS_KG * scale * scale;    // m = 70 * scale^2

                // --- current velocity ---
                Vector velBt = p.getVelocity();                  // blocks/tick
                double speedBt = velBt.length();
                double speedMps = speedBt * 20.0;
                if (speedMps < MIN_SPEED_MPS) speedMps = MIN_SPEED_MPS;

                // --- air density (ISA troposphere 0–11 km) ---
                double y = p.getLocation().getY();
                double h = y - SEA_LEVEL_Y;                      // m
                double rho = airDensityAtAltitude(h);            // kg/m^3

                // --- drag a = (1/2 * rho * CdA * v^2) / m  -- apply to ALL gliders ---
                double cdA = CDA_BASE_M2 * DRAG_MULTIPLIER * scale; // scale-proportional
                double aDrag = (0.5 * rho * cdA * speedMps * speedMps) / massKg; // m/s^2

                // --- thrust a = P/(m v)  (sneak cuts to 0) ---
                double powerW = p.isSneaking() ? 0.0 : (hp * WATT_PER_HP);
                double aThrust = powerW / (massKg * speedMps);   // m/s^2

                // --- update velocity ---
                Vector dirFacing = p.getLocation().getDirection();
                if (dirFacing.lengthSquared() > 1e-6) dirFacing.normalize();
                Vector dirVel = speedBt > 1e-6 ? velBt.clone().normalize() : dirFacing.clone();

                double dvThrust_bt = (aThrust * DT) / 20.0;
                double dvDrag_bt   = (aDrag   * DT) / 20.0;

                double maxDv_bt = (MAX_ACCEL_MPS2 * DT) / 20.0; // safety clamp
                if (dvThrust_bt > maxDv_bt) dvThrust_bt = maxDv_bt;

                Vector newVel = velBt.clone();
                if (dvThrust_bt > 0 && dirFacing.lengthSquared() > 0) newVel.add(dirFacing.multiply(dvThrust_bt));
                if (dvDrag_bt   > 0 && dirVel.lengthSquared() > 0) {
                    double dragMag = Math.min(dvDrag_bt, newVel.length());
                    if (dragMag > 0) newVel.subtract(dirVel.multiply(dragMag));
                }
                p.setVelocity(newVel);

                // --- g-force: sample every 0.2 s (sampleTicks) and apply step damage ---
                updateGForceDamage(p, velBt, sampleTicks);
            }
        }, 1L, 1L);
    }

    private void reloadFromConfig() {
        BASE_MASS_KG = getConfig().getDouble("physics.base_mass_kg", DEFAULT_BASE_MASS_KG);
        WATT_PER_HP  = getConfig().getDouble("physics.watt_per_hp",   DEFAULT_WATT_PER_HP);
        MIN_SPEED_MPS = getConfig().getDouble("physics.min_speed_mps", DEFAULT_MIN_SPEED_MPS);
        MAX_ACCEL_MPS2 = getConfig().getDouble("physics.max_accel_mps2", DEFAULT_MAX_ACCEL_MPS2);

        SEA_LEVEL_Y = getConfig().getDouble("aero.sea_level_y", DEFAULT_SEA_LEVEL_Y);
        CDA_BASE_M2 = getConfig().getDouble("aero.cda_base_m2", DEFAULT_CDA_BASE_M2);
        DRAG_MULTIPLIER = getConfig().getDouble("aero.drag_multiplier", DEFAULT_DRAG_MULTIPLIER);

        GFORCE_SAMPLE_SECONDS = getConfig().getDouble("gforce.sample_seconds", DEFAULT_GFORCE_SAMPLE_SECONDS);
        // death_threshold_g is unused in this spec
        GFORCE_KILL_CREATIVE = getConfig().getBoolean("gforce.kill_in_creative", DEFAULT_GFORCE_KILL_CREATIVE);
    }

    // ISA troposphere (0–11 km): density from altitude
    private double airDensityAtAltitude(double hMeters) {
        // T = T0 - L*h ; P = P0 * (1 - L*h/T0)^(g/(R*L)) ; rho = P/(R*T)
        double term = 1.0 - (L * hMeters) / T0;
        if (term < 0.0) term = 0.0;
        double exponent = G / (R * L); // ≈ 5.25588
        double T = T0 - L * hMeters;
        if (T < 1.0) T = 1.0; // safety
        double P = P0 * Math.pow(term, exponent);
        return P / (R * T);
    }

    // 0.2s averaged acceleration → g, then **step damage every 0.2s** for 3.0–9.5 g
    private void updateGForceDamage(Player p, Vector velBtCurrent, int sampleTicks) {
        UUID id = p.getUniqueId();
        ArrayDeque<Vector> q = velHistoryMps.computeIfAbsent(id, k -> new ArrayDeque<>(sampleTicks + 1));

        // push current velocity (m/s)
        Vector vNowMps = velBtCurrent.clone().multiply(20.0);
        q.addLast(vNowMps);
        if (q.size() > sampleTicks + 1) q.removeFirst();

        // apply only each 0.2s boundary
        if (tickCounter % sampleTicks != 0) return;
        if (q.size() < sampleTicks + 1) return;

        Vector vPast = q.peekFirst().clone();
        Vector dv = vNowMps.clone().subtract(vPast);
        double a = dv.length() / (sampleTicks * DT); // m/s^2 (includes turn)
        double gForce = a / G;

        double dmg = computeStepDamage(gForce);
        if (dmg <= 0) return;

        if (!GFORCE_KILL_CREATIVE && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) return;

        p.damage(dmg);
        p.sendActionBar(Component.text(String.format("%.1f g : -%.1f", Math.min(gForce, 9.5), dmg), NamedTextColor.RED));
    }

    // Spec: 3.0g→3.0, 3.5→4.5, 4.0→6.0, ... up to 9.5→22.5 (0.5g step adds +1.5)
    private double computeStepDamage(double g) {
        if (g < 3.0) return 0.0;
        double gClamped = Math.min(g, 9.5);
        double step = Math.floor((gClamped - 3.0) / 0.5); // 0..13
        return 3.0 + step * 1.5;
    }

    // main/offhand engine item
    private ItemStack getEngineItem(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (isEngine(main)) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (isEngine(off)) return off;
        return main;
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

    private double getScaleOrDefault(Player p, double def) {
        try {
            Attribute scaleAttrEnum = Attribute.valueOf("GENERIC_SCALE");
            AttributeInstance inst = p.getAttribute(scaleAttrEnum);
            if (inst != null) return inst.getValue();
        } catch (IllegalArgumentException ignored) {}
        return def;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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

        ItemStack engine = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = engine.getItemMeta();
        DecimalFormat df = new DecimalFormat("0.##");
        meta.displayName(Component.text("Elytra Engine (" + df.format(hp) + " hp)", NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("馬力: " + df.format(hp) + " hp", NamedTextColor.YELLOW));
        lore.add(Component.text("手に持って滑空で加速 (スニークでカット)", NamedTextColor.GRAY));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(HP_KEY, PersistentDataType.DOUBLE, hp);
        engine.setItemMeta(meta);

        p.getInventory().addItem(engine);
        p.sendMessage(Component.text("エンジンを付与しました: " + df.format(hp) + " hp", NamedTextColor.GREEN));
        return true;
    }
}
