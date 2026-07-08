package com.x3hnefatafl.engine

import kotlin.math.abs
import kotlin.math.min

/**
 * Hnefatafl — Viking chess, 11x11 Fetlar rules.
 *   Attackers (24, dark red) besiege from the edges and move first.
 *   Defenders (12 white guards + the King) start ringed around the throne.
 *   All pieces move orthogonally any distance (like a rook), no jumping.
 *   Only the King may stop on the throne (centre) or the four corners.
 *   Capture is custodial: sandwich an enemy soldier between two of yours (a
 *   corner or the empty throne counts as "yours"). The King is captured only
 *   when surrounded on all four sides by attackers / the throne (edges are
 *   safe). Defenders win if the King reaches a corner; attackers win if they
 *   capture him.
 */
object P {
    const val EMPTY = 0
    const val ATT = 1     // attacker soldier
    const val DEF = 2     // defender soldier
    const val KING = 3
    const val ATTACKERS = 1
    const val DEFENDERS = -1
    fun sideOf(piece: Int) = if (piece == ATT) ATTACKERS else DEFENDERS
}

const val N = 11
const val THRONE = 60 // (5,5)
val CORNERS = intArrayOf(0, 10, 110, 120)

fun row(i: Int) = i / N
fun col(i: Int) = i % N
fun idx(r: Int, c: Int) = r * N + c
fun onRC(r: Int, c: Int) = r in 0 until N && c in 0 until N
fun isCorner(i: Int) = i == 0 || i == 10 || i == 110 || i == 120

data class Move(val from: Int, val to: Int)

sealed class MoveOutcome {
    class Legal(val move: Move) : MoveOutcome()
    class Illegal(val reason: String) : MoveOutcome()
}

private val DIRS = arrayOf(intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0))

class Board {
    val sq = IntArray(N * N)
    var side = P.ATTACKERS

    fun clone(): Board {
        val b = Board()
        System.arraycopy(sq, 0, b.sq, 0, sq.size)
        b.side = side
        return b
    }

    fun attackersToMove() = side == P.ATTACKERS
    private fun mine(p: Int, side: Int) = p != 0 && P.sideOf(p) == side

    fun kingSquare(): Int {
        for (i in sq.indices) if (sq[i] == P.KING) return i
        return -1
    }

    fun countAttackers(): Int { var n = 0; for (p in sq) if (p == P.ATT) n++; return n }
    fun countDefenders(): Int { var n = 0; for (p in sq) if (p == P.DEF) n++; return n }

    // ------------------------------------------------------- move generation

    fun generate(): MutableList<Move> {
        val moves = ArrayList<Move>(64)
        for (i in sq.indices) {
            val p = sq[i]
            if (p == 0 || P.sideOf(p) != side) continue
            movesFrom(i, moves)
        }
        return moves
    }

    fun movesFrom(i: Int): List<Move> {
        val out = ArrayList<Move>(16)
        movesFrom(i, out)
        return out
    }

    private fun movesFrom(i: Int, out: MutableList<Move>) {
        val p = sq[i]
        if (p == 0) return
        val king = p == P.KING
        val r = row(i); val c = col(i)
        for (d in DIRS) {
            var nr = r + d[0]; var nc = c + d[1]
            while (onRC(nr, nc)) {
                val j = idx(nr, nc)
                if (sq[j] != 0) break                 // blocked, no jumping
                val restricted = j == THRONE || isCorner(j)
                if (!restricted) {
                    out.add(Move(i, j))
                } else {
                    if (king) out.add(Move(i, j))      // king may land on throne/corner
                    if (isCorner(j) && !king) break     // soldier can't land on or pass a corner
                    // empty throne: passable by anyone, landing only for the king
                }
                nr += d[0]; nc += d[1]
            }
        }
    }

    // -------------------------------------------------------------- applying

    /** Play a move, resolving custodial captures; returns new board + captured squares. */
    fun applyWithCaptures(m: Move): Pair<Board, List<Int>> {
        val b = clone()
        val piece = b.sq[m.from]
        b.sq[m.from] = 0
        b.sq[m.to] = piece
        val s = P.sideOf(piece)
        val caps = ArrayList<Int>(4)
        val r = row(m.to); val c = col(m.to)
        for (d in DIRS) {
            val yr = r + d[0]; val yc = c + d[1]
            if (!onRC(yr, yc)) continue
            val y = idx(yr, yc)
            val yp = b.sq[y]
            if (yp == 0 || P.sideOf(yp) == s || yp == P.KING) continue // enemy soldier only
            val zr = yr + d[0]; val zc = yc + d[1]
            if (flanks(b, zr, zc, s)) { b.sq[y] = 0; caps.add(y) }
        }
        b.side = -b.side
        return b to caps
    }

    fun applied(m: Move): Board = applyWithCaptures(m).first

    private fun flanks(b: Board, zr: Int, zc: Int, s: Int): Boolean {
        if (!onRC(zr, zc)) return false
        val z = idx(zr, zc)
        if (isCorner(z)) return true
        if (z == THRONE && b.sq[THRONE] == 0) return true // empty throne is hostile
        val zp = b.sq[z]
        return zp != 0 && P.sideOf(zp) == s
    }

    // ------------------------------------------------------------ terminals

    fun kingOnCorner(): Boolean {
        val k = kingSquare()
        return k >= 0 && isCorner(k)
    }

    /** King captured: all four orthogonal sides are attackers or the throne (edges are safe). */
    fun kingCaptured(): Boolean {
        val k = kingSquare()
        if (k < 0) return true
        val r = row(k); val c = col(k)
        for (d in DIRS) {
            val nr = r + d[0]; val nc = c + d[1]
            if (!onRC(nr, nc)) return false          // board edge — safe
            val j = idx(nr, nc)
            if (sq[j] == P.ATT) continue
            if (j == THRONE && k != THRONE) continue  // empty throne is hostile to the king
            return false
        }
        return true
    }

    /** Attackers directly orthogonally adjacent to the king (for eval/HUD tension). */
    fun attackersOnKing(): Int {
        val k = kingSquare(); if (k < 0) return 0
        var n = 0
        val r = row(k); val c = col(k)
        for (d in DIRS) {
            val nr = r + d[0]; val nc = c + d[1]
            if (onRC(nr, nc) && sq[idx(nr, nc)] == P.ATT) n++
        }
        return n
    }

    fun kingDistToCorner(): Int {
        val k = kingSquare(); if (k < 0) return 0
        var best = 99
        for (csq in CORNERS) best = min(best, abs(row(k) - row(csq)) + abs(col(k) - col(csq)))
        return best
    }

    // ------------------------------------------------------- explain a move

    fun classify(from: Int, to: Int): MoveOutcome {
        val p = sq[from]
        if (p == 0) return MoveOutcome.Illegal("There's no piece there.")
        if (P.sideOf(p) != side) return MoveOutcome.Illegal("That's not your piece to move.")
        val legal = movesFrom(from).firstOrNull { it.to == to }
        if (legal != null) return MoveOutcome.Legal(legal)
        if (row(from) != row(to) && col(from) != col(to))
            return MoveOutcome.Illegal("Pieces move in straight lines, like a rook.")
        if ((to == THRONE || isCorner(to)) && p != P.KING)
            return MoveOutcome.Illegal("Only the King may enter the throne or a corner.")
        if (sq[to] != 0) return MoveOutcome.Illegal("That square is occupied.")
        return MoveOutcome.Illegal("The path is blocked.")
    }

    companion object {
        private val ATT_START = intArrayOf(
            idx(0, 3), idx(0, 4), idx(0, 5), idx(0, 6), idx(0, 7), idx(1, 5),
            idx(10, 3), idx(10, 4), idx(10, 5), idx(10, 6), idx(10, 7), idx(9, 5),
            idx(3, 0), idx(4, 0), idx(5, 0), idx(6, 0), idx(7, 0), idx(5, 1),
            idx(3, 10), idx(4, 10), idx(5, 10), idx(6, 10), idx(7, 10), idx(5, 9),
        )
        private val DEF_START = intArrayOf(
            idx(5, 3), idx(5, 4), idx(5, 6), idx(5, 7),
            idx(3, 5), idx(4, 5), idx(6, 5), idx(7, 5),
            idx(4, 4), idx(6, 4), idx(4, 6), idx(6, 6),
        )

        fun initial(): Board {
            val b = Board()
            for (i in ATT_START) b.sq[i] = P.ATT
            for (i in DEF_START) b.sq[i] = P.DEF
            b.sq[THRONE] = P.KING
            b.side = P.ATTACKERS
            return b
        }
    }
}
