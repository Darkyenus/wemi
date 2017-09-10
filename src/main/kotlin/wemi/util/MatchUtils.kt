package wemi.util

import java.util.*

/**
 *
 */
object MatchUtils {

    /** @param s0 text user entered
     * @param s1 text of result
     */
    fun levenshteinDistance(s0: CharSequence, s1: CharSequence, insertCost: Int, replaceCost: Int, deleteCost: Int): Int {
        val len0 = s0.length + 1
        val len1 = s1.length + 1

        // the array of distances
        var cost = IntArray(len0)
        var newCost = IntArray(len0)

        // initial cost of skipping prefix in String s0
        for (i in 0 until len0)
            cost[i] = i * deleteCost

        // dynamically computing the array of distances

        // transformation cost for each letter in s1
        for (i1 in 1 until len1) {
            // initial cost of skipping prefix in String s1
            newCost[0] = i1 * insertCost

            // transformation cost for each letter in s0
            for (i0 in 1 until len0) {
                // matching current letters in both strings
                val match = if (s0[i0 - 1] == s1[i1 - 1]) 0 else replaceCost

                // computing cost for each transformation
                val cost_insert = cost[i0] + insertCost //          \/
                val cost_replace = cost[i0 - 1] + match //          _|
                val cost_delete = newCost[i0 - 1] + deleteCost //   >

                // keep minimum cost
                newCost[i0] = min(cost_insert, cost_delete, cost_replace)
            }

            // swap cost/newCost arrays
            val swap = cost
            cost = newCost
            newCost = swap
        }

        // the distance is the cost for transforming all letters in both strings
        return cost[len0 - 1]
    }

    private fun min(a: Int, b: Int, c: Int): Int {
        if (a <= b && a <= c) return a
        return if (b <= a && b <= c) b else c
    }

    fun contentEquals(a: CharSequence?, b: CharSequence?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        val length = a.length
        if (length != b.length) return false
        for (i in 0 until length) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    inline fun <reified T> match(from: Array<T>, noinline toString: (T) -> CharSequence, target: CharSequence): MatchResult<T> {
        return match(T::class.java, from, toString, target)
    }

    fun <T> match(type:Class<T>, from: Array<T>, toString: (T) -> CharSequence, target: CharSequence): MatchResult<T> {
        val BAD_SCORE = 1000

        //Insert only search
        var considerOnlyPerfectMatches = false
        val scores = IntArray(from.size)
        var goodScores = 0
        for (i in from.indices) {
            val item = from[i]
            val itemName = toString(item)
            if (considerOnlyPerfectMatches) {
                if (contentEquals(target, itemName)) {
                    scores[i] = 0
                    goodScores++
                } else {
                    scores[i] = BAD_SCORE
                }
            } else {
                val score = levenshteinDistance(target, itemName, 1, BAD_SCORE, BAD_SCORE)
                if (score == 0) {
                    //Perfect match, continue searching, there may be dupes
                    considerOnlyPerfectMatches = true
                }
                if (score < BAD_SCORE) {
                    goodScores++
                }
                scores[i] = score
            }
        }

        if (goodScores == 1) {
            //Clear winner however bad it may be
            for (i in from.indices) {
                if (scores[i] < BAD_SCORE) {
                    return MatchResult(from[i], type)
                }
            }
            assert(false)
        }

        var findCanBeUnambiguous = true

        if (goodScores == 0) {
            //No good scores, search again with weights allowing other edits than adding
            findCanBeUnambiguous = false
            for (i in from.indices) {
                scores[i] = levenshteinDistance(target, toString(from[i]), 1, 6, 3)
            }
        }

        var bestScore = BAD_SCORE
        var bestScoreAmbiguityThreshold = BAD_SCORE
        var bestScoreIndex = -1
        var bestScoreIsUnambiguous = false

        for (i in from.indices) {
            val score = scores[i]
            if (score < bestScore) {
                bestScoreAmbiguityThreshold = bestScore + Math.max(bestScore / 3, 2)
                bestScoreIsUnambiguous = bestScore < bestScoreAmbiguityThreshold
                bestScore = score
                bestScoreIndex = i
            } else if (score < bestScoreAmbiguityThreshold) {
                bestScoreIsUnambiguous = false
            }
        }

        if (bestScoreIsUnambiguous && findCanBeUnambiguous) {
            return MatchResult(from[bestScoreIndex], type)
        } else {
            val results = ArrayList<MatchResultItem>()
            for (i in from.indices) {
                val score = scores[i]
                if (score < BAD_SCORE) {
                    results.add(MatchResultItem(i, score))
                }
            }
            Collections.sort<MatchResultItem>(results)

            val resultItems = Math.min(8, results.size)

            @Suppress("UNCHECKED_CAST")
            val resultArray = java.lang.reflect.Array.newInstance(type, resultItems) as Array<T>
            for (i in 0 until resultItems) {
                resultArray[i] = from[results[i].index]
            }
            return MatchResult(resultArray)
        }
    }

    class MatchResult<T> {

        val isDefinite: Boolean
        val results: Array<T>

        internal constructor(results: Array<T>) {
            this.isDefinite = false
            this.results = results
        }

        internal constructor(result: T, type:Class<T>) {
            this.isDefinite = true

            @Suppress("UNCHECKED_CAST")
            this.results = java.lang.reflect.Array.newInstance(type, 1) as Array<T>
            this.results[0] = result
        }

        fun result(): T {
            return results[0]
        }
    }

    private class MatchResultItem(val index: Int, val score: Int) : Comparable<MatchResultItem> {

        override fun compareTo(other: MatchResultItem): Int {
            return score - other.score
        }
    }
}