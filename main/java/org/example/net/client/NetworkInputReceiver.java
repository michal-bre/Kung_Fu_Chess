package org.example.net.client;

import org.example.controller.BoardMapper;
import org.example.controller.InputReceiver;
import org.example.engine.GameSnapshot;
import org.example.engine.PieceSnapshot;
import org.example.engine.VisualState;
import org.example.model.Piece;
import org.example.model.Position;

import java.util.Optional;

/**
 * The network client's InputReceiver - the direct counterpart to
 * InteractionHandler, but simplified: there is no local GameEngine to ask
 * "is this move legal", because the server is the only thing authoritative
 * enough to answer that. This class only decides the one thing that IS a
 * purely local, per-viewer UI concern - which square the player currently
 * has selected - and otherwise just forwards a completed selection straight
 * to GameClient as a MOVE/JUMP command, exactly like a thin client in any
 * client-authoritative-server architecture ("optimistic input, authoritative
 * server").
 *
 * "Can I even select this square" is approximated client-side by checking
 * the latest server snapshot for an IDLE piece of this player's own color at
 * the clicked square (VisualState.IDLE mirrors DefaultGameEngine.canSelect's
 * !isPieceMovingFrom && !isPieceResting check closely enough for this
 * purpose - see PieceSnapshot/VisualState). This is only a usability guard
 * against obviously-wrong clicks (empty square, opponent's piece, a piece
 * mid-flight); the server re-validates everything independently regardless
 * (see GameServer.handleMove/handleJump) and is the only outcome that
 * actually matters.
 *
 * Implements the same InputReceiver interface GameController implements, so
 * BoardInputListener works with either one unmodified - see InputReceiver's
 * class doc.
 */
public final class NetworkInputReceiver implements InputReceiver {

    private final GameClient gameClient;
    private final BoardMapper boardMapper;

    private volatile Position selectedPosition;

    public NetworkInputReceiver(GameClient gameClient, BoardMapper boardMapper) {
        this.gameClient = gameClient;
        this.boardMapper = boardMapper;
    }

    @Override
    public void handleClick(int x, int y) {
        Optional<Position> mapped = boardMapper.toPosition(x, y);
        if (!mapped.isPresent()) return;
        Position clicked = mapped.get();

        if (selectedPosition == null) {
            if (isOwnSelectablePieceAt(clicked)) {
                selectedPosition = clicked;
            }
            return;
        }

        if (clicked.equals(selectedPosition)) {
            selectedPosition = null;
            return;
        }

        if (isOwnSelectablePieceAt(clicked)) {
            selectedPosition = clicked;
            return;
        }

        gameClient.sendMove(selectedPosition, clicked);
        selectedPosition = null;
    }

    @Override
    public void handleJump(int x, int y) {
        Optional<Position> mapped = boardMapper.toPosition(x, y);
        if (!mapped.isPresent()) return;
        gameClient.sendJump(mapped.get());
    }

    /** Which square (if any) this player currently has selected - folded into the composite GameSnapshot BoardView renders, exactly like InteractionHandler.getSelectedPosition feeds GameController.getSnapshot in local mode. */
    public Position getSelectedPosition() {
        return selectedPosition;
    }

    /**
     * Builds the exact GameSnapshot BoardView should render right now: the
     * server's latest base state, with this client's own local selection
     * (and the server's most recent rejection feedback, if any) overlaid on
     * top - see GameServer.buildStateMessage's class doc for why the server
     * never sends selection/rejection itself. Intended to be handed to
     * BoardView as its Supplier<GameSnapshot> (e.g.
     * {@code new BoardView(renderer, networkInputReceiver::getEffectiveSnapshot, ...)}).
     */
    public GameSnapshot getEffectiveSnapshot() {
        GameSnapshot base = gameClient.getBaseSnapshot();
        return new GameSnapshot(base.getBoardWidth(), base.getBoardHeight(), base.getPieces(),
                selectedPosition, gameClient.getLastRejectedPosition(), gameClient.getLastRejectedAtMillis(),
                base.getGameTimeMillis(), base.isGameOver(), base.getWinner());
    }

    private boolean isOwnSelectablePieceAt(Position position) {
        Piece.Color myColor = gameClient.getMyColor();
        if (myColor == null) return false; // not yet assigned a color (still connecting) or a spectator

        int cellSize = boardMapper.getCellSize();
        for (PieceSnapshot piece : gameClient.getBaseSnapshot().getPieces()) {
            if (piece.getColor() != myColor || piece.getVisualState() != VisualState.IDLE) continue;
            int col = (int) Math.round(piece.getPixelX() / cellSize);
            int row = (int) Math.round(piece.getPixelY() / cellSize);
            if (row == position.getRow() && col == position.getCol()) {
                return true;
            }
        }
        return false;
    }
}
