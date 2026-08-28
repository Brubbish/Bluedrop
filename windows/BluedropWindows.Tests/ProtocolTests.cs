using BluedropWindows.Protocol;
using System.IO;
using System.Text;
using Xunit;

namespace BluedropWindows.Tests;

public class FrameCodecTests
{
    private static string Hex(byte[] bytes) =>
        string.Join(" ", bytes.Select(b => b.ToString("x2")));

    // Canonical vectors live in /protocol/vectors.json at the monorepo root and
    // are shared with the Android suite — both framers must agree on these bytes.
    private static readonly System.Text.Json.JsonElement Vectors = LoadVectors();

    private static System.Text.Json.JsonElement LoadVectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "vectors.json");
        using var doc = System.Text.Json.JsonDocument.Parse(File.ReadAllBytes(path));
        return doc.RootElement.Clone();
    }

    private static byte[] HexToBytes(string hex) =>
        Enumerable.Range(0, hex.Length / 2)
            .Select(i => Convert.ToByte(hex.Substring(i * 2, 2), 16))
            .ToArray();

    [Fact]
    public void SharedFrameVectorsRoundTrip()
    {
        foreach (var entry in Vectors.GetProperty("frames").EnumerateArray())
        {
            var name = entry.GetProperty("name").GetString();
            var type = Convert.ToByte(entry.GetProperty("type").GetString()!, 16);
            var payload = HexToBytes(entry.GetProperty("payloadHex").GetString()!);
            var wire = HexToBytes(entry.GetProperty("frameHex").GetString()!);

            Assert.Equal(wire, new Frame(type, payload).Encode());

            var decoded = FrameCodec.ReadAsync(new MemoryStream(wire)).GetAwaiter().GetResult();
            Assert.Equal(type, decoded.Type);
            Assert.Equal(payload, decoded.Payload);
        }
    }

    [Fact]
    public async Task SharedRejectVectorsAreRefused()
    {
        foreach (var entry in Vectors.GetProperty("rejects").EnumerateArray())
        {
            var bytes = HexToBytes(entry.GetProperty("hex").GetString()!);
            await Assert.ThrowsAsync<ProtocolException>(
                () => FrameCodec.ReadAsync(new MemoryStream(bytes)));
        }
    }

    [Fact]
    public async Task DecodeRoundTripsLargePayload()
    {
        var payload = new byte[256 * 1024];
        for (var i = 0; i < payload.Length; i++) payload[i] = (byte)(i % 251);
        var encoded = new Frame(Bdip.TypeClipboardImage, payload).Encode();
        var decoded = await FrameCodec.ReadAsync(new MemoryStream(encoded));
        Assert.Equal(Bdip.TypeClipboardImage, decoded.Type);
        Assert.Equal(payload, decoded.Payload);
    }

    [Fact]
    public async Task TruncatedVectorFrameRaisesEndOfStream()
    {
        var first = Vectors.GetProperty("frames")[0].GetProperty("frameHex").GetString()!;
        var full = HexToBytes(first);
        await Assert.ThrowsAsync<EndOfStreamException>(
            () => FrameCodec.ReadAsync(new MemoryStream(full[..^2])));
    }

    [Fact]
    public void U32HelperIsLittleEndian()
    {
        Assert.Equal(new byte[] { 0x39, 0x30, 0x00, 0x00 }, FrameCodec.U32Le(12345));
    }
}

public class BdipSessionTests
{
    private sealed class RecordingListener : IBdipListener
    {
        public readonly List<string> Texts = [];
        public readonly List<byte[]> Images = [];
        public readonly List<FileMeta> Metas = [];
        public readonly List<(int, byte[])> Chunks = [];
        public HelloInfo? Peer;
        public string? ClosedReason;

        public void OnEstablished(HelloInfo peer) => Peer = peer;
        public void OnClipboardText(string text) => Texts.Add(text);
        public void OnClipboardImage(byte[] png) => Images.Add(png);
        public void OnFileMeta(FileMeta meta) => Metas.Add(meta);
        public void OnFileChunk(int index, byte[] data) => Chunks.Add((index, data));
        public void OnFileAck(FileAck ack) { }
        public void OnClosed(string reason) => ClosedReason = reason;
    }

    [Fact]
    public async Task HandshakeAndClipboardTextFlowBothWays()
    {
        var pipe = new DuplexPipePair();
        var initiatorListener = new RecordingListener();
        var responderListener = new RecordingListener();

        var initiator = new BdipSession(pipe.A, () => { }, initiatorListener,
            new HelloInfo { Name = "initiator" }, BdipRole.Initiator);
        var responder = new BdipSession(pipe.B, () => { }, responderListener,
            new HelloInfo { Name = "responder", Caps = ["text", "image", "file"] }, BdipRole.Responder);
        initiator.Start();
        responder.Start();

        await initiator.AwaitEstablishedAsync(new CancellationTokenSource(5000).Token);
        await responder.AwaitEstablishedAsync(new CancellationTokenSource(5000).Token);
        Assert.Equal("responder", initiatorListener.Peer?.Name);
        Assert.Equal("initiator", responderListener.Peer?.Name);

        Assert.True(initiator.SendClipboardText("hello from initiator"));
        Assert.True(responder.SendClipboardText("hi from responder"));
        await WaitUntil(() => responderListener.Texts.Count > 0 && initiatorListener.Texts.Count > 0);
        Assert.Equal("hello from initiator", responderListener.Texts[0]);
        Assert.Equal("hi from responder", initiatorListener.Texts[0]);

        initiator.Shutdown();
        responder.Shutdown();
    }

    [Fact]
    public async Task OversizedTextIsRefused()
    {
        var pipe = new DuplexPipePair();
        var initiator = new BdipSession(pipe.A, () => { }, new RecordingListener(),
            new HelloInfo { Name = "i" }, BdipRole.Initiator);
        var responder = new BdipSession(pipe.B, () => { }, new RecordingListener(),
            new HelloInfo { Name = "r" }, BdipRole.Responder);
        initiator.Start();
        responder.Start();
        await initiator.AwaitEstablishedAsync(new CancellationTokenSource(5000).Token);

        var tooBig = new string('x', Bdip.MaxText + 1);
        Assert.False(initiator.SendClipboardText(tooBig));
        initiator.Shutdown();
        responder.Shutdown();
    }

    private static async Task WaitUntil(Func<bool> condition, int timeoutMs = 5000)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (!condition())
        {
            if (sw.ElapsedMilliseconds > timeoutMs) throw new TimeoutException();
            await Task.Delay(10);
        }
    }

    private sealed class ChannelStream : Stream
    {
        private readonly object _gate = new();
        private readonly Queue<byte> _bytes = new();
        private readonly SemaphoreSlim _available = new(0, int.MaxValue);
        private bool _closed;

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override void Write(byte[] buffer, int offset, int count)
        {
            lock (_gate)
            {
                for (var i = 0; i < count; i++) _bytes.Enqueue(buffer[offset + i]);
            }
            _available.Release(count);
        }

        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct)
        {
            Write(buffer, offset, count);
            return Task.CompletedTask;
        }

        public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct)
        {
            var taken = 0;
            while (taken < count)
            {
                await _available.WaitAsync(ct);
                lock (_gate)
                {
                    buffer[offset + taken++] = _bytes.Dequeue();
                }
            }
            return taken;
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            var taken = 0;
            while (taken < count)
            {
                _available.Wait();
                lock (_gate)
                {
                    buffer[offset + taken++] = _bytes.Dequeue();
                }
            }
            return taken;
        }

        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
    }

    /// <summary>Two streams wired crosswise: writes on A surface as reads on B.</summary>
    private sealed class DuplexPipePair
    {
        private readonly ChannelStream _aToB = new();
        private readonly ChannelStream _bToA = new();

        public Stream A { get; } 
        public Stream B { get; }

        public DuplexPipePair()
        {
            A = new RelayStream(_aToB, _bToA);
            B = new RelayStream(_bToA, _aToB);
        }

        private sealed class RelayStream(ChannelStream readSide, ChannelStream writeSide) : Stream
        {
            public override bool CanRead => true;
            public override bool CanSeek => false;
            public override bool CanWrite => true;
            public override long Length => throw new NotSupportedException();
            public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

            public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
                readSide.ReadAsync(buffer, offset, count, ct);

            public override int Read(byte[] buffer, int offset, int count) =>
                readSide.Read(buffer, offset, count);

            public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
                writeSide.WriteAsync(buffer, offset, count, ct);

            public override void Write(byte[] buffer, int offset, int count) =>
                writeSide.Write(buffer, offset, count);

            public override void Flush() { }
            public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
            public override void SetLength(long value) => throw new NotSupportedException();
        }
    }
}
