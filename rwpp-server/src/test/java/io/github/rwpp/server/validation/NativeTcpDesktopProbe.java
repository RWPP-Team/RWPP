package io.github.rwpp.server.validation;

import com.corrodinggames.librocket.scripts.ScriptEngine;
import com.corrodinggames.rts.game.RWGameEngine;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.game.units.Unit;
import com.corrodinggames.rts.gameFramework.GameEngine;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in probe for a real Slick/LibRocket desktop process and a separate native server. */
public final class NativeTcpDesktopProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("com.corrodinggames.rts.java.Main").getMethod("main", String[].class)
            .invoke(null, (Object) new String[]{"--rwml-no-launcher", "-nodisplay", "-nosound", "-nomusic"});
        Run.execute(args[0]);
    }

    private static final class Run {
        private static int stage;
        private static OrderableUnit unit;
        private static float x, y;
        private static int firstTick;
        private static final AtomicBoolean done = new AtomicBoolean();
        private static final AtomicReference<Throwable> failed = new AtomicReference<>();

        static void execute(String address) throws Exception {
            long deadline = System.nanoTime() + 45_000_000_000L;
            while (System.nanoTime() < deadline && !done.get() && failed.get() == null) {
                ScriptEngine scripts = ScriptEngine.getInstance();
                if (scripts != null && GameEngine.getInstance() instanceof RWGameEngine engine && engine.initCompleted) {
                    scripts.addRunnableToQueue(() -> {
                        try { observe(engine, scripts, address); }
                        catch (Throwable problem) { failed.compareAndSet(null, problem); }
                    });
                }
                Thread.sleep(20);
            }
            if (failed.get() != null) failed.get().printStackTrace();
            if (!done.get()) System.err.println("NATIVE_TCP_FAILED stage=" + stage);
            System.exit(done.get() ? 0 : 2);
        }

        static void observe(RWGameEngine engine, ScriptEngine scripts, String address) throws Exception {
            if (stage == 0) {
                stage = 1;
                scripts.getRoot().joinServer((Boolean.getBoolean("rwpp.probe.forceTcp") ? "[TCP]" : "") + address);
                return;
            }
            if (stage == 1 && engine.networkEngine.receivedServerInfo && engine.networkEngine.localTeam != null) {
                var connection = engine.networkEngine.getClientConnection();
                if (connection == null) return;
                String expected = System.getProperty("rwpp.probe.expectedTransport", "KCP");
                if (connection.socket instanceof io.github.rwpp.api.net.impl.NativePacketSocket socket) {
                    if (!socket.getTransportName().equals(expected))
                        throw new AssertionError("Expected " + expected + ", got " + socket.getTransportName());
                } else if (!expected.equals("VANILLA") && !expected.equals("TCP")) throw new AssertionError("Expected KCP socket");
                for (Thread thread : Thread.getAllStackTraces().keySet())
                    if (connection.socket instanceof io.github.rwpp.api.net.impl.NativePacketSocket framed && framed.getHandlesNativePackets()
                        && (thread.getName().startsWith("ReceiveWorker-") || thread.getName().startsWith("SendWorker-")))
                        throw new AssertionError("Per-connection worker still running: " + thread.getName());
                stage = 2;
                System.out.println("NATIVE_TCP_JOIN_OK transport=" + expected + " player=" + engine.networkEngine.localTeam.name);
            }
            if (stage == 2 && engine.networkEngine.gameStarted && engine.map != null && engine.map.mapLoaded) {
                for (Object item : Unit.unitList) {
                    if (item instanceof OrderableUnit candidate && candidate.team == engine.playerTeam && !candidate.isBuilding()) {
                        unit = candidate;
                        break;
                    }
                }
                if (unit == null) return;
                x = unit.x; y = unit.y; firstTick = engine.simulationTick;
                var move = engine.commandController.createCommandForTeam(engine.playerTeam);
                move.team = engine.playerTeam; move.player = engine.playerTeam;
                move.addUnit(unit); move.setMoveWaypoint(x + 120, y);
                stage = 3;
            }
            if (stage == 3 && Math.hypot(unit.x - x, unit.y - y) > 50 && engine.simulationTick > firstTick + 300
                ) {
                System.out.println("NATIVE_TCP_DESKTOP_OK movement=" + Math.hypot(unit.x - x, unit.y - y)
                    + " tick=" + engine.simulationTick + " ping=" + engine.networkEngine.localTeam.getPing());
                done.set(true);
            }
        }
    }
}

