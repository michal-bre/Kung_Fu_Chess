package org.example.account;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A non-persistent AccountRepository - accounts live only as long as this
 * instance does (a JVM restart loses everything). Used as GameServer's
 * default when no real repository is supplied (e.g. GameServerIntegrationTest,
 * or anyone constructing GameServer via its original 4-arg constructor from
 * Phase 2, which never needed accounts to exist at all), and as the
 * production fallback if ServerMain is run without a database file
 * configured. SqliteAccountRepository is the real, persistent implementation
 * - see its class doc.
 */
public final class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accountsByUsername = new ConcurrentHashMap<>();

    @Override
    public Account findOrCreateAccount(String username) {
        return accountsByUsername.computeIfAbsent(username,
                name -> new Account(name, EloRating.DEFAULT_RATING, 0, 0, 0));
    }

    @Override
    public synchronized void recordGameResult(String winnerUsername, int newWinnerElo, String loserUsername, int newLoserElo) {
        accountsByUsername.compute(winnerUsername, (name, existing) -> {
            Account base = existing != null ? existing : new Account(name, EloRating.DEFAULT_RATING, 0, 0, 0);
            return new Account(name, newWinnerElo, base.getGamesPlayed() + 1, base.getWins() + 1, base.getLosses());
        });
        accountsByUsername.compute(loserUsername, (name, existing) -> {
            Account base = existing != null ? existing : new Account(name, EloRating.DEFAULT_RATING, 0, 0, 0);
            return new Account(name, newLoserElo, base.getGamesPlayed() + 1, base.getWins(), base.getLosses() + 1);
        });
    }
}
