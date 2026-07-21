package org.example.account;

/**
 * The standard ELO rating update formula (as used by chess federations and
 * every mainstream online chess server) - pure math, zero I/O, zero
 * dependency on Account/AccountRepository, so it's trivially unit-testable
 * on its own (see EloRatingTest).
 *
 * This engine currently has no draw/stalemate detection at all - a game
 * only ever ends via GameEndedEvent, published the instant a king is
 * captured (see MovementEngine.setWinner), so there is always exactly one
 * winner and one loser. computeForWin below is the only case this project
 * can ever actually produce; it is not artificially generalized to handle
 * draws that can't currently happen.
 */
public final class EloRating {

    /** Starting rating for a brand-new account - the customary default across chess rating systems (e.g. FIDE's provisional starting point is similar; 1200 is also what chess.com/lichess-style "new player" baselines commonly use). */
    public static final int DEFAULT_RATING = 1200;

    /** How many rating points change hands on a single game - a higher K means ratings react faster but are noisier; 32 is the standard value USCF/FIDE use for players who aren't yet highly established. */
    public static final int DEFAULT_K_FACTOR = 32;

    private EloRating() {
    }

    /** Computes both players' new ratings after a decisive game, using the default K-factor. */
    public static Result computeForWin(int winnerRating, int loserRating) {
        return computeForWin(winnerRating, loserRating, DEFAULT_K_FACTOR);
    }

    /**
     * Computes both players' new ratings after a decisive game.
     *
     * Expected score for the winner: 1 / (1 + 10^((loserRating - winnerRating) / 400)) -
     * the standard ELO expectation curve (a 400-point rating gap implies the
     * higher-rated player is expected to score about 10x as often as the
     * lower-rated one). The winner's rating moves toward "won this game
     * fully" (actual score 1) by kFactor times the surprise (1 - expected);
     * the loser's rating moves the exact same number of points the other
     * way (actual score 0, so its own expected-score gap is negative of the
     * winner's) - ELO point exchange is zero-sum by construction.
     */
    public static Result computeForWin(int winnerRating, int loserRating, int kFactor) {
        double expectedWinner = 1.0 / (1.0 + Math.pow(10, (loserRating - winnerRating) / 400.0));
        double expectedLoser = 1.0 - expectedWinner;

        int newWinnerRating = (int) Math.round(winnerRating + kFactor * (1.0 - expectedWinner));
        int newLoserRating = (int) Math.round(loserRating + kFactor * (0.0 - expectedLoser));

        return new Result(newWinnerRating, newLoserRating);
    }

    /** The outcome of a single computeForWin call - both players' new ratings, so a caller never has to call this twice (which risks the two computed deltas silently drifting out of sync if the loser's call happened to read a since-changed winnerRating). */
    public static final class Result {
        private final int newWinnerRating;
        private final int newLoserRating;

        Result(int newWinnerRating, int newLoserRating) {
            this.newWinnerRating = newWinnerRating;
            this.newLoserRating = newLoserRating;
        }

        public int getNewWinnerRating() {
            return newWinnerRating;
        }

        public int getNewLoserRating() {
            return newLoserRating;
        }
    }
}
