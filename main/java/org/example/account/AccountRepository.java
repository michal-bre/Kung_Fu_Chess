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

    /** Returns the existing account for {@code username}, or creates one at EloRating.DEFAULT_RATING with zero games played if this is the first time this username has ever logged in. Usernames are the sole account key - see GameServer's class doc for why this project has no password/authentication step. */
    Account findOrCreateAccount(String username);

    /**
     * Records one decisive game's outcome for both participants: persists
     * each account's new ELO rating and increments its games-played and
     * win/loss counters. Both accounts are updated together (not as two
     * independent calls) so a caller can never accidentally record only
     * half of a game's result.
     */
    void recordGameResult(String winnerUsername, int newWinnerElo, String loserUsername, int newLoserElo);
}
