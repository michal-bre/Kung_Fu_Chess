package org.example.account;

/**
 * Persistence port for player accounts - the ArchitectureDoc-style
 * "port/adapter" seam this project already uses elsewhere (MoveValidationPort,
 * EnginePort, GameEngine): GameServer depends only on this interface, never
 * on SqliteAccountRepository or java.sql directly, so it can be handed
 * either the real SQLite-backed implementation (production) or
 * InMemoryAccountRepository (tests, or a default when no database is
 * configured) without any change to GameServer itself.
 */
public interface AccountRepository {

    /** Returns the existing account for {@code username}, or creates one at EloRating.DEFAULT_RATING with zero games played if this is the first time this username has ever logged in. Does not check a password - only {@link #authenticate} does that; this method exists for callers (tests, recordGameResult's own bootstrap) that only need the account record itself. */
    Account findOrCreateAccount(String username);

    /**
     * Verifies {@code username}/{@code password} and returns that account,
     * creating a brand-new one at EloRating.DEFAULT_RATING (and recording
     * this password as its own) if this is the first time this username has
     * ever logged in - the same "create on first use" policy
     * {@link #findOrCreateAccount} already had, just gated by a password
     * too now (CTD 26 spec slide 5). An account that already exists but has
     * no password on file yet (created before this method existed) adopts
     * whatever password is given on its next login instead of permanently
     * locking that player out - see each implementation's authenticate for
     * exactly when that applies.
     *
     * @throws AuthenticationException if the account already has a password
     *         on file and it doesn't match the one supplied here.
     */
    Account authenticate(String username, String password);

    /**
     * Records one decisive game's outcome for both participants: persists
     * each account's new ELO rating and increments its games-played and
     * win/loss counters. Both accounts are updated together (not as two
     * independent calls) so a caller can never accidentally record only
     * half of a game's result.
     */
    void recordGameResult(String winnerUsername, int newWinnerElo, String loserUsername, int newLoserElo);
}
