package com.pokedaisy.app.companion.data

import kotlin.math.sqrt

/**
 * Gen 3's own battle sums, for BATTLE BAND: a ball's chance to catch (battle_script_commands.c's Cmd_handleballthrow)
 * and a move's damage range (CalculateBaseDamage), on vanilla FireRed / LeafGreen / Emerald's rules. Integer maths as
 * the game does it, so the numbers are the game's.
 */
object BattleMath {
    /**
     * The chance (0..100) that [ballId] catches a foe of [catchRate] at [hp] / [maxHp] with [status] ([statusLabel]'s
     * text), [foeTypes] its types (Net Ball), [foeLevel] its level (Nest Ball). Null = not a ball the sum knows.
     */
    fun catchChance(ballId: Int, catchRate: Int, hp: Int, maxHp: Int, status: String, foeTypes: List<Int>, foeLevel: Int): Int? {
        if (maxHp <= 0 || catchRate <= 0) return null
        // The ball's multiplier, in tenths (gBallMultipliers and the special balls' rules).
        val tenths = when (ballId) {
            BALL_MASTER -> return 100
            BALL_ULTRA -> 20
            BALL_GREAT, BALL_SAFARI -> 15
            BALL_POKE, BALL_LUXURY, BALL_PREMIER, BALL_DIVE, BALL_REPEAT, BALL_TIMER -> 10
            BALL_NET -> if (foeTypes.any { it == TYPE_WATER || it == TYPE_BUG }) 30 else 10
            BALL_NEST -> if (foeLevel < 40) (40 - foeLevel).coerceAtLeast(10) else 10
            else -> return null
        }
        var odds = (catchRate * tenths / 10) * (maxHp * 3 - hp.coerceIn(0, maxHp) * 2) / (3 * maxHp)
        when (status) {
            "SLP", "FRZ" -> odds *= 2
            "PSN", "TOX", "BRN", "PAR" -> odds = odds * 15 / 10
        }
        if (odds > 254) return 100
        if (odds <= 0) return 0
        // Four shake checks, each a 16-bit roll under 1048560 / sqrt(sqrt(16711680 / odds)).
        val shake = 1048560 / sqrt(sqrt(16711680.0 / odds)).toInt()
        val p = (shake / 65536.0).coerceAtMost(1.0)
        return (p * p * p * p * 100).toInt().coerceIn(0, 100)
    }

    /**
     * A move's damage as a share of the foe's [foeMaxHp], lowest and highest roll (percent), or null for a move without
     * power. [attack] / [defense] are the battlers' stats on the move's side (physical: Attack / Defense, special:
     * Sp. Atk / Sp. Def - Gen 3 splits by type), with their [attackStage] / [defenseStage]; [stab] its user shares its
     * type; [effectPct] the type chart's (100 = 1x).
     */
    fun damageRange(
        level: Int, power: Int, attack: Int, defense: Int, attackStage: Int, defenseStage: Int,
        stab: Boolean, effectPct: Int, foeMaxHp: Int,
    ): IntRange? {
        if (power <= 0 || foeMaxHp <= 0 || defense <= 0 || effectPct <= 0) return null
        val a = staged(attack, attackStage)
        val d = staged(defense, defenseStage).coerceAtLeast(1)
        var base = a * power * (2 * level / 5 + 2) / d / 50 + 2
        if (stab) base = base * 15 / 10
        base = base * effectPct / 100
        val low = (base * 85 / 100).coerceAtLeast(1)
        return (low * 100 / foeMaxHp)..(base * 100 / foeMaxHp)
    }

    /** Whether a move of [type] runs on Attack / Defense (Gen 3: by type; the ones before ??? are physical). */
    fun physical(type: Int): Boolean = type in 0..8

    /** A stat at its stage (gStatStageRatios: 10/40 ... 40/10). */
    private fun staged(stat: Int, stage: Int): Int {
        val s = stage.coerceIn(-6, 6)
        return if (s >= 0) stat * (2 + s) / 2 else stat * 2 / (2 - s)
    }

    const val BALL_MASTER = 1
    const val BALL_ULTRA = 2
    const val BALL_GREAT = 3
    const val BALL_POKE = 4
    const val BALL_SAFARI = 5
    const val BALL_NET = 6
    const val BALL_DIVE = 7
    const val BALL_NEST = 8
    const val BALL_REPEAT = 9
    const val BALL_TIMER = 10
    const val BALL_LUXURY = 11
    const val BALL_PREMIER = 12
    private const val TYPE_BUG = 6
    private const val TYPE_WATER = 11
}
