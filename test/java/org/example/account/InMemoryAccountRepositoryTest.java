package org.example.account;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Contract test for AccountRepository, run against the in-memory
 * implementation (fast, no I/O). SqliteAccountRepository implements the
 * exact same interface and is exercised end-to-end by GameServer's own
 * usage - see ServerMain's class doc for why a real SQLite driver jar isn't
 * available to test against directly in this project's sandbox, and
 * GameServer's account-flow tests (GameServerIntegrationTest) for coverage
 * of the LOGIN/GAME_ENDED wiring itself, exercised through this same
 * in-memory repository via GameServer's 5-arg constructor.
 */
public class InMemoryAccountRepositoryTest {

    @Test
    public void findOrCreateAccountGivesANewUsernameTheDefaultRatingAndZeroGames() {
        AccountRepository repo = new InMemoryAccountRepository();
        Account account = repo.findOrCreateAccount("alice");

        assertEquals("alice", account.getUsername());
        assertEquals(EloRating.DEFAULT_RATING, account.getElo());
        assertEquals(0, account.getGamesPlayed());
        assertEquals(0, account.getWins());
        assertEquals(0, account.getLosses());
    }

    @Test
    public void findOrCreateAccountReturnsTheSameAccountOnASecondCall() {
        AccountRepository repo = new InMemoryAccountRepository();
        repo.recordGameResult("alice", 1216, "bob", 1184);

        Account alice = repo.findOrCreateAccount("alice");
        assertEquals(1216, alice.getElo());
        assertEquals(1, alice.getGamesPlayed());
    }

    @Test
    public void recordGameResultUpdatesBothPlayersEloAndWinLossCounts() {
        AccountRepository repo = new InMemoryAccountRepository();
        repo.findOrCreateAccount("alice");
        repo.findOrCreateAccount("bob");

        repo.recordGameResult("alice", 1216, "bob", 1184);

        Account alice = repo.findOrCreateAccount("alice");
        Account bob = repo.findOrCreateAccount("bob");

        assertEquals(1216, alice.getElo());
        assertEquals(1, alice.getGamesPlayed());
        assertEquals(1, alice.getWins());
        assertEquals(0, alice.getLosses());

        assertEquals(1184, bob.getElo());
        assertEquals(1, bob.getGamesPlayed());
        assertEquals(0, bob.getWins());
        assertEquals(1, bob.getLosses());
    }

    @Test
    public void recordGameResultAccumulatesAcrossMultipleGames() {
        AccountRepository repo = new InMemoryAccountRepository();
        repo.recordGameResult("alice", 1216, "bob", 1184);
        repo.recordGameResult("alice", 1230, "bob", 1170);

        Account alice = repo.findOrCreateAccount("alice");
        assertEquals(2, alice.getGamesPlayed());
        assertEquals(2, alice.getWins());
        assertEquals(1230, alice.getElo());
    }
}
