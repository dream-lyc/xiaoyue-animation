package cn.childtv.core

import java.util.Random
import kotlin.test.*

class ParentChallengeTest {
    private fun challengeWith(vararg values: Int): Pair<ParentAuthorization, ParentChallenge> {
        val numbers = values.iterator()
        val authorization = ParentAuthorization({ 0L }, { _, _ ->
            numbers.nextInt()
        })
        return authorization to authorization.challenge(ParentAction.CHANGE_TODAY)
    }

    @Test fun additionIsOneMentalArithmeticStepWithinTwenty() {
        val (authorization, challenge) = challengeWith(0, 8, 7)
        assertEquals("8 + 7 = ?", challenge.prompt)
        assertNotNull(authorization.answer(challenge.id, 15))
    }

    @Test fun subtractionIsOneMentalArithmeticStepWithNonNegativeResult() {
        val (authorization, challenge) = challengeWith(1, 18, 6)
        assertEquals("18 - 6 = ?", challenge.prompt)
        assertNotNull(authorization.answer(challenge.id, 12))
    }

    @Test fun generatedChallengesUseOnlySimpleSingleStepOperations() {
        val seeded = Random(42)
        val authorization = ParentAuthorization({ 0L }, { from, until -> from + seeded.nextInt(until - from) })
        val operations = mutableSetOf<String>()
        repeat(300) {
            val challenge = authorization.challenge(ParentAction.CHANGE_TODAY)
            val match = assertNotNull(Regex("(\\d+) ([+×-]) (\\d+) = \\?").matchEntire(challenge.prompt))
            val a = match.groupValues[1].toInt()
            val operator = match.groupValues[2]
            val b = match.groupValues[3].toInt()
            operations += operator
            val answer = when (operator) { "+" -> a + b; "-" -> a - b; else -> a * b }
            if (operator == "×") assertTrue(a in 1..9 && b in 1..9 && answer in 1..81)
            else assertTrue(a in 0..20 && b in 0..20 && answer in 0..20)
            assertNotNull(authorization.answer(challenge.id, answer))
        }
        assertEquals(setOf("+", "-", "×"), operations)
    }

    @Test fun multiplicationUsesOnlySingleDigitOperands() {
        val (authorization, challenge) = challengeWith(2, 6, 7)
        assertEquals("6 × 7 = ?", challenge.prompt)
        assertNotNull(authorization.answer(challenge.id, 42))
        val (smallest, one) = challengeWith(2, 1, 1)
        assertNotNull(smallest.answer(one.id, 1))
        val (largest, eightyOne) = challengeWith(2, 9, 9)
        assertNotNull(largest.answer(eightyOne.id, 81))
    }

    @Test fun additionCanReachTwentyAndSubtractionCanReachZero() {
        val (addition, sum) = challengeWith(0, 19, 1)
        assertNotNull(addition.answer(sum.id, 20))
        val (subtraction, difference) = challengeWith(1, 20, 20)
        assertNotNull(subtraction.answer(difference.id, 0))
    }
}
