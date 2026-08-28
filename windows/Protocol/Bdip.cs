using System.Buffers.Binary;
using System.IO;
using System.Text;

namespace BluedropWindows.Protocol;

/// <summary>
/// BDIP v1 wire framing — mirrors docs/PROTOCOL.md in the Bluedrop repo.
/// Frame: magic "BDIP" | ver u8 | type u8 | length u32 LE | payload.
/// </summary>
public static class Bdip
{
    public const int Version = 1;
    public const int HeaderSize = 10;
    public const int MaxPayload = 8 * 1024 * 1024;   // 8 MiB frame cap
    public const int MaxText = 1024 * 1024;           // 1 MiB clipboard text send cap
    public const int MaxImage = MaxPayload;           // 8 MiB clipboard image cap
    public const long MaxFile = 512L * 1024 * 1024;   // 512 MiB single-file policy cap
    public const int ChunkSize = 60 * 1024;           // 60 KiB file chunks
    public const int PingIntervalMs = 10_000;
    public const int RxDeadMs = 30_000;
    public const int HelloTimeoutMs = 10_000;

    public const byte TypeHello = 0x01;
    public const byte TypePing = 0x02;
    public const byte TypePong = 0x03;
    public const byte TypeBye = 0x04;
    public const byte TypeClipboardText = 0x10;
    public const byte TypeClipboardImage = 0x11;
    public const byte TypeFileMeta = 0x20;
    public const byte TypeFileChunk = 0x21;
    public const byte TypeFileAck = 0x22;
    public const byte TypeProgress = 0x30;

    public const byte ByeShutdown = 0;
    public const byte ByeProtocolError = 1;
    public const byte ByeBusy = 2;
    public const byte ByePairingFailed = 3;

    public static readonly byte[] Magic = [0x42, 0x44, 0x49, 0x50]; // "BDIP"
}

public sealed class ProtocolException(string message) : Exception(message);

public readonly record struct Frame(byte Type, byte[] Payload)
{
    public byte[] Encode()
    {
        if (Payload.Length > Bdip.MaxPayload)
            throw new ProtocolException("payload over frame cap");
        var bytes = new byte[Bdip.HeaderSize + Payload.Length];
        Bdip.Magic.CopyTo(bytes, 0);
        bytes[4] = (byte)Bdip.Version;
        bytes[5] = Type;
        BinaryPrimitives.WriteUInt32LittleEndian(bytes.AsSpan(6, 4), (uint)Payload.Length);
        Payload.CopyTo(bytes, Bdip.HeaderSize);
        return bytes;
    }
}

public static class FrameCodec
{
    /// <summary>Reads one full frame; throws ProtocolException on invalid
    /// framing and EndOfStreamException on EOF at any position.</summary>
    public static async Task<Frame> ReadAsync(Stream input, CancellationToken ct = default)
    {
        var header = await ReadFullyAsync(input, Bdip.HeaderSize, ct);
        if (!header.AsSpan(0, 4).SequenceEqual(Bdip.Magic))
            throw new ProtocolException("bad magic");
        var version = header[4];
        if (version != Bdip.Version)
            throw new ProtocolException($"unsupported version {version}");
        var type = header[5];
        var length = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(6, 4));
        if (length > Bdip.MaxPayload)
            throw new ProtocolException($"frame length {length} over cap");
        var payload = length == 0 ? [] : await ReadFullyAsync(input, (int)length, ct);
        return new Frame(type, payload);
    }

    private static async Task<byte[]> ReadFullyAsync(Stream input, int count, CancellationToken ct)
    {
        var buf = new byte[count];
        var off = 0;
        while (off < count)
        {
            var n = await input.ReadAsync(buf.AsMemory(off, count - off), ct);
            if (n <= 0) throw new EndOfStreamException();
            off += n;
        }
        return buf;
    }

    public static byte[] U32Le(uint value) =>
        [(byte)value, (byte)(value >> 8), (byte)(value >> 16), (byte)(value >> 24)];

    public static string Utf8(byte[] bytes) => Encoding.UTF8.GetString(bytes);
    public static byte[] Utf8(string s) => Encoding.UTF8.GetBytes(s);
}
