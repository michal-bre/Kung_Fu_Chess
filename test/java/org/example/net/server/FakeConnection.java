package org.example.net.server;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.enums.Opcode;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.framing.Framedata;
import org.java_websocket.protocols.IProtocol;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test double for {@code org.java_websocket.WebSocket} that records every
 * message sent to it in memory instead of writing to a real socket.
 *
 * This is what makes Room/GameServer's routing and room-lifecycle logic
 * unit-testable at all: {@code WebSocket} is (conveniently) an interface in
 * the Java-WebSocket library, not a concrete class tied to a real channel, so
 * Room and GameServer - which only ever call {@code send}/{@code isOpen} on
 * the connections they're given, never anything socket-specific - can be
 * driven directly with one of these instead of a real
 * {@code WebSocketClient}/{@code WebSocketServer} pair talking over loopback.
 * That's the whole point of Phase 6's "server logic doesn't need a live
 * socket to test": GameServerIntegrationTest/RoomIntegrationTest/
 * GameServerDisconnectIntegrationTest already prove the wire protocol works
 * end to end over real sockets; the tests that use this class instead prove
 * the routing/seat-assignment/disconnect decisions themselves are correct,
 * without paying for a real TCP handshake and Thread.sleep-based polling on
 * every single case.
 *
 * Every method beyond send/isOpen/close is either a harmless no-op or a
 * simple in-memory field - Room and GameServer never call anything else on a
 * WebSocket (see their source), so there was no reason to implement framing,
 * drafts, or SSL for real.
 */
final class FakeConnection implements WebSocket {

    final List<String> sentMessages = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;
    private Object attachment;

    void setOpen(boolean open) {
        this.open = open;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public void close(int code) {
        open = false;
    }

    @Override
    public void close(int code, String message) {
        open = false;
    }

    @Override
    public IProtocol getProtocol() {
        return null;
    }

    @Override
    public void send(String text) {
        sentMessages.add(text);
    }

    @Override
    public void send(byte[] data) {
        // unused by Room/GameServer - every message this project sends is text.
    }

    @Override
    public void send(ByteBuffer bytes) {
        // unused by Room/GameServer.
    }

    @Override
    public void closeConnection(int code, String message) {
        open = false;
    }

    @Override
    public void sendFrame(Collection<Framedata> frames) {
        // unused by Room/GameServer.
    }

    @Override
    public void sendFrame(Framedata framedata) {
        // unused by Room/GameServer.
    }

    @Override
    public void sendPing() {
        // unused by Room/GameServer.
    }

    @Override
    public void sendFragmentedFrame(Opcode op, ByteBuffer buffer, boolean fin) {
        // unused by Room/GameServer.
    }

    @Override
    public boolean hasBufferedData() {
        return false;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalSocketAddress() {
        return null;
    }

    @Override
    public boolean isClosing() {
        return !open;
    }

    @Override
    public boolean isFlushAndClose() {
        return false;
    }

    @Override
    public boolean isClosed() {
        return !open;
    }

    @Override
    public Draft getDraft() {
        return null;
    }

    @Override
    public ReadyState getReadyState() {
        return open ? ReadyState.OPEN : ReadyState.CLOSED;
    }

    @Override
    public String getResourceDescriptor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void setAttachment(T attachment) {
        this.attachment = attachment;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttachment() {
        return (T) attachment;
    }

    @Override
    public boolean hasSSLSupport() {
        return false;
    }

    @Override
    public SSLSession getSSLSession() {
        return null;
    }
}
