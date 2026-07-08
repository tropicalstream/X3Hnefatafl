package com.x3hnefatafl.engine

import com.x3hnefatafl.SettingsStore
import com.x3hnefatafl.audio.Audio
import kotlin.math.max

enum class GameState { MENU, PLAYING, THINKING, OVER }

interface GameHost {
    fun applySettings()
    fun sound(id: Int, pitch: Float = 1f, vol: Float = 1f)
}

/**
 * Drives a game of Hnefatafl against [AI]. The human plays one side (defenders
 * by default — the King's guard). Swipe a cursor, click to lift a piece, dwell
 * on the target and click to commit. The AI plays on a background thread.
 */
class GameEngine(val store: SettingsStore, val host: GameHost) {

    var state = GameState.MENU
        private set
    var settingsOpen = false
        private set
    val settingsMenu = SettingsMenu(this, store)

    val particles = ParticleSystem()

    var board = Board.initial()
        private set
    var humanDefender = true
        private set
    var difficulty = Difficulty.WARRIOR
        private set

    var cursor = THRONE
    var selected = -1
        private set
    var targets = HashSet<Int>()
        private set
    var lastFrom = -1
        private set
    var lastTo = -1
        private set
    var oppFrom = -1
        private set
    var oppTo = -1
        private set

    var cursorSince = 0f
        private set
    val dwellProgress get() = ((time - cursorSince) / DWELL).coerceIn(0f, 1f)
    val moveArmed get() = time - cursorSince >= DWELL

    var invalidMsg: String? = null
        private set
    var invalidIsHint = false
        private set
    var invalidT = 0f
    var statusMsg = ""
        private set
    var resultMsg = ""
        private set

    var speedOn = false
        private set
    var whiteMs = 0L
        private set
    var blackMs = 0L
        private set

    var time = 0f
    var menuDiff = 1
    var thinkPulse = 0f

    private val history = ArrayList<Board>()
    private val ai = AI()
    private var aiGen = 0
    @Volatile private var pendingMove: Move? = null
    @Volatile private var pendingGen = -1

    val humanSide get() = if (humanDefender) P.DEFENDERS else P.ATTACKERS
    val sideToMove get() = board.side
    val isHumanTurn get() = board.side == humanSide && state == GameState.PLAYING

    fun boot() {
        menuDiff = store.difficulty
        val saved = store.savedGame
        if (saved != null && runCatching { resume(saved) }.getOrDefault(false)) return
        toMenu()
    }

    // ------------------------------------------------------------- loop

    fun update(dt: Float) {
        time += dt
        thinkPulse = (thinkPulse + dt) % 1f
        particles.update(dt)
        if (invalidT > 0f) { invalidT -= dt; if (invalidT <= 0f) invalidMsg = null }
        if (settingsOpen) return

        val pm = pendingMove
        if (pm != null && pendingGen == aiGen && state == GameState.THINKING) {
            pendingMove = null
            history.add(board.clone())
            doMove(pm)
            oppFrom = pm.from; oppTo = pm.to
            if (state == GameState.THINKING) {
                state = GameState.PLAYING
                cursor = board.kingSquare().let { if (it >= 0) it else cursor } // focus the King
                cursorSince = time
            }
            if (state != GameState.OVER) persist()
        }

        if (speedOn && (state == GameState.PLAYING || state == GameState.THINKING)) {
            val att = board.attackersToMove()
            if (att) whiteMs = max(0L, whiteMs - (dt * 1000).toLong())
            else blackMs = max(0L, blackMs - (dt * 1000).toLong())
            val low = if (att) whiteMs else blackMs
            if (low in 1..10000 && time % 1f < dt) host.sound(Audio.LOWTIME, 1.2f, 0.6f)
            if ((att && whiteMs <= 0L) || (!att && blackMs <= 0L)) flagFall(att)
        }
    }

    private fun flagFall(attackerFlagged: Boolean) {
        val flaggedSide = if (attackerFlagged) P.ATTACKERS else P.DEFENDERS
        val humanFlagged = flaggedSide == humanSide
        endGame(if (humanFlagged) "Out of time — you lose." else "Your enemy ran out of time. You win!", !humanFlagged)
    }

    // ------------------------------------------------------------- input

    fun cursorMove(dir: Int) {
        if (state != GameState.PLAYING) return
        var r = row(cursor); var c = col(cursor)
        when (dir) { 0 -> r--; 1 -> r++; 2 -> c--; else -> c++ }
        if (onRC(r, c)) { cursor = idx(r, c); cursorSince = time; host.sound(Audio.TICK, 1.4f, 0.5f) }
    }

    fun click() {
        when {
            settingsOpen -> settingsMenu.activate()
            state == GameState.MENU -> startGame()
            state == GameState.PLAYING -> boardClick()
            state == GameState.OVER -> toMenu()
            else -> {}
        }
    }

    private fun boardClick() {
        if (board.side != humanSide) return
        val p = board.sq[cursor]
        if (selected == -1) {
            when {
                p != 0 && P.sideOf(p) == humanSide -> selectSquare(cursor)
                p != 0 -> setInvalid("That's your enemy's piece — pick one of yours.")
                else -> setInvalid("Pick one of your own pieces first.")
            }
            return
        }
        if (cursor == selected) { deselect(); return }
        if (p != 0 && P.sideOf(p) == humanSide) { selectSquare(cursor); return }
        when {
            cursor !in targets -> { setInvalid(explain(selected, cursor)); host.sound(Audio.ILLEGAL) }
            !moveArmed -> { setHint("Rest on the square a moment, then tap to move."); host.sound(Audio.TICK, 0.9f, 0.6f) }
            else -> applyHuman(Move(selected, cursor))
        }
    }

    private fun explain(from: Int, to: Int) = when (val o = board.classify(from, to)) {
        is MoveOutcome.Illegal -> o.reason
        else -> "That move isn't allowed."
    }

    private fun selectSquare(sq: Int) {
        selected = sq
        targets = HashSet(board.movesFrom(sq).map { it.to })
        invalidMsg = null
        cursorSince = time
        host.sound(Audio.SELECT)
    }

    private fun deselect() { selected = -1; targets = HashSet() }

    private fun applyHuman(m: Move) {
        history.add(board.clone())
        oppFrom = -1; oppTo = -1
        deselect()
        doMove(m)
        if (state == GameState.PLAYING) { state = GameState.THINKING; requestAi() }
        if (state != GameState.OVER) persist()
    }

    // --------------------------------------------------------------- move

    private fun doMove(m: Move) {
        val (nb, caps) = board.applyWithCaptures(m)
        board = nb
        lastFrom = m.from; lastTo = m.to
        cursor = m.to; cursorSince = time
        if (caps.isNotEmpty()) {
            host.sound(Audio.CAPTURE)
            for (csq in caps) particles.burst(sqCenterX(csq), sqCenterY(csq), 0xFFFF5040.toInt(), 1.1f)
        } else host.sound(Audio.MOVE)
        evaluatePosition()
    }

    private fun evaluatePosition() {
        val b = board
        when {
            b.kingCaptured() -> {
                val humanWon = humanSide == P.ATTACKERS
                b.kingSquare().let { if (it >= 0) particles.burst(sqCenterX(it), sqCenterY(it), 0xFFFF3030.toInt(), 1.8f) }
                endGame(if (humanWon) "The King is taken — you win!" else "Your King is captured. You lose.", humanWon)
            }
            b.kingOnCorner() -> {
                val humanWon = humanSide == P.DEFENDERS
                b.kingSquare().let { particles.burst(sqCenterX(it), sqCenterY(it), 0xFFFFE070.toInt(), 1.8f) }
                endGame(if (humanWon) "The King has escaped — you win!" else "The King escaped. You lose.", humanWon)
            }
            b.generate().isEmpty() -> {
                val loserSide = b.side
                val humanWon = loserSide != humanSide
                endGame(if (humanWon) "Your enemy is trapped — you win!" else "You have no moves. You lose.", humanWon)
            }
            else -> statusMsg = when {
                b.kingOnCorner() -> ""
                b.kingDistToCorner() <= 2 -> "The King nears escape!"
                b.attackersOnKing() >= 3 -> "The King is nearly surrounded!"
                else -> ""
            }
        }
    }

    private fun endGame(msg: String, humanWon: Boolean) {
        resultMsg = msg
        statusMsg = ""
        state = GameState.OVER
        aiGen++
        pendingMove = null
        store.clearSavedGame()
        if (humanWon) { store.wins++; host.sound(Audio.WIN) } else { store.losses++; host.sound(Audio.LOSE) }
    }

    // ----------------------------------------------------------- AI hook

    private fun requestAi() {
        aiGen++
        val myGen = aiGen
        val snapshot = board.clone()
        val diff = difficulty
        Thread {
            val mv = ai.bestMove(snapshot, diff)
            if (mv != null) { pendingMove = mv; pendingGen = myGen }
        }.start()
    }

    // -------------------------------------------------------- navigation

    fun startGame() {
        humanDefender = store.playerWhite
        difficulty = Difficulty.from(store.difficulty)
        board = Board.initial()
        history.clear()
        deselect()
        lastFrom = -1; lastTo = -1; oppFrom = -1; oppTo = -1
        statusMsg = ""; resultMsg = ""; invalidMsg = null
        cursor = board.kingSquare()
        cursorSince = time
        store.clearSavedGame()
        particles.clear()
        speedOn = store.speedSeconds > 0
        whiteMs = store.speedSeconds * 1000L
        blackMs = store.speedSeconds * 1000L
        settingsOpen = false
        aiGen++
        pendingMove = null
        state = GameState.PLAYING
        host.sound(Audio.SELECT)
        if (board.side != humanSide) { state = GameState.THINKING; requestAi() }
    }

    fun toMenu() {
        state = GameState.MENU
        settingsOpen = false
        aiGen++
        pendingMove = null
        particles.clear()
        menuDiff = store.difficulty
    }

    fun newGame() = startGame()
    fun restart() = startGame()

    fun undo() {
        if (history.isEmpty() || state == GameState.MENU) return
        aiGen++
        pendingMove = null
        var pops = 0
        while (history.isNotEmpty() && pops < 2) {
            board = history.removeAt(history.size - 1)
            pops++
            if (board.side == humanSide) break
        }
        deselect()
        lastFrom = -1; lastTo = -1; oppFrom = -1; oppTo = -1
        resultMsg = ""; statusMsg = ""
        cursor = board.kingSquare().let { if (it >= 0) it else cursor }
        cursorSince = time
        state = GameState.PLAYING
        host.sound(Audio.TICK)
    }

    fun resign() {
        if (state == GameState.PLAYING || state == GameState.THINKING) endGame("You yield the field. You lose.", false)
    }

    // -------------------------------------------------------- settings/UI

    fun doubleTap() {
        settingsOpen = !settingsOpen
        if (settingsOpen) settingsMenu.onOpen()
        host.sound(if (settingsOpen) Audio.SELECT else Audio.TICK)
    }

    fun onBack(): Boolean {
        if (settingsOpen) { doubleTap(); return true }
        return when (state) {
            GameState.PLAYING, GameState.THINKING -> { doubleTap(); true }
            GameState.OVER -> { toMenu(); true }
            else -> false
        }
    }

    fun swipeDir(dir: Int) {
        if (settingsOpen) { settingsMenu.onDir(dir); return }
        when (state) {
            GameState.PLAYING -> cursorMove(dir)
            GameState.MENU -> if (dir == 2 || dir == 3) {
                menuDiff = (menuDiff + (if (dir == 3) 1 else -1)).coerceIn(0, 4)
                store.difficulty = menuDiff
                host.sound(Audio.TICK)
            }
            else -> {}
        }
    }

    fun onAppPause() {
        persist()
        if ((state == GameState.PLAYING || state == GameState.THINKING) && !settingsOpen) {
            settingsOpen = true
            settingsMenu.onOpen()
        }
    }

    private fun setInvalid(msg: String) { invalidMsg = msg; invalidIsHint = false; invalidT = 2.6f }
    private fun setHint(msg: String) { invalidMsg = msg; invalidIsHint = true; invalidT = 1.6f }

    // ------------------------------------------------------- persistence

    private fun serialize(): String {
        val sb = StringBuilder("1|")
        sb.append(board.sq.joinToString(",")).append("|")
        sb.append(board.side).append("|")
        sb.append(if (humanDefender) 1 else 0).append(",").append(difficulty.ordinal).append(",")
            .append(if (speedOn) 1 else 0).append(",").append(whiteMs).append(",").append(blackMs).append("|")
        sb.append(oppFrom).append(",").append(oppTo)
        return sb.toString()
    }

    private fun resume(s: String): Boolean {
        val parts = s.split("|")
        if (parts.size < 5 || parts[0] != "1") return false
        val sqs = parts[1].split(",").map { it.toInt() }
        if (sqs.size != N * N) return false
        val b = Board()
        for (i in 0 until N * N) b.sq[i] = sqs[i]
        b.side = parts[2].toInt()
        val cfg = parts[3].split(",")
        humanDefender = cfg[0] == "1"
        difficulty = Difficulty.from(cfg[1].toInt())
        speedOn = cfg[2] == "1"
        whiteMs = cfg[3].toLong(); blackMs = cfg[4].toLong()
        val opp = parts[4].split(",")
        oppFrom = opp[0].toInt(); oppTo = opp[1].toInt()

        board = b
        history.clear()
        deselect()
        lastFrom = -1; lastTo = -1
        invalidMsg = null; resultMsg = ""; statusMsg = ""
        cursor = board.kingSquare().let { if (it >= 0) it else THRONE }
        cursorSince = time
        aiGen++
        pendingMove = null
        state = if (board.side == humanSide) GameState.PLAYING else GameState.THINKING
        if (state == GameState.THINKING) requestAi()
        return true
    }

    private fun persist() {
        if (state == GameState.PLAYING || state == GameState.THINKING) store.savedGame = serialize()
    }

    // --------------------------------------------------- board geometry

    companion object {
        const val BOARD_X = 135f
        const val BOARD_Y = 70f
        const val SQ = 30f
        const val DWELL = 1.2f
    }

    fun sqCenterX(sq: Int) = BOARD_X + col(sq) * SQ + SQ / 2
    fun sqCenterY(sq: Int) = BOARD_Y + row(sq) * SQ + SQ / 2
}
