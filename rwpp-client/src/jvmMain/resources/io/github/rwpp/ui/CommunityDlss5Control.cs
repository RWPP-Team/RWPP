// RWPP adapter for UniversalDLSS5 v0.5.1's published shared_control.hpp (ABI 21).
// No graphics hooks or NVIDIA code: only controller IPC and process-module inspection.
using System;
using System.Diagnostics;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Text;
using System.Threading;
using System.Runtime.InteropServices;

public sealed class RwppNeuralSample {
    public bool Attached, Active;
    public uint State, Api, FailureStage;
    public ulong NeuralFrames, ProcessedFrames;
    public float GpuMs;
    public string Message = "";
}

public static class RwppDlssControl {
    private delegate bool WindowCallback(IntPtr window, IntPtr argument);
    [DllImport("user32.dll")] private static extern bool EnumWindows(WindowCallback callback, IntPtr argument);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr window, uint command);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    // CloseMainWindow ignores hidden controller windows. WM_CLOSE preserves the
    // controller's normal detach/cleanup path even when it was launched hidden.
    public static void RequestControllerClose(int pid, long started, string expected) {
        using (var process = Process.GetProcessById(pid)) {
            if (process.StartTime.ToFileTimeUtc() != started || !String.Equals(process.MainModule.FileName, expected, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Controller process identity changed.");
            if (process.CloseMainWindow()) return;
            bool requested = false;
            EnumWindows(delegate(IntPtr window, IntPtr ignored) {
                uint owner; GetWindowThreadProcessId(window, out owner);
                if (owner == (uint)pid && GetWindow(window, 4) == IntPtr.Zero)
                    requested = PostMessage(window, 0x10, IntPtr.Zero, IntPtr.Zero) || requested;
                return true;
            }, IntPtr.Zero);
            if (!requested) throw new InvalidOperationException("Controller has no shutdown window.");
        }
    }
    private const string MapName = "Local\\UniversalDLSS5.Control.v1";
    private const int Settings = 40, RuntimePath = 208, Statuses = 1232, StatusSize = 2896, BlockSize = 93904;

    private static void Verify(MemoryMappedViewAccessor v) {
        if (v.Capacity < BlockSize || v.ReadUInt32(0) != 0x35534c44 || v.ReadUInt32(4) != 21 || v.ReadUInt32(Settings) != 10)
            throw new InvalidOperationException("Unsupported UniversalDLSS5 shared-control version; use v0.5.1.");
    }

    public static RwppNeuralSample Read(int pid) {
        try {
            using (var map = MemoryMappedFile.OpenExisting(MapName, MemoryMappedFileRights.Read))
            using (var v = map.CreateViewAccessor(0, 0, MemoryMappedFileAccess.Read)) {
                Verify(v);
                for (int retry = 0; retry < 4; retry++) {
                    int seq = v.ReadInt32(12);
                    if ((seq & 1) != 0) { Thread.Sleep(2); continue; }
                    Thread.MemoryBarrier();
                    for (int i = 0; i < 32; i++) {
                        long b = Statuses + i * StatusSize;
                        if (v.ReadUInt32(b) != (uint)pid) continue;
                        ulong tick = v.ReadUInt64(b + 160);
                        ulong age = ((ulong)unchecked((uint)Environment.TickCount) + 0x100000000UL - (tick & 0xffffffffUL)) & 0xffffffffUL;
                        if (tick == 0 || age > 10000) continue;
                        byte[] message = new byte[768]; v.ReadArray(b + 2124, message, 0, message.Length);
                        var text = Encoding.Unicode.GetString(message).Split('\0')[0];
                        var sample = new RwppNeuralSample {
                            Attached = true, State = v.ReadUInt32(b + 28), Api = v.ReadUInt32(b + 4),
                            Active = v.ReadUInt32(b + 140) != 0, NeuralFrames = v.ReadUInt64(b + 88),
                            ProcessedFrames = v.ReadUInt64(b + 72), GpuMs = v.ReadSingle(b + 152),
                            FailureStage = v.ReadUInt32(b + 48), Message = text
                        };
                        Thread.MemoryBarrier();
                        if (seq == v.ReadInt32(12)) return sample;
                        break;
                    }
                    if (seq == v.ReadInt32(12)) return new RwppNeuralSample();
                }
            }
        } catch (FileNotFoundException) { }
        return new RwppNeuralSample();
    }

    public static bool IsBridgeLoaded(int pid, string expected) {
        using (var process = Process.GetProcessById(pid)) {
            foreach (ProcessModule module in process.Modules) {
                if (!String.Equals(module.ModuleName, "UniversalDLSS5.Bridge.dll", StringComparison.OrdinalIgnoreCase)) continue;
                if (!String.Equals(Path.GetFullPath(module.FileName), Path.GetFullPath(expected), StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("A different UniversalDLSS5 bridge is already loaded.");
                return true;
            }
        }
        return false;
    }

    public static unsafe void Configure(int pid, string runtime) {
        if (!File.Exists(Path.Combine(runtime, "nvngx_dlssnr.dll"))) throw new FileNotFoundException("nvngx_dlssnr.dll is missing.");
        byte[] path = Encoding.Unicode.GetBytes(runtime + "\0");
        if (path.Length > 1024) throw new ArgumentException("Runtime path is too long.");
        using (var map = MemoryMappedFile.OpenExisting(MapName, MemoryMappedFileRights.ReadWrite))
        using (var v = map.CreateViewAccessor(0, 0, MemoryMappedFileAccess.ReadWrite)) {
            Verify(v);
            uint owner = v.ReadUInt32(36);
            if (owner != 0 && owner != (uint)pid && Read((int)owner).Attached)
                throw new InvalidOperationException("The controller is attached to another application. Detach it first.");
            byte* p = null;
            v.SafeMemoryMappedViewHandle.AcquirePointer(ref p);
            try {
                int seq = 0; bool locked = false;
                for (int retry = 0; retry < 100; retry++) {
                    seq = *(int*)(p + 8);
                    if ((seq & 1) == 0 && Interlocked.CompareExchange(ref *(int*)(p + 8), seq + 1, seq) == seq) { locked = true; break; }
                    Thread.Sleep(5);
                }
                if (!locked) throw new InvalidOperationException("Controller settings are busy.");
                try {
                    // Game2D preset, one neural pass, SDR and an explicit opt-in for RTX 40.
                    p[Settings + 4] = 1; p[Settings + 5] = 1; p[Settings + 6] = 1; p[Settings + 7] = 1;
                    p[Settings + 9] = 0; p[Settings + 10] = 0; p[Settings + 12] = 1;
                    p[Settings + 14] = 0; p[Settings + 15] = 0;
                    *(uint*)(p + Settings + 20) = 0; *(uint*)(p + Settings + 24) = 0;
                    *(uint*)(p + Settings + 28) = 0; *(uint*)(p + Settings + 32) = 1;
                    *(uint*)(p + Settings + 40) = 0; *(uint*)(p + Settings + 48) = 0;
                    *(float*)(p + Settings + 52) = .24f; *(float*)(p + Settings + 60) = .82f;
                    *(float*)(p + Settings + 88) = .88f; *(float*)(p + Settings + 92) = .92f;
                    *(float*)(p + Settings + 100) = .80f; *(float*)(p + Settings + 104) = .40f;
                    *(uint*)(p + Settings + 152) = 1; *(uint*)(p + Settings + 156) = 4; *(uint*)(p + Settings + 160) = 4;
                    for (int i = 0; i < 1024; i++) p[RuntimePath + i] = 0;
                    for (int i = 0; i < path.Length; i++) p[RuntimePath + i] = path[i];
                    *(uint*)(p + 36) = (uint)pid; *(uint*)(p + 32) = (uint)pid; *(int*)(p + 16) = 0;
                    Thread.MemoryBarrier();
                } finally { Interlocked.Exchange(ref *(int*)(p + 8), seq + 2); }
                Interlocked.Increment(ref *(int*)(p + 20));
                Interlocked.Increment(ref *(int*)(p + 28));
            } finally { v.SafeMemoryMappedViewHandle.ReleasePointer(); }
        }
    }
}
