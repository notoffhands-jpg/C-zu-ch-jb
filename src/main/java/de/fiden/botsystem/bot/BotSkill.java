package de.fiden.botsystem.bot;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class BotSkill {
    private double skill;
    public BotSkill.Personality personality;
    public final double pathBias;
    public final double timingJitter;
    public double hopHabit;
    public double pace;
    public double lane;
    public double curiosity;
    public double fidgetiness;
    public double critHabit;
    public double shieldHabit;
    public double retreatHealth;
    /** Personal style on top of the tier (set randomly, or fixed by a bot type). */
    public double aggression = 1.0;
    /** Added to every swing's reach: negative = likes to fight close, positive = spaces out. */
    public double rangeBias;
    public double strafeScale = 1.0;
    public double reactionScale = 1.0;
    public double accuracyBonus;
    public BotSkill.TargetPref targetPref = BotSkill.TargetPref.SMART;

    public BotSkill(double skill, BotSkill.Personality personality, double pathBias, double timingJitter) {
        this.skill = clamp(skill);
        this.personality = personality;
        this.pathBias = pathBias;
        this.timingJitter = timingJitter;
        this.randomizeTraits();
    }

    public static BotSkill random(double min, double max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BotSkill.Personality[] all = BotSkill.Personality.values();
        BotSkill skill = new BotSkill(r.nextDouble(min, max), all[r.nextInt(all.length)], r.nextDouble(-1.0, 1.0), r.nextDouble(-0.1, 0.14));
        skill.randomizeTraits();
        return skill;
    }

    /** Personal spread around the tier's numbers (two HT4s don't play identically). */
    private double critSpread = 1.0;
    private double shieldSpread = 1.0;

    public void randomizeTraits() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int kind = r.nextInt(10);
        this.hopHabit = kind < 3 ? 0.0 : (kind < 7 ? r.nextDouble(0.15, 0.4) : r.nextDouble(0.6, 0.9));
        this.pace = r.nextDouble(0.9, 1.0);
        this.lane = r.nextDouble(-1.6, 1.6);
        this.curiosity = r.nextDouble(0.0, 1.0);
        this.fidgetiness = r.nextDouble(0.0, 1.0);
        this.critSpread = r.nextDouble(0.85, 1.12);
        this.shieldSpread = r.nextInt(6) == 0 ? 0.3 : r.nextDouble(0.75, 1.25);
        this.retreatHealth = r.nextInt(3) == 0 ? 0.0 : r.nextDouble(3.0, 8.0);
        this.aggression = this.personality.aggression * r.nextDouble(0.85, 1.15);
        this.rangeBias = (r.nextDouble() + r.nextDouble() - 1.0) * 0.3;
        this.strafeScale = r.nextDouble(0.7, 1.3);
        this.reactionScale = r.nextDouble(0.85, 1.15);
        this.accuracyBonus = 0.0;
        this.applyTier();
    }

    /** Crit/shield habits follow the tier table (plus the personal spread). */
    private void applyTier() {
        Tiers.Tier tier = this.tier();
        this.critHabit = Math.min(0.97, tier.crit * this.critSpread);
        this.shieldHabit = Math.min(0.95, tier.shield * this.shieldSpread);
    }

    /** This bot's row of the tier table (blended between tiers). */
    public Tiers.Tier tier() {
        return Tiers.at(this.skill);
    }

    public double wtapChance() {
        return this.tier().wtap;
    }

    public double stunChance() {
        return this.tier().stun;
    }

    public double strafeAmount() {
        return this.tier().strafe * this.strafeScale;
    }

    public double skill() {
        return this.skill;
    }

    public void setSkill(double skill) {
        this.skill = clamp(skill);
        this.applyTier();
    }

    public void setRank(BotSkill.Rank rank) {
        this.skill = rank.skill;
        this.applyTier();
    }

    public BotSkill.Rank rank() {
        return BotSkill.Rank.closest(this.skill);
    }

    public String describe() {
        return Tiers.label(this.skill) + " (" + Math.round(this.skill * 100.0) + "%, " + this.personality.name().toLowerCase(Locale.ROOT)
            + String.format(Locale.ROOT, ", aggro %.2f, reach %+.2f, strafe %.2f, reakt %.2f, ziel %s)", this.aggression, this.rangeBias, this.strafeScale, this.reactionScale, this.targetPref.label);
    }

    public double accuracy() {
        double base = this.tier().accuracy + this.accuracyBonus + ThreadLocalRandom.current().nextDouble(-0.02, 0.02);
        return Math.max(0.5, Math.min(0.99, base));
    }

    /**
     * How close (eye to hitbox) this bot lets an enemy come before it swings, rolled fresh for
     * every swing inside its tier's reach range (mostly around the middle).
     */
    public double swingReach() {
        Tiers.Tier tier = this.tier();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double roll = (r.nextDouble() + r.nextDouble()) / 2.0; // triangular: most swings near the middle
        return Math.max(1.6, Math.min(3.0, tier.reachMin + (tier.reachMax - tier.reachMin) * roll + this.rangeBias));
    }

    /** Reaction scaled by the tier (table value 5 = the base, 3 = 60 % of it). */
    public long reactionTicks(long baseTicks) {
        return Math.max(1L, Math.round(baseTicks * this.tier().reaction / 5.0 * this.reactionScale));
    }

    public long attackCooldown(ItemStack weapon) {
        double base;
        if (weapon.is(ItemTags.AXES)) {
            base = weapon.is(Items.IRON_AXE) ? 22.3 : (!weapon.is(Items.WOODEN_AXE) && !weapon.is(Items.STONE_AXE) ? 20.0 : 25.0);
        } else {
            base = weapon.is(ItemTags.SWORDS) ? 11.2 : 10.0;
        }

        double personal = Math.max(0.0, this.timingJitter * base);
        int slop = (int)Math.round(this.tier().timingSlop);
        int human = ThreadLocalRandom.current().nextInt(0, slop + 1);
        return Math.max(10L, (long)Math.ceil(base + personal + human));
    }

    /** Everything personal (not the tier) - for respawns under a new name. */
    public void copyTraitsFrom(BotSkill other) {
        this.setSkill(other.skill);
        this.personality = other.personality;
        this.hopHabit = other.hopHabit;
        this.pace = other.pace;
        this.lane = other.lane;
        this.curiosity = other.curiosity;
        this.fidgetiness = other.fidgetiness;
        this.critHabit = other.critHabit;
        this.shieldHabit = other.shieldHabit;
        this.retreatHealth = other.retreatHealth;
        this.aggression = other.aggression;
        this.rangeBias = other.rangeBias;
        this.strafeScale = other.strafeScale;
        this.reactionScale = other.reactionScale;
        this.accuracyBonus = other.accuracyBonus;
        this.targetPref = other.targetPref;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Whom a bot prefers when several enemies are in reach. */
    public enum TargetPref {
        SMART("smart"),
        NEAREST("nearest"),
        WEAKEST("weakest"),
        STRONGEST("strongest"),
        THREAT("threat");

        public final String label;

        TargetPref(String label) {
            this.label = label;
        }

        public static BotSkill.TargetPref parse(String value) {
            String v = value.toLowerCase(Locale.ROOT);
            for (BotSkill.TargetPref pref : values()) {
                if (pref.label.equals(v)) {
                    return pref;
                }
            }
            return switch (v) {
                case "naechster", "nah", "near", "closest" -> NEAREST;
                case "schwach", "low", "lowest", "finisher", "schwaechster" -> WEAKEST;
                case "stark", "high", "staerkster", "tank" -> STRONGEST;
                case "angreifer", "attacker", "revenge", "rache" -> THREAT;
                case "auto", "normal", "default" -> SMART;
                default -> null;
            };
        }
    }

    public enum Personality {
        AGGRESSIVE(1.12, 0.85, 0.75),
        BALANCED(1.0, 0.65, 0.55),
        CAUTIOUS(0.88, 0.45, 0.35);

        public final double aggression;
        public final double sprintPreference;
        public final double strafePreference;

        Personality(double aggression, double sprintPreference, double strafePreference) {
            this.aggression = aggression;
            this.sprintPreference = sprintPreference;
            this.strafePreference = strafePreference;
        }

        public static BotSkill.Personality parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "aggressive", "aggressiv", "aggro", "rusher" -> AGGRESSIVE;
                case "balanced", "normal", "ausgeglichen" -> BALANCED;
                case "cautious", "vorsichtig", "careful", "defensive", "defensiv" -> CAUTIOUS;
                default -> null;
            };
        }
    }

    public enum Rank {
        NOOB("Noob", 0.16),
        OKAY("Okay", 0.34),
        LT5("LT5", 0.5),
        HT5("HT5", 0.6),
        LT4("LT4", 0.72),
        HT4("HT4", 0.84),
        LT3("LT3", 0.92),
        HT3("HT3", 1.0);

        public final String label;
        public final double skill;

        Rank(String label, double skill) {
            this.label = label;
            this.skill = skill;
        }

        public static BotSkill.Rank parse(String value) {
            for (BotSkill.Rank rank : values()) {
                if (rank.name().equalsIgnoreCase(value) || rank.label.equalsIgnoreCase(value)) {
                    return rank;
                }
            }

            return null;
        }

        public static BotSkill.Rank closest(double skill) {
            BotSkill.Rank closest = NOOB;

            for (BotSkill.Rank rank : values()) {
                if (Math.abs(rank.skill - skill) < Math.abs(closest.skill - skill)) {
                    closest = rank;
                }
            }

            return closest;
        }
    }
}
