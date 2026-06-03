package top.iencand.translex.client.translate;

public enum SkyBlockTerm {
    // 基础属性
    STRENGTH("Strength", "力量"),
    CRIT_CHANCE("Crit Chance", "暴击率"),
    CRIT_DAMAGE("Crit Damage", "暴击伤害"),
    HEALTH("Health", "生命值"),
    DEFENSE("Defense", "防御力"),
    SPEED("Speed", "速度"),
    INTELLIGENCE("Intelligence", "智力/魔法值"),
    TRUE_DEFENSE("True Defense", "真·防御力"),
    FEROCITY("Ferocity", "残暴"),

    // 采集与特殊
    MAGIC_FIND("Magic Find", "魔法寻找"),
    PET_LUCK("Pet Luck", "宠物幸运"),
    SEA_CREATURE_CHANCE("Sea Creature Chance", "海生生物概率"),
    MINING_SPEED("Mining Speed", "采矿速度"),
    MINING_FORTUNE("Mining Fortune", "采矿幸运"),
    PRISTINE("Pristine", "精纯"),

    // 战斗属性
    ATTACK_SPEED("Bonus Attack Speed", "奖励攻击速度"),
    ABILITY_DAMAGE("Ability Damage", "技能伤害"),
    MENDING("Mending", "治疗效率"),
    VITALITY("Vitality", "生命活力");

    private final String en;
    private final String zh;

    SkyBlockTerm(String en, String zh) {
        this.en = en;
        this.zh = zh;
    }

    // 确保方法名与 TranslationCacheManager 中的调用一致
    public String getEn() {
        return en;
    }

    public String getZh() {
        return zh;
    }

    /**
     * 预读数组，避免在 TranslationCacheManager 中频繁调用 values()
     * 产生数组克隆开销，提升性能。
     */
    public static final SkyBlockTerm[] VALUES = values();
}