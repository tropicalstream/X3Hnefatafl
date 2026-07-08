package com.x3hnefatafl

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.x3hnefatafl.engine.Difficulty
import com.x3hnefatafl.engine.GameEngine
import com.x3hnefatafl.engine.GameState
import com.x3hnefatafl.engine.P
import com.x3hnefatafl.engine.col
import com.x3hnefatafl.engine.isCorner
import com.x3hnefatafl.engine.row
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Theatrical Hnefatafl rendering on pure black (waveguide = black transparent). */
class Renderer(private val engine: GameEngine, private val store: SettingsStore) {

    private val W = 640f
    private val H = 480f
    private val BX = GameEngine.BOARD_X
    private val BY = GameEngine.BOARD_Y
    private val SQ = GameEngine.SQ

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rf = RectF()
    private val crown = Path()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dark0 = 0xFF141D33.toInt()
    private val dark1 = 0xFF1A2540.toInt()
    private val special = 0xFF3A3018.toInt()   // throne / corner tint
    private val attFill = 0xFFA02828.toInt()
    private val attRim = 0xFF5A1414.toInt()
    private val defFill = 0xFFE9ECF4.toInt()
    private val defRim = 0xFF8892A4.toInt()
    private val gold = 0xFFFFD24A.toInt()
    private val goldDeep = 0xFF9A6E12.toInt()

    fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(Color.BLACK)
        if (w <= 0 || h <= 0) return
        val s = min(w / W, h / H)
        c.save()
        c.translate((w - W * s) / 2f, (h - H * s) / 2f)
        c.scale(s, s)

        if (engine.state == GameState.MENU) drawMenu(c)
        else {
            drawBoard(c)
            drawPieces(c)
            drawOppTrail(c)
            drawHud(c)
            drawParticles(c)
            if (engine.state == GameState.OVER) drawOver(c)
            drawInvalid(c)
        }
        if (engine.settingsOpen) drawSettings(c)
        c.restore()
    }

    // ------------------------------------------------------------- board

    private fun drawBoard(c: Canvas) {
        rf.set(BX - 6f, BY - 6f, BX + 11 * SQ + 6f, BY + 11 * SQ + 6f)
        stroke.strokeWidth = 5f; stroke.color = Color.argb(70, 120, 100, 60)
        c.drawRoundRect(rf, 6f, 6f, stroke)
        stroke.strokeWidth = 2f; stroke.color = Color.argb(200, 200, 170, 110)
        c.drawRoundRect(rf, 6f, 6f, stroke)

        for (i in 0 until 121) {
            val x = BX + col(i) * SQ; val y = BY + row(i) * SQ
            fill.shader = null
            fill.color = if ((row(i) + col(i)) % 2 == 0) dark0 else dark1
            c.drawRect(x, y, x + SQ, y + SQ, fill)
            if (i == 60 || isCorner(i)) {
                fill.color = special
                c.drawRect(x + 1, y + 1, x + SQ - 1, y + SQ - 1, fill)
                // rune mark: a small diamond/cross for throne & escape corners
                stroke.strokeWidth = 1.6f; stroke.color = Color.argb(130, 255, 210, 120)
                val cx = x + SQ / 2; val cy = y + SQ / 2; val r = SQ * 0.26f
                c.drawLine(cx, cy - r, cx + r, cy, stroke)
                c.drawLine(cx + r, cy, cx, cy + r, stroke)
                c.drawLine(cx, cy + r, cx - r, cy, stroke)
                c.drawLine(cx - r, cy, cx, cy - r, stroke)
            }
        }

        if (engine.lastFrom >= 0) { highlight(c, engine.lastFrom, Color.argb(60, 255, 220, 90)); highlight(c, engine.lastTo, Color.argb(80, 255, 220, 90)) }
        if (engine.selected >= 0) {
            highlight(c, engine.selected, Color.argb(110, 90, 200, 255))
            if (store.showLegal) for (t in engine.targets) {
                fill.shader = null; fill.color = Color.argb(140, 120, 200, 255)
                c.drawCircle(engine.sqCenterX(t), engine.sqCenterY(t), 4f, fill)
            }
        }

        // Cursor — colored by the side to move (the "moving" side).
        val cx = BX + col(engine.cursor) * SQ; val cy = BY + row(engine.cursor) * SQ
        val moverAtt = engine.sideToMove == P.ATTACKERS
        val cc = if (moverAtt) intArrayOf(255, 90, 80) else intArrayOf(255, 220, 120)
        val pz = (170 + 80 * sin(engine.time * 5f)).toInt().coerceIn(80, 255)
        rf.set(cx + 2f, cy + 2f, cx + SQ - 2f, cy + SQ - 2f)
        stroke.strokeWidth = 3f; stroke.color = Color.argb(pz, cc[0], cc[1], cc[2])
        c.drawRoundRect(rf, 5f, 5f, stroke)
        if (engine.selected >= 0 && engine.cursor in engine.targets) {
            val ccx = cx + SQ / 2; val ccy = cy + SQ / 2
            rf.set(ccx - SQ * 0.46f, ccy - SQ * 0.46f, ccx + SQ * 0.46f, ccy + SQ * 0.46f)
            stroke.strokeWidth = 3.5f
            if (engine.moveArmed) { stroke.color = Color.argb((200 + 55 * sin(engine.time * 6f)).toInt().coerceIn(120, 255), 120, 240, 150); c.drawArc(rf, -90f, 360f, false, stroke) }
            else { stroke.color = Color.argb(230, 255, 210, 120); c.drawArc(rf, -90f, 360f * engine.dwellProgress, false, stroke) }
        }
    }

    private fun highlight(c: Canvas, i: Int, color: Int) {
        val x = BX + col(i) * SQ; val y = BY + row(i) * SQ
        fill.shader = null; fill.color = color
        c.drawRect(x, y, x + SQ, y + SQ, fill)
    }

    private fun drawPieces(c: Canvas) {
        for (i in 0 until 121) {
            when (engine.board.sq[i]) {
                P.ATT -> drawAttacker(c, engine.sqCenterX(i), engine.sqCenterY(i), SQ * 0.40f)
                P.DEF -> drawDefender(c, engine.sqCenterX(i), engine.sqCenterY(i), SQ * 0.40f)
                P.KING -> drawKing(c, engine.sqCenterX(i), engine.sqCenterY(i), SQ * 0.46f, true)
            }
        }
    }

    private fun drawAttacker(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.shader = null
        fill.color = attRim; c.drawCircle(cx, cy, r, fill)
        fill.color = attFill; c.drawCircle(cx, cy, r * 0.82f, fill)
        // menace: a dark helm ridge
        fill.color = 0xFF4A0F0F.toInt()
        rf.set(cx - r * 0.5f, cy - r * 0.18f, cx + r * 0.5f, cy + r * 0.04f)
        c.drawRect(rf, fill)
        fill.color = Color.argb(60, 255, 180, 170); c.drawCircle(cx - r * 0.26f, cy - r * 0.3f, r * 0.24f, fill)
    }

    private fun drawDefender(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.shader = null
        fill.color = defRim; c.drawCircle(cx, cy, r, fill)
        fill.color = defFill; c.drawCircle(cx, cy, r * 0.82f, fill)
        stroke.strokeWidth = 1.4f; stroke.color = Color.argb(120, 120, 130, 150)
        c.drawCircle(cx, cy, r * 0.5f, stroke)
        fill.color = Color.argb(90, 255, 255, 255); c.drawCircle(cx - r * 0.26f, cy - r * 0.3f, r * 0.24f, fill)
    }

    private fun drawKing(c: Canvas, cx: Float, cy: Float, r: Float, glow: Boolean) {
        if (glow) {
            val gr = r * (2.6f + 0.4f * sin(engine.time * 3f))
            glowPaint.shader = RadialGradient(cx, cy, gr, intArrayOf(Color.argb(150, 255, 220, 120), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, gr, glowPaint)
        }
        fill.shader = null
        fill.color = goldDeep; c.drawCircle(cx, cy, r, fill)
        fill.color = gold; c.drawCircle(cx, cy, r * 0.82f, fill)
        // crown
        crown.reset()
        val s = r * 0.9f
        crown.moveTo(cx - 0.5f * s, cy + 0.22f * s)
        crown.lineTo(cx - 0.5f * s, cy - 0.28f * s)
        crown.lineTo(cx - 0.2f * s, cy + 0.02f * s)
        crown.lineTo(cx, cy - 0.34f * s)
        crown.lineTo(cx + 0.2f * s, cy + 0.02f * s)
        crown.lineTo(cx + 0.5f * s, cy - 0.28f * s)
        crown.lineTo(cx + 0.5f * s, cy + 0.22f * s)
        crown.close()
        fill.color = 0xFFFFF0B0.toInt(); c.drawPath(crown, fill)
        stroke.strokeWidth = 1.2f; stroke.color = goldDeep; c.drawPath(crown, stroke)
    }

    private fun drawOppTrail(c: Canvas) {
        if (engine.oppFrom < 0 || engine.oppTo < 0) return
        val x1 = engine.sqCenterX(engine.oppFrom); val y1 = engine.sqCenterY(engine.oppFrom)
        val x2 = engine.sqCenterX(engine.oppTo); val y2 = engine.sqCenterY(engine.oppTo)
        val dx = x2 - x1; val dy = y2 - y1; val len = hypot(dx, dy)
        val n = (len / 12f).toInt().coerceAtLeast(2)
        fill.shader = null
        for (i in 1 until n) {
            val t = i.toFloat() / n
            val tw = sin(engine.time * 4f - i * 0.5f) * 0.5f + 0.5f
            fill.color = Color.argb((110 + 120 * tw).toInt().coerceIn(60, 255), 255, 150, 100)
            c.drawCircle(x1 + dx * t, y1 + dy * t, 2f + tw * 1.2f, fill)
        }
        val ang = atan2(dy, dx); val ah = 8f
        crown.reset(); crown.moveTo(x2, y2)
        crown.lineTo(x2 - ah * cos(ang - 0.42f), y2 - ah * sin(ang - 0.42f))
        crown.lineTo(x2 - ah * cos(ang + 0.42f), y2 - ah * sin(ang + 0.42f)); crown.close()
        fill.color = Color.argb(235, 255, 165, 110); c.drawPath(crown, fill)
    }

    // --------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        // Turn indicator — the icon switches to whoever is moving.
        val att = engine.sideToMove == P.ATTACKERS
        val thinking = engine.state == GameState.THINKING
        val label: String; val color: Int
        when {
            thinking -> { label = "${engine.difficulty.label} musters…"; color = Color.argb(255, 255, 190, 120) }
            engine.isHumanTurn -> { label = "Your move"; color = Color.argb(255, 150, 235, 170) }
            else -> { label = if (att) "Attackers move" else "Defenders move"; color = Color.argb(255, 220, 220, 235) }
        }
        if (engine.state != GameState.OVER) {
            if (att) drawAttacker(c, 40f, 34f, 11f) else drawKing(c, 40f, 34f, 12f, false)
            text(c, label, 58f, 39f, 15f, color, Paint.Align.LEFT, glow = Color.argb(90, 60, 120, 200))
        }

        // Side rosters.
        drawAttacker(c, 560f, 96f, 11f)
        text(c, "×${engine.board.countAttackers()}", 578f, 101f, 15f, Color.WHITE, Paint.Align.LEFT)
        text(c, "the host", 560f, 120f, 10f, Color.argb(200, 220, 160, 160), Paint.Align.LEFT)
        if (engine.speedOn) drawClock(c, 585f, 146f, engine.whiteMs, att)

        drawKing(c, 560f, 250f, 12f, false)
        text(c, "×${engine.board.countDefenders() + 1}", 578f, 255f, 15f, Color.WHITE, Paint.Align.LEFT)
        text(c, "the King's men", 560f, 274f, 10f, Color.argb(200, 210, 210, 235), Paint.Align.LEFT)
        if (engine.speedOn) drawClock(c, 585f, 300f, engine.blackMs, !att)

        if (engine.statusMsg.isNotEmpty())
            text(c, engine.statusMsg, W / 2f, 452f, 13f, Color.argb((160 + 90 * sin(engine.time * 5f)).toInt().coerceIn(70, 255), 255, 200, 120))
        text(c, "W ${store.wins}  L ${store.losses}", 68f, 452f, 10f, Color.argb(200, 180, 180, 200), Paint.Align.LEFT)
    }

    private fun drawClock(c: Canvas, cx: Float, y: Float, ms: Long, active: Boolean) {
        val sec = (ms / 1000).toInt(); val low = ms in 1..10000
        val col = when {
            low -> Color.argb((150 + 100 * sin(engine.time * 8f)).toInt().coerceIn(60, 255), 255, 80, 80)
            active -> Color.argb(255, 255, 240, 180); else -> Color.argb(200, 170, 195, 230)
        }
        text(c, "%d:%02d".format(sec / 60, sec % 60), cx, y, 15f, col)
    }

    // -------------------------------------------------------- overlays

    private fun drawInvalid(c: Canvas) {
        val msg = engine.invalidMsg ?: return
        val a = (engine.invalidT / 2.6f).coerceIn(0f, 1f); val alpha = (min(1f, a * 3f) * 255).toInt()
        val hint = engine.invalidIsHint
        rf.set(150f, 458f, 490f, 478f)
        fill.shader = null
        fill.color = if (hint) Color.argb((alpha * 0.85f).toInt(), 54, 44, 16) else Color.argb((alpha * 0.85f).toInt(), 60, 20, 24)
        c.drawRoundRect(rf, 10f, 10f, fill)
        text(c, msg, 320f, 473f, 12f, if (hint) Color.argb(alpha, 255, 230, 180) else Color.argb(alpha, 255, 210, 200))
    }

    private fun drawOver(c: Canvas) {
        dim(c, 150)
        panel(c, 120f, 170f, 520f, 320f)
        val win = engine.resultMsg.contains("win", true)
        val col = if (win) Color.argb(255, 255, 214, 90) else Color.argb(255, 255, 120, 120)
        text(c, if (win) "VICTORY" else "DEFEAT", 320f, 218f, 32f, col, glow = Color.argb(150, 120, 70, 20))
        text(c, engine.resultMsg, 320f, 256f, 13f, Color.argb(255, 220, 224, 240))
        text(c, "TAP FOR MENU", 320f, 298f, 15f, Color.argb((170 + 85 * sin(engine.time * 4f)).toInt().coerceIn(60, 255), 210, 220, 245))
    }

    // -------------------------------------------------- theatrical menu

    private fun drawMenu(c: Canvas) {
        val cx = 320f; val cy = 158f
        // Massive ring of the dark-red host.
        val nAtt = 22; val ra = 128f; val a0 = engine.time * 0.12f
        for (k in 0 until nAtt) {
            val ang = a0 + k * (6.2832f / nAtt)
            drawAttacker(c, cx + cos(ang) * ra, cy + sin(ang) * ra * 0.62f, 9f)
        }
        // Small ring of white guards.
        val nDef = 8; val rd = 54f; val a1 = -engine.time * 0.22f
        for (k in 0 until nDef) {
            val ang = a1 + k * (6.2832f / nDef)
            drawDefender(c, cx + cos(ang) * rd, cy + sin(ang) * rd * 0.62f, 8f)
        }
        // The King, ablaze at the centre.
        drawKing(c, cx, cy, 22f, true)

        textP.setShadowLayer(18f, 0f, 0f, Color.argb(200, 180, 120, 40))
        text(c, "HNEFATAFL", 320f, 262f, 44f, 0xFFFFE7B0.toInt())
        textP.clearShadowLayer()
        text(c, "the King must run · the host must close in", 320f, 288f, 12f, Color.argb(220, 210, 180, 150))

        val d = Difficulty.from(engine.menuDiff)
        text(c, "‹  ${d.label}  ›", 320f, 330f, 22f, Color.WHITE, glow = Color.argb(140, 120, 80, 30))
        text(c, "playing as ${if (store.playerWhite) "Defender (the King)" else "Attacker (the host)"}", 320f, 354f, 12f, Color.argb(255, 255, 214, 120))
        text(c, "victories  ${store.wins}", 320f, 376f, 12f, Color.argb(220, 210, 220, 240))

        text(c, "swipe ↔ difficulty   •   tap to begin", 320f, 414f, 13f, Color.argb((170 + 85 * sin(engine.time * 3f)).toInt().coerceIn(60, 255), 210, 220, 245))
        text(c, "double-tap for settings", 320f, 436f, 11f, Color.argb(160, 170, 175, 200))
    }

    // --------------------------------------------------------- settings

    private fun drawSettings(c: Canvas) {
        dim(c, 188)
        panel(c, 138f, 34f, 502f, 446f)
        text(c, "SETTINGS", 320f, 64f, 20f, Color.WHITE, glow = Color.argb(160, 120, 80, 30))
        val menu = engine.settingsMenu
        val visible = 10
        val start = (menu.selected - visible / 2).coerceIn(0, (menu.items.size - visible).coerceAtLeast(0))
        var y = 96f
        for (i in start until min(start + visible, menu.items.size)) {
            val item = menu.items[i]; val sel = i == menu.selected
            if (sel) { fill.shader = null; fill.color = Color.argb(210, 60, 50, 30); rf.set(150f, y - 15f, 490f, y + 8f); c.drawRoundRect(rf, 8f, 8f, fill) }
            text(c, item.label, 166f, y, 13f, if (sel) Color.WHITE else Color.argb(255, 190, 180, 165), Paint.Align.LEFT)
            val v = item.value()
            if (v.isNotEmpty()) {
                val shown = if (sel && item.adjust != null) "‹ $v ›" else v
                text(c, shown, 474f, y, 13f, if (sel) Color.argb(255, 255, 224, 128) else Color.argb(255, 165, 150, 130), Paint.Align.RIGHT)
            }
            y += 33f
        }
        if (start > 0) text(c, "▲", 320f, 86f, 10f, Color.argb(180, 200, 180, 150))
        if (start + visible < menu.items.size) text(c, "▼", 320f, 430f, 10f, Color.argb(180, 200, 180, 150))
        text(c, "swipe ↕ select   ↔ adjust   tap OK   double-tap close", 320f, 462f, 10.5f, Color.argb(200, 190, 175, 150))
    }

    // ------------------------------------------------------ fx & helpers

    private fun drawParticles(c: Canvas) {
        for (pt in engine.particles.list) {
            val k = (pt.life / pt.maxLife).coerceIn(0f, 1f); val alpha = (k * 255).toInt()
            if (pt.ring) { stroke.strokeWidth = 1.5f + 3f * k; stroke.color = pt.color; stroke.alpha = alpha; c.drawCircle(pt.x, pt.y, pt.size * (1f + (1f - k) * 2f), stroke) }
            else { fill.shader = null; fill.color = pt.color; fill.alpha = alpha; c.drawCircle(pt.x, pt.y, pt.size * (0.4f + 0.6f * k), fill) }
        }
        stroke.alpha = 255; fill.alpha = 255
    }

    private fun dim(c: Canvas, a: Int) { fill.shader = null; fill.color = Color.argb(a, 0, 0, 0); c.drawRect(0f, 0f, W, H, fill) }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rf.set(l, t, r, b); fill.shader = null; fill.color = Color.argb(238, 18, 16, 12)
        c.drawRoundRect(rf, 16f, 16f, fill)
        stroke.strokeWidth = 2f; stroke.color = Color.argb(200, 180, 140, 80); c.drawRoundRect(rf, 16f, 16f, stroke)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, glow: Int = 0) {
        textP.textSize = size; textP.textAlign = align; textP.color = color
        if (glow != 0) textP.setShadowLayer(size * 0.4f, 0f, 0f, glow) else textP.clearShadowLayer()
        c.drawText(s, x, y, textP)
        textP.clearShadowLayer()
    }
}
