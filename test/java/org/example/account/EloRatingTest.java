package org.example.account;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pure-math coverage for EloRating - no I/O, no AccountRepository, mirroring
 * how e.g. PieceScore is tested directly rather than only indirectly through
 * a full game.
 */
public class EloRatingTest {

    @Test
    public void equalRatingsSplitTheKFactorEvenly() {
        // Expected score is exactly 0.5 for equal ratings, so the point
        // exchange is exact (no rounding ambiguity) - a clean case to
        // hardcode, unlike the asymmetric cases below.
        EloRating.Result result = EloRating.computeForWin(1200, 1200, 32);
        assertEquals(1216, result.getNewWinnerRating());
        assertEquals(1184, result.getNewLoserRating());
    }

    @Test
    public void winningIsWorthMorePointsWhenUpsettingAHigherRatedOpponent() {
        EloRating.Result upset = EloRating.computeForWin(1000, 1400); // huge underdog wins
        EloRating.Result expected = EloRating.computeForWin(1400, 1000); // big favorite wins as expected

        int upsetGain = upset.getNewWinnerRating() - 1000;
        int expectedGain = expected.getNewWinnerRating() - 1400;

        assertTrue("an upset win must be worth strictly more rating points than an expected win",
                upsetGain > expectedGain);
    }

    @Test
    public void pointExchangeIsZeroSumWithinRoundingTolerance() {
        int[][] pairs = {{1200, 1200}, {1000, 1400}, {1400, 1000}, {800, 2000}, {1500, 1505}};
        for (int[] pair : pairs) {
            EloRating.Result result = EloRating.computeForWin(pair[0], pair[1]);
            int winnerDelta = result.getNewWinnerRating() - pair[0];
            int loserDelta = result.getNewLoserRating() - pair[1];
            assertTrue("winner's gain and loser's loss must offset within 1 point (independent rounding) for "
                            + pair[0] + " vs " + pair[1],
                    Math.abs(winnerDelta + loserDelta) <= 1);
            assertTrue("the winner must never lose rating", winnerDelta >= 0);
            assertTrue("the loser must never gain rating", loserDelta <= 0);
        }
    }

    @Test
    public void kFactorScalesTheRatingChangeProportionally() {
        EloRating.Result withK16 = EloRating.computeForWin(1200, 1200, 16);
        EloRating.Result withK32 = EloRating.computeForWin(1200, 1200, 32);

        assertEquals(8, withK16.getNewWinnerRating() - 1200);
        assertEquals(16, withK32.getNewWinnerRating() - 1200);
    }

    @Test
    public void defaultRatingAndKFactorMatchDocumentedConstants() {
        assertEquals(1200, EloRating.DEFAULT_RATING);
        assertEquals(32, EloRating.DEFAULT_K_FACTOR);
    }
}
