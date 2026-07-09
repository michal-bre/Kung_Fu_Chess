# Kung Fu Chess

A real-time chess variant with no turns: both sides can move simultaneously, and every move takes real (simulated) time to complete instead of resolving instantly. A piece sent to a square is "in flight" for a duration proportional to the distance it travels, which opens the door to race conditions, mid-air captures, and defensive "jumps" that have no equivalent in standard turn-based chess.

The game runs as a console program: it reads a board layout and a list of commands from `stdin`, and prints results to `stdout`.

> תיעוד מלא בעברית זמין ב-[`README.he.md`](README.he.md). קובץ הסבר טכני מעמיק, קובץ-אחר-קובץ, זמין ב-[`ARCHITECTURE.he.md`](ARCHITECTURE.he.md).

---

## Architecture

The codebase is organized around **Clean Architecture**, split into four strictly decoupled layers plus an outer adapters layer for I/O. Each layer only knows about the layers "inside" it — dependencies always point inward, toward the model:

```
main/java/org/example/
├── Main.java              # Composition root: wires every dependency by hand
├── model/                 # Pure domain entities — zero dependencies
│   ├── Board.java
│   ├── Piece.java
│   └── Position.java
├── rules/                 # Business rules & move validation — depends only on model
│   ├── MoveValidationService.java
│   ├── PawnPromotionService.java
│   ├── AirCaptureService.java
│   ├── ActiveMoveQuery.java
│   └── MoveValidationPort.java
├── engine/                 # Real-time state management & synchronization
│   ├── MovementEngine.java
│   ├── ActiveMove.java
│   └── EnginePort.java
├── controller/             # Command routing / application coordination
│   ├── GameController.java
│   └── InteractionHandler.java
└── adapters/                # Input/output (console)
    ├── BoardParser.java
    ├── BoardPresenter.java
    ├── CommandLineAdapter.java
    └── CommandType.java
```

| Layer | Responsibility | Allowed to depend on |
|---|---|---|
| **Model** | Pure domain objects (`Board`, `Piece`, `Position`) with no behavior tied to timing, I/O, or rules. | *Nothing* |
| **Rules** | Business rules and move validation — legality checks, pawn promotion, capture-on-collision logic. | Model |
| **Engine** | Real-time state management: the game clock, in-flight moves, and synchronization between simultaneous actions. | Model, Rules |
| **Controller** | Application entry point — routes commands to the engine and rules layers through interfaces (ports), never through concrete classes. | Model, Rules (via ports), Engine (via ports) |
| **Adapters** | Console I/O: parsing input, printing the board. Kept entirely outside the four core layers. | Everything |

This separation matters for a few concrete reasons:

- **Testability.** The `rules` layer can be unit tested with plain model objects and a one-line fake port — no engine, no controller, no I/O required. The `engine` layer can be tested with a real board and no controller at all.
- **No hidden coupling.** None of `model`, `rules`, `engine`, or `controller` contain a single `System.out` call or any knowledge of how input arrives. All console I/O lives in `adapters`.
- **Swappable front end.** Because `GameController` only depends on `EnginePort` and `MoveValidationPort` — never on `MovementEngine` or `MoveValidationService` directly — the console adapter could be replaced with a GUI, a web socket handler, or an AI client without touching a single line of game logic.

---

## Features

- **Real-time, turn-free movement** — every move has a duration based on distance traveled (`MOVE_DURATION_PER_SQUARE`), and the board only updates once a move actually lands.
- **Race-condition handling** — when two pieces of different colors are sent to the same square in the same tick, the engine resolves the collision deterministically instead of corrupting board state: every source square is cleared first, then destinations are resolved as a group, so the earliest-added move wins the square and the loser is captured.
- **Air captures via jumps** — a piece can "jump" in place to guard a square, intercepting an enemy move that lands there while the jump is active.
- **Test-driven development (TDD)** — the project has an extensive JUnit test suite (11 iteration-based test classes covering parsing, movement, blockers, pawn rules, timing, jumps, and game-over conditions) written and extended alongside the implementation.
- **Dependency-inverted design** — `rules` defines the narrow interfaces (`ActiveMoveQuery`, `MoveValidationPort`) it needs from the outer layers instead of depending on them directly, so the dependency graph always points toward the domain, never away from it.

---

## Game commands

The program reads a board section and a commands section from `stdin`:

```
Board:
<board row 1>
<board row 2>
...
Commands:
<command 1>
<command 2>
...
```

Board rows use `wK`/`bK`-style tokens (`w`/`b` for color, `K/Q/R/N/B/P` for piece type) and `.` for an empty square.

| Command | Description |
|---|---|
| `print board` | Prints the current board state |
| `click X Y` | Clicks at **pixel** coordinates `(X, Y)` — selects a piece, or attempts a move if a piece is already selected |
| `jump X Y` | Performs a defensive jump at pixel coordinates `(X, Y)` |
| `wait MS` | Advances the game clock by `MS` milliseconds |

`X`/`Y` are pixel coordinates on a simulated grid, converted to board cells via `row = Y / Board.CELL_SIZE` and `col = X / Board.CELL_SIZE` (`CELL_SIZE = 100`).

Example:

```
Board:
wK . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . .
. . . . . . . bK
Commands:
click 50 50
click 150 50
wait 1000
print board
```

---

## Building & running

The project has no build tool (no Maven/Gradle) — it's compiled directly against the sources under `main/java`, with `JUnit`/`Hamcrest` (under `lib/`) needed only for the test suite under `test/java`.

```bash
# compile
javac -d out $(find main/java -name '*.java')

# run
java -cp out org.example.Main < input.txt

# compile & run tests
javac -d out -cp "lib/junit-4.13.1.jar:lib/hamcrest-core-1.3.jar:out" $(find test/java -name '*.java')
java -cp "lib/junit-4.13.1.jar:lib/hamcrest-core-1.3.jar:out" org.junit.runner.JUnitCore org.example.Iteration1_BoardParsingTest
```

### Testing note: `TestGameControllerFactory`

`GameController` uses **pure constructor injection** — it never instantiates its own collaborators, so every test that needs a fully wired game has to build the object graph by hand: a `MovementEngine`, a `MoveValidationService` bound to it, an `InteractionHandler` bound to both, and finally the `GameController` itself.

To avoid repeating that wiring at every test call site, `test/java/org/example/TestGameControllerFactory.java` provides a single static factory method:

```java
GameController gc = TestGameControllerFactory.create(board);
```

This is a test-only helper — it performs exactly the same manual wiring the composition root (`Main.java`) performs in production, just packaged for reuse across the test suite. Production code never calls it.

---

## License

No license file is currently included in this repository.
