package com.x3hnefatafl.engine

import kotlin.math.abs
import kotlin.random.Random

/** Difficulty tiers (Norse-flavored). Depth sets strength; blunder loosens the easy tiers. */
enum class Difficulty(
    val label: String, val depth: Int, val blunderChance: Float,
    val poolMargin: Int, val budgetMs: Long,
) {
    THRALL("1 · Thrall", 2, 0.5f, 260, 900),
    WARRIOR("2 · Warrior", 3, 0.28f, 140, 1400),
    JARL("3 · Jarl", 3, 0.10f, 70, 1900),
    BERSERKER("4 · Berserker", 4, 0.0f, 0, 2400),
    KONUNGR("5 · Konungr", 4, 0.0f, 0, 2900);

    companion object {
        fun from(i: Int) = entries[i.coerceIn(0, entries.size - 1)]
    }
}

class AI {
    @Volatile private var deadline = 0L
    @Volatile private var aborted = false
    private val rng = Random(System.nanoTime())

    fun bestMove(board: Board, diff: Difficulty): Move? {
        val legal = board.generate()
        if (legal.isEmpty()) return null
        if (legal.size == 1) return legal[0]
        deadline = System.currentTimeMillis() + diff.budgetMs
        aborted = false

        var scored = legal.map { it to 0 }
        for (d in 1..diff.depth) {
            val ordered = scored.sortedByDescending { it.second }.map { it.first }
            val res = ArrayList<Pair<Move, Int>>(ordered.size)
            for (m in ordered) {
                if (System.currentTimeMillis() > deadline) { aborted = true; break }
                res.add(m to -search(board.applied(m), d - 1, -INF, INF, 1))
            }
            if (!aborted && res.isNotEmpty()) scored = res
            if (aborted) break
            if (scored.any { abs(it.second) > MATE - 100 }) break
        }

        val best = scored.maxByOrNull { it.second } ?: return legal.random(rng)
        if (diff.blunderChance > 0f && rng.nextFloat() < diff.blunderChance) {
            val pool = scored.filter { best.second - it.second <= diff.poolMargin }
            return (if (pool.isNotEmpty()) pool else scored).random(rng).first
        }
        return scored.filter { it.second == best.second }.random(rng).first
    }

    private fun search(board: Board, depth: Int, a0: Int, beta: Int, ply: Int): Int {
        if (aborted || System.currentTimeMillis() > deadline) { aborted = true; return 0 }
        val s = board.side
        // Terminals (from the side-to-move's perspective).
        if (board.kingCaptured()) return if (s == P.ATTACKERS) MATE - ply else -(MATE - ply)
        if (board.kingOnCorner()) return if (s == P.DEFENDERS) MATE - ply else -(MATE - ply)
        val moves = board.generate()
        if (moves.isEmpty()) return -(MATE - ply)
        if (depth == 0) return evalNega(board)
        order(board, moves)
        var alpha = a0
        var best = -INF
        for (m in moves) {
            val v = -search(board.applied(m), depth - 1, -beta, -alpha, ply + 1)
            if (v > best) best = v
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    /** Order captures and king-escape moves first for better pruning. */
    private fun order(board: Board, moves: List<Move>) {
        (moves as? ArrayList<Move>)?.sortByDescending { m ->
            var s = 0
            if (isCorner(m.to) && board.sq[m.from] == P.KING) s += 5000
            s + board.applyWithCaptures(m).second.size * 100
        }
    }

    /** Static eval from the side-to-move's perspective (negamax); base is attackers' view. */
    private fun evalNega(board: Board): Int {
        var att = board.countAttackers()
        var def = board.countDefenders()
        var score = att * 12 - def * 16
        // King boxed in = good for attackers; free + near a corner = good for defenders.
        val kdist = board.kingDistToCorner()
        score += kdist * 7                     // attackers want the king far from corners
        score += board.attackersOnKing() * 22  // attackers want to crowd the king
        val kfree = kingFreedom(board)
        score -= kfree * 9                      // open king lanes favor the defender
        return score * board.side
    }

    private fun kingFreedom(board: Board): Int {
        val k = board.kingSquare(); if (k < 0) return 0
        var n = 0
        val r = row(k); val c = col(k)
        for (d in arrayOf(intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0))) {
            val nr = r + d[0]; val nc = c + d[1]
            if (onRC(nr, nc) && board.sq[idx(nr, nc)] == 0) n++
        }
        return n
    }

    companion object {
        private const val INF = 1_000_000
        private const val MATE = 30_000
    }
}
