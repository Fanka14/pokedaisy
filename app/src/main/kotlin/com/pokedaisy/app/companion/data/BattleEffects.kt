package com.pokedaisy.app.companion.data

/**
 * What lasts several turns in a FireRed-engine battle, read from the game's RAM (pret/pokefirered's symbols, the same in
 * LeafGreen and French LeafGreen): the weather and its turns, each side's screens (gSideStatuses / gSideTimers), and
 * each battler's confusion, binding, Leech Seed (gBattleMons' status2, gStatuses3) and sleep turns (status1).
 */
data class BattleEffects(
    val weather: Weather? = null,
    /** Turns left of a weather that ends by itself; 0 = for good (an ability's). */
    val weatherTurns: Int = 0,
    /** Index 0: the player's side, 1: the foe's. */
    val sides: List<SideEffects> = listOf(SideEffects(), SideEffects()),
    /** The battler on the field per side (0: the player's, 1: the foe's). */
    val battlers: List<BattlerEffects> = listOf(BattlerEffects(), BattlerEffects()),
) {
    val isEmpty: Boolean get() = weather == null && sides.all { it.isEmpty } && battlers.all { it.isEmpty }

    enum class Weather { RAIN, SANDSTORM, SUN, HAIL }

    data class SideEffects(val reflect: Int = 0, val lightScreen: Int = 0, val mist: Int = 0, val safeguard: Int = 0, val spikes: Int = 0) {
        val isEmpty: Boolean get() = reflect == 0 && lightScreen == 0 && mist == 0 && safeguard == 0 && spikes == 0
    }

    data class BattlerEffects(val confused: Boolean = false, val bound: Boolean = false, val leechSeed: Boolean = false, val sleepTurns: Int = 0) {
        val isEmpty: Boolean get() = !confused && !bound && !leechSeed && sleepTurns == 0
    }

    /** Where they are: gBattleWeather, gWishFutureKnock (weatherDuration at +0x28), gSideStatuses, gSideTimers,
     * gStatuses3, gBattleMons. */
    data class Addrs(
        val weather: Long, val wishFutureKnock: Long, val sideStatuses: Long, val sideTimers: Long, val statuses3: Long, val battleMons: Long,
    )

    companion object {
        /** FireRed / LeafGreen (both revisions) and French LeafGreen. */
        val FIRERED = Addrs(
            weather = 0x02023F1CL, wishFutureKnock = 0x02023F20L, sideStatuses = 0x02023DDEL, sideTimers = 0x02023DE4L,
            statuses3 = 0x02023DFCL, battleMons = 0x02023BE4L,
        )

        private const val BATTLE_MON_SIZE = 0x58
        private const val MON_STATUS1 = 0x4C
        private const val MON_STATUS2 = 0x50
        private const val SIDE_TIMER_SIZE = 12

        /** Reads them through [read] (address, length), between frames; null if any read fails. */
        fun read(a: Addrs, read: (Long, Int) -> ByteArray): BattleEffects? = runCatching {
            fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
            val w = u16(read(a.weather, 2), 0)
            val weather = when {
                w and 0x07 != 0 -> Weather.RAIN
                w and 0x18 != 0 -> Weather.SANDSTORM
                w and 0x60 != 0 -> Weather.SUN
                w and 0x80 != 0 -> Weather.HAIL
                else -> null
            }
            val turns = if (weather != null && w and 0x54 == 0) read(a.wishFutureKnock + 0x28, 1)[0].toInt() and 0xFF else 0
            val statuses = read(a.sideStatuses, 4)
            val timers = read(a.sideTimers, 2 * SIDE_TIMER_SIZE)
            val sides = (0..1).map { s ->
                val st = u16(statuses, s * 2)
                val t = s * SIDE_TIMER_SIZE
                SideEffects(
                    reflect = if (st and 0x01 != 0) timers[t].toInt() and 0xFF else 0,
                    lightScreen = if (st and 0x02 != 0) timers[t + 2].toInt() and 0xFF else 0,
                    mist = if (st and 0x100 != 0) timers[t + 4].toInt() and 0xFF else 0,
                    safeguard = if (st and 0x20 != 0) timers[t + 6].toInt() and 0xFF else 0,
                    spikes = if (st and 0x10 != 0) timers[t + 10].toInt() and 0xFF else 0,
                )
            }
            val s3 = read(a.statuses3, 16)
            val mons = read(a.battleMons, 2 * BATTLE_MON_SIZE)
            val battlers = (0..1).map { b ->
                val status1 = u32(mons, b * BATTLE_MON_SIZE + MON_STATUS1)
                val status2 = u32(mons, b * BATTLE_MON_SIZE + MON_STATUS2)
                BattlerEffects(
                    confused = status2 and 0x07L != 0L,
                    bound = status2 and 0xE000L != 0L,
                    leechSeed = u32(s3, b * 4) and 0x04L != 0L,
                    sleepTurns = (status1 and 0x07L).toInt(),
                )
            }
            BattleEffects(weather, turns, sides, battlers)
        }.getOrNull()
    }
}
