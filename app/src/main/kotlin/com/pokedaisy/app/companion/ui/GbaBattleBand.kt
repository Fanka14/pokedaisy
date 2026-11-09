package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor

/*
 * BATTLE BAND (SETTINGS > BATTLE BAND, with BATTLE BUTTONS on): a Gen 3 GBA battle on one screen held sideways, the
 * game's own action menu replaced by one band across the whole screen over the text box's rows (112-160): the game's
 * own "What will ... do?" box at the left, copied from its frame and drawn bigger ([GameBandPixels]), and FIGHT / BAG
 * over POKéMON / RUN as one big 2 x 2 at the right. FIGHT presses the game's FIGHT: the band then turns into the four
 * moves (2 x 2, the move cards' look, type and PP) with BACK at the end. BAG and POKéMON open the game's own screens
 * (the band steps aside). Every pick presses the game's buttons ([BattleInput]); nothing is patched.
 *
 * PokeDaisyActivity shows it instead of BattleButtons when BATTLE BAND is on. ui-preview's gba-battle-band-* shots render it.
 */

/** The game's frame rows where the battle's text box and menus start (tile row 14 in every Gen 3 battle layout). */
const val BAND_TOP_ROW = 112

/** The GBA's frame size. */
private const val GBA_W = 240
private const val GBA_H = 160

/** The action prompt's box in the game's frame (B_WIN_ACTION_PROMPT, tiles 1-14, with its frame tiles 0 and 15). */
private const val PROMPT_W = 128

/**
 * The game's "What will ... do?" box, copied out of its frame between two frames (the emu thread, [capture]) while the
 * band wants it ([wanted]): a [PROMPT_W] x 48 ARGB picture, published only when it changed.
 */
object GameBandPixels {
    @Volatile
    var wanted = false

    private val _pixels = MutableStateFlow<IntArray?>(null)
    val pixels: StateFlow<IntArray?> get() = _pixels

    private var frames = 0

    /** [buf]: the core's RGBX frame, [w] x [h]. Every 4th frame at most; nothing outside a 240 x 160 GBA frame. */
    fun capture(buf: java.nio.ByteBuffer?, w: Int, h: Int) {
        if (!wanted || buf == null || w != GBA_W || h != GBA_H) return
        if (++frames % 4 != 0) return
        val rows = GBA_H - BAND_TOP_ROW
        val px = IntArray(PROMPT_W * rows)
        for (y in 0 until rows) {
            val line = (BAND_TOP_ROW + y) * w
            for (x in 0 until PROMPT_W) {
                // Absolute gets, the byte order EmulatorView uploads (the 4th byte is junk: alpha forced).
                val i = (line + x) * 4
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                px[y * PROMPT_W + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        closeRight(px, rows)
        if (!px.contentEquals(_pixels.value)) _pixels.value = px
    }

    /**
     * The game's prompt box has no right edge of its own: the action menu's frame overlaps it (FireRed at x 120), and
     * [PROMPT_W] took that frame's first columns. Cut where the box's inside ends, close it with its left edge mirrored,
     * and fill what's left with the band's colour (the top-left corner).
     */
    private fun closeRight(px: IntArray, rows: Int) {
        val inner = 7   // the first row inside the frame (BAND_TOP_ROW's 3 band rows, 2 gold, 2 lilac)
        if (rows <= inner + 1) return
        val inside = px[inner * PROMPT_W + PROMPT_W / 4]
        var left = 0
        while (left < PROMPT_W / 4 && px[inner * PROMPT_W + left] != inside) left++
        var edge = PROMPT_W / 4
        while (edge < PROMPT_W && px[inner * PROMPT_W + edge] == inside) edge++
        if (left == 0 || left == PROMPT_W / 4 || edge + left > PROMPT_W) return
        val band = px[0]
        for (y in 0 until rows) {
            val row = y * PROMPT_W
            for (k in 0 until left) px[row + edge + k] = px[row + left - 1 - k]
            for (x in edge + left until PROMPT_W) px[row + x] = band
        }
    }

    /** Forget the last game's picture (a new game, the band turned off). */
    fun clear() {
        _pixels.value = null
    }
}

/**
 * BATTLE BAND's party list, shared with the activity: a controller's A on POKéMON opens it as a tap does, and while it's
 * open the D-pad / A / B steer it instead of the game (PokeDaisyActivity.bandPartyTakes). Set by [GbaBattleBand].
 */
object BandParty {
    val open = androidx.compose.runtime.mutableStateOf(false)
    /** The controller's row: the party, then CANCEL; -1 = the list's own cursor (BEST / OUT), as after a tap. */
    val cursor = androidx.compose.runtime.mutableIntStateOf(-1)
    /** The band shows its actions and POKéMON opens the list (not the game's party screen). */
    @Volatile
    var available = false
    /** The list's rows, CANCEL included. */
    @Volatile
    var rows = 0
    /** Row i picked (the last, CANCEL, closes the list). */
    @Volatile
    var pick: ((Int) -> Unit)? = null

    fun close() {
        open.value = false
        available = false
    }
}

/** What the band shows: nothing, the actions, the moves. */
private const val MODE_NONE = 0
private const val MODE_ACTIONS = 1
private const val MODE_MOVES = 2

/** How long the band stays between two menus (the game's FIGHT opening its moves), so its own menu doesn't flash up. */
private const val HOLD_MS = 180L

/** The band's own colour where the game's picture can't tell it (no frame copied yet). */
private val BandDark = Color(0xFF283040)

/**
 * The band, laid over the whole stage (the HUD's coordinates), [picture] being where the GBA's own 240 x 160 frame is.
 * [shown]: a single battle the game's input can steer, on one screen held sideways. [prompt]: the game's prompt box
 * ([GameBandPixels]), or null for one drawn here (the game's words, the HUD's pixel font).
 */
@Composable
fun GbaBattleBand(
    snapshot: SnapshotView,
    battleInput: BattleInput?,
    picture: Rect?,
    shown: Boolean,
    showHints: Boolean,
    prompt: IntArray? = GameBandPixels.pixels.collectAsState().value,
) {
    val state = snapshot.battleInputState
    val busy = rememberBattleInputBusy(battleInput)
    val mode = when {
        !shown || picture == null || battleInput == null -> MODE_NONE
        state == BATTLE_INPUT_ACTION_SELECT -> MODE_ACTIONS
        state == BATTLE_INPUT_MOVE_SELECT -> MODE_MOVES
        else -> MODE_NONE
    }
    // Held for a moment after its menu goes (FIGHT's moves come up a few frames later), never over the game's own bag
    // or party screens.
    val held = remember { mutableIntStateOf(MODE_NONE) }
    val gameScreen = state == BATTLE_INPUT_BAG_OPEN || state == BATTLE_INPUT_PARTY_OPEN
    LaunchedEffect(mode, gameScreen) {
        if (mode != MODE_NONE) held.intValue = mode
        else if (gameScreen) held.intValue = MODE_NONE
        else { kotlinx.coroutines.delay(HOLD_MS); held.intValue = MODE_NONE }
    }
    val shownMode = if (mode != MODE_NONE) mode else if (shown && !gameScreen) held.intValue else MODE_NONE
    // POKéMON opens PokeDaisy's party list (BattleButtons' own) where the switch works, not the game's party screen; it
    // goes with the action menu (a switch ran, the turn moved on).
    var partyOpen by BandParty.open
    LaunchedEffect(shown, state) { if (!shown || state != BATTLE_INPUT_ACTION_SELECT) partyOpen = false }
    // (With one Pokémon too: the list still shows it, OUT, and CANCEL.)
    val ownParty = battleInput?.canSwitch == true && snapshot.party.isNotEmpty()
    // BAG opens the HUD's BAG, its items used through the game's battle bag (BattleInput.useItem), where that works.
    var bagOpen by remember { mutableStateOf(false) }
    LaunchedEffect(shown, state) { if (!shown || state != BATTLE_INPUT_ACTION_SELECT) bagOpen = false }
    val ownBag = battleInput?.canUseItems == true
    // A controller's A on POKéMON opens the list too, and its D-pad / A / B steer it (PokeDaisyActivity.bandPartyTakes).
    androidx.compose.runtime.SideEffect {
        BandParty.available = ownParty && shownMode == MODE_ACTIONS && picture != null && !busy && mode != MODE_NONE
        BandParty.rows = snapshot.party.size + 1
        BandParty.pick = { i ->
            val mon = snapshot.party.getOrNull(i)
            if (mon == null) partyOpen = false
            else if (canSendIn(mon, snapshot.battlePlayer)) battleInput?.switchTo(mon.personality)
        }
    }
    if (shownMode == MODE_NONE || picture == null) return
    val idle = busy || mode == MODE_NONE
    val moves = snapshot.battlePlayer.getOrNull(0)?.moves.orEmpty().take(4)
    val wild = snapshot.enemyParty.isEmpty()
    val image: ImageBitmap? = remember(prompt) {
        prompt?.let { android.graphics.Bitmap.createBitmap(it, PROMPT_W, GBA_H - BAND_TOP_ROW, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
    }
    // The band's colour: the game's own (its prompt box's top-left corner is the band behind it).
    val bandColor = prompt?.let { Color(it[0]) } ?: BandDark
    val base = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = with(base) { maxWidth.toPx() }
        val gp = picture.height / GBA_H   // screen px a game pixel
        val top = picture.top + BAND_TOP_ROW * gp
        val bandH = picture.bottom - top
        val gap = floor(gp * 3)
        with(base) {
            // The band across the whole screen: taps on it are the band's, never the game's.
            Box(
                Modifier.offset { IntOffset(0, top.toInt()) }.size(maxWidth, bandH.toDp()).background(bandColor)
                    .clickable(remember { MutableInteractionSource() }, null) {},
            )
        }
        val innerH = bandH - 2 * gap
        @Composable
        fun Area(l: Float, r: Float, content: @Composable () -> Unit) {
            with(base) {
                Box(Modifier.offset { IntOffset(l.toInt(), (top + gap).toInt()) }.size((r - l).toDp(), innerH.toDp())) { content() }
            }
        }
        if (shownMode == MODE_ACTIONS) {
            // The prompt box at the game's own shape, as tall as the band allows; the buttons take the rest.
            val promptW = innerH * PROMPT_W / (GBA_H - BAND_TOP_ROW - 2 * 3)
            Area(gap, gap + promptW) {
                if (image != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        // The box's own rows, its frame included: from the band's 3 px margin to 3 px from the bottom.
                        drawImage(
                            image, srcOffset = IntOffset(0, 3), srcSize = IntSize(PROMPT_W, GBA_H - BAND_TOP_ROW - 6),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None,
                        )
                    }
                } else PromptBox(snapshot, innerH)
            }
            Area(2 * gap + promptW, boxW - gap) {
                ActionGrid(snapshot, battleInput, idle, wild, moves.isNotEmpty(), innerH, onBag = {
                    if (ownBag) bagOpen = true else battleInput?.selectAction(1)
                }) {
                    if (ownParty) {
                        BandParty.cursor.intValue = -1
                        partyOpen = true
                    } else battleInput?.selectAction(2)
                }
            }
        } else {
            Area(gap, boxW - gap) {
                MoveGrid2x2(moves, battleInput, idle, showHints, innerH)
            }
        }
        if (partyOpen && shownMode == MODE_ACTIONS) BattlePartyWindow(snapshot, battleInput, busy, showHints) { partyOpen = false }
        // The HUD's POKé BALLs in a wild battle, a column in the top-right corner, one button a kind (the pocket's order):
        // a tap throws that ball, as BAG > the ball > USE would.
        val balls = snapshot.items.filter {
            com.pokedaisy.app.companion.data.basePocket(it.pocket) == com.pokedaisy.app.companion.data.POCKET_POKE_BALLS && it.quantity > 0
        }
        if (ownBag && wild && balls.isNotEmpty() && shownMode == MODE_ACTIONS && !partyOpen && !bagOpen) {
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                balls.forEach { ball ->
                    BallButton(ball.iconAsset, ball.quantity, enabled = !idle, Modifier) {
                        battleInput?.useItem(ball.itemId, BAG_BALLS, null)
                    }
                }
            }
        }
        if (bagOpen && shownMode == MODE_ACTIONS && battleInput != null) {
            val use = remember(battleInput, snapshot) { BandItemUse(snapshot, battleInput) { bagOpen = false } }
            BattleBagPanel(snapshot, use) { bagOpen = false }
        }
    }
}

/** How tall (dp) a move card is drawn as: about its height on the companion's battle screen (ONE-TAP MOVES' two lines). */
private const val MOVE_CARD_DP = 68f

/** A density whose GBA pixel ([rememberGbaTextMetrics]' u) is [k] screen px. */
private fun pixelDensity(k: Int, base: Density) = Density(k * 16f / 20f, base.fontScale)

/** The prompt drawn here (no frame copied yet: the preview, the first moments): the game's words in a pale box. */
@Composable
private fun PromptBox(snapshot: SnapshotView, h: Float) {
    val base = LocalDensity.current
    val battler = snapshot.battlePlayer.getOrNull(0)?.let { b ->
        snapshot.party.firstOrNull { it.species == b.species && it.level == b.level && it.hp == b.hp }?.shownName ?: b.name
    }.orEmpty()
    val k = (h / 64f).toInt().coerceAtLeast(1)
    CompositionLocalProvider(LocalDensity provides pixelDensity(k, base)) {
        val m = rememberGbaTextMetrics(1f)
        val u = m.u
        Box(
            Modifier.fillMaxSize().background(Color(0xFF6B6F8A), PixelRoundedShape(u * 3)).padding(u * 2)
                .background(Color(0xFFF8F8F0), PixelRoundedShape(u * 2)).padding(horizontal = u * 6, vertical = u * 4),
            contentAlignment = Alignment.CenterStart,
        ) {
            GbaText(tr("What will {0} do?", battler), Color(0xFF383848), Color(0xFFD0D0C8), m)
        }
    }
}

/** FIGHT / BAG over POKéMON / RUN, as big as the band's right part. */
@Composable
private fun ActionGrid(
    snapshot: SnapshotView, battleInput: BattleInput?, idle: Boolean, wild: Boolean, hasMoves: Boolean, h: Float, onBag: () -> Unit, onParty: () -> Unit,
) {
    val base = LocalDensity.current
    // Two rows and a gap in the band's height: a GBA pixel of the buttons' art about 1/48th of it.
    val k = (h / 48f).toInt().coerceAtLeast(1)
    CompositionLocalProvider(LocalDensity provides pixelDensity(k, base)) {
        val m = rememberGbaTextMetrics(1f)
        val u = m.u
        val enabled = battleInput != null && !idle
        val active = snapshot.battlePlayer.getOrNull(0)
        // The game's own cursor (a controller steers the menu under the band): its cell gets the white frame.
        val cursor = gameCursor(battleInput)
        @Composable
        fun Cell(index: Int, modifier: Modifier, label: String, fill: Color, frame: Color, on: Boolean, icon: @Composable () -> Unit, pick: () -> Unit) {
            PlatinumButton(
                fill = fill, frame = frame, modifier = modifier.fillMaxHeight().cursorFrame(index == cursor, u), enabled = on, pressesGame = true, outline = u + 1.dp,
                contentPadding = PaddingValues(horizontal = u * 4, vertical = u), onClick = pick,
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 3, Alignment.CenterHorizontally)) {
                    icon()
                    FittedGbaText(label, buttonContent, if (buttonContent == Color.White) Color(0xFF383838) else Color.Transparent, m, Modifier.weight(1f, fill = false))
                }
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(u * 3)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                Cell(0, Modifier.weight(1f), tr("FIGHT"), FightRed, FightRedDark, enabled && hasMoves, { HudSpecies(active?.iconAsset, u * 20) }) { battleInput?.selectAction(0) }
                Cell(1, Modifier.weight(1f), tr("BAG"), BagOrange, BagOrangeDark, enabled, { PixelArt(BAG_ICON, BAG_COLORS, Modifier.size(u * 14)) }, onBag)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                Cell(2, Modifier.weight(1f), tr("POKéMON"), MonGreen, MonGreenDark, enabled, { PixelArt(PixelIcons.pokeBall, POKE_BALL, Modifier.size(u * 14)) }, onParty)
                Cell(3, Modifier.weight(1f), tr("RUN"), RunBlue, RunBlueDark, enabled && wild, { PixelArt(RUN_ICON, RUN_COLORS, Modifier.size(u * 14)) }) { battleInput?.selectAction(3) }
            }
        }
    }
}

/** The four moves 2 x 2 in the move cards' look (name, PP, type, power, BATTLE HINTS' verdict), BACK at the end. */
@Composable
private fun MoveGrid2x2(moves: List<com.pokedaisy.app.companion.data.MoveView>, battleInput: BattleInput?, idle: Boolean, showHints: Boolean, h: Float) {
    val base = LocalDensity.current
    // A move row is 34 GBA pixels of the list's art: two rows and a gap in the band's height.
    val k = (h / (2 * 34f + 3f)).toInt().coerceAtLeast(1)
    CompositionLocalProvider(LocalDensity provides pixelDensity(k, base)) {
        val m = rememberGbaTextMetrics(1f)
        val u = m.u
        val enabled = battleInput != null && !idle
        val cursor = gameCursor(battleInput)
        CompanionBackHandler(enabled = enabled) { battleInput?.back() }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(u * 3)) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                        for (col in 0 until 2) {
                            val i = row * 2 + col
                            // The companion's own move card (type colours, the verdict chips), drawn at the size it has on
                            // the companion's screen: its text is in sp, so a density that makes the card MOVE_CARD_DP tall.
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().cursorFrame(i == cursor, u)) {
                                val cardPx = with(LocalDensity.current) { maxHeight.toPx() }
                                CompositionLocalProvider(LocalDensity provides Density(cardPx / MOVE_CARD_DP, 1f)) {
                                    MoveCard(moves.getOrNull(i), if (enabled) battleInput else null, i, !enabled, showHints, Modifier.fillMaxSize(), compact = true, centered = true)
                                }
                            }
                        }
                    }
                }
            }
            PlatinumButton(
                fill = Color(0xFF6A7080), frame = Color(0xFF3C4250), modifier = Modifier.width(u * 88).fillMaxHeight(),
                enabled = enabled, pressesGame = true, outline = u, contentPadding = PaddingValues(u * 3), onClick = { battleInput?.back() },
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    PixelArt(PixelIcons.back, mapOf('1' to Color.White), Modifier.size(u * 16))
                    FittedGbaText(tr("BACK"), Color.White, Color(0xFF383838), m)
                }
            }
        }
    }
}

/** The game's cursor on the menu that waits (an action's value / a move), -1 where it isn't read. */
@Composable
private fun gameCursor(battleInput: BattleInput?): Int {
    val none = remember { MutableStateFlow(-1) }
    return (battleInput?.menuCursor ?: none).collectAsState().value
}

/** The game's cursor on a band button: a white frame, two GBA pixels, over the button's own. */
private fun Modifier.cursorFrame(on: Boolean, u: androidx.compose.ui.unit.Dp): Modifier =
    if (on) border(u * 2, Color.White, PixelRoundedShape(5.dp)) else this

/** What a FireRed bag item does in a battle, for [BandItemUse] (pret/pokefirered's item ids and battle usage). */
private enum class BattleItemKind { BALL, HEAL, STATUS, HEAL_ALL, REVIVE, AS_IS, ESCAPE }

private fun battleItemKind(itemId: Int): BattleItemKind? = when (itemId) {
    in 1..12 -> BattleItemKind.BALL
    13, 20, 21, 22, 26, 27, 28, 29, 30, 31, 38, 44 -> BattleItemKind.HEAL   // POTION ... BERRY JUICE
    14, 15, 16, 17, 18, 23, 32 -> BattleItemKind.STATUS                      // ANTIDOTE ... FULL HEAL, HEAL POWDER
    19 -> BattleItemKind.HEAL_ALL                                            // FULL RESTORE
    24, 25, 33 -> BattleItemKind.REVIVE                                      // REVIVE, MAX REVIVE, REVIVAL HERB
    in 73..79 -> BattleItemKind.AS_IS                                        // GUARD SPEC., DIRE HIT, the X items
    80, 81 -> BattleItemKind.ESCAPE                                          // POKé DOLL, FLUFFY TAIL
    else -> null
}

/**
 * The HUD BAG's USE in a battle: an item the battle takes ([battleItemKind]) is used through the game's own battle bag
 * ([BattleInput.useItem]); medicine first asks for the Pokémon, from the party the game would let it help. [onStarted]
 * closes the bag once the game has it. Balls and escape items only against a wild Pokémon.
 */
private class BandItemUse(
    private val snapshot: SnapshotView,
    private val battleInput: BattleInput,
    private val onStarted: () -> Unit,
) : com.pokedaisy.app.companion.ItemUse {
    private val wild = snapshot.enemyParty.isEmpty()

    override fun canUse(itemId: Int): Boolean = when (battleItemKind(itemId)) {
        null -> false
        BattleItemKind.BALL, BattleItemKind.ESCAPE -> wild
        else -> true
    }

    override fun marked(itemId: Int): Boolean = canUse(itemId)

    override fun picksMon(itemId: Int): Boolean = when (battleItemKind(itemId)) {
        BattleItemKind.HEAL, BattleItemKind.STATUS, BattleItemKind.HEAL_ALL, BattleItemKind.REVIVE -> true
        else -> false
    }

    override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {
        val pocket = if (battleItemKind(itemId) == BattleItemKind.BALL) BAG_BALLS else BAG_ITEMS
        if (battleInput.useItem(itemId, pocket, null)) onStarted()
        else done(com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW))
    }

    override fun targets(itemId: Int, give: Boolean, done: (com.pokedaisy.app.companion.data.PartyTargets) -> Unit) {
        val kind = battleItemKind(itemId)
        done(
            com.pokedaisy.app.companion.data.PartyTargets(
                com.pokedaisy.app.companion.data.ItemUseResult.USED,
                snapshot.party.mapIndexed { i, mon ->
                    val helps = when (kind) {
                        BattleItemKind.HEAL -> mon.hp in 1 until mon.maxHp
                        BattleItemKind.STATUS -> mon.hp > 0 && mon.status.isNotEmpty()
                        BattleItemKind.HEAL_ALL -> mon.hp > 0 && (mon.hp < mon.maxHp || mon.status.isNotEmpty())
                        BattleItemKind.REVIVE -> mon.hp == 0
                        else -> false
                    }
                    val verdict = when {
                        mon.isEgg -> com.pokedaisy.app.companion.data.SlotVerdict.EGG
                        helps && mon.personality != 0L -> com.pokedaisy.app.companion.data.SlotVerdict.OK
                        else -> com.pokedaisy.app.companion.data.SlotVerdict.NO_EFFECT
                    }
                    com.pokedaisy.app.companion.data.PartyTarget(i, mon.personality, 0, verdict)
                },
            ),
        )
    }

    override fun useOn(
        itemId: Int, target: com.pokedaisy.app.companion.data.PartyTarget, moveIndex: Int,
        done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit,
    ) {
        if (battleInput.useItem(itemId, BAG_ITEMS, target.personality)) onStarted()
        else done(com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW))
    }
}

/** BATTLE BAND's POKé BALL: the ball's picture (the HUD's) and how many are left. */
@Composable
private fun BallButton(icon: String?, count: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val m = rememberGbaTextMetrics(1.5f)
    val u = rememberGbaTextMetrics(1f).u
    PlatinumButton(
        fill = Color(0xFFF8F8F0), frame = Color(0xFFE83028), modifier = modifier.height(u * 45), enabled = enabled, pressesGame = true,
        outline = u, contentPadding = PaddingValues(horizontal = u * 9, vertical = u * 3), onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u * 4)) {
            if (icon != null) HudItemIcon(icon, u * 33, Modifier) else PixelArt(PixelIcons.pokeBall, POKE_BALL, Modifier.size(u * 24))
            GbaText("x$count", Color(0xFF383848), Color(0xFFD0D0C8), m)
        }
    }
}

/** FireRed's battle bag pockets (gBagMenuState.pocket). */
private const val BAG_ITEMS = 0
private const val BAG_BALLS = 2
