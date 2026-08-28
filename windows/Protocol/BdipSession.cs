using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.IO;

namespace BluedropWindows.Protocol;

public enum BdipRole { Initiator, Responder }

public interface IBdipListener
{
    void OnEstablished(HelloInfo peer);
    void OnClipboardText(string text);
    void OnClipboardImage(byte[] png);
    void OnFileMeta(FileMeta meta);
    void OnFileChunk(int index, byte[] data);
    void OnFileAck(FileAck ack);
    void OnClosed(string reason);
}

/// <summary>
/// One BDIP session over an established stream (see docs/PROTOCOL.md).
/// Writes are serialized through a single consumer loop; the reader
/// dispatches frames to the listener. Mirrors the Kotlin BdipSession.
/// </summary>
public sealed class BdipSession : IDisposable
{
    private readonly Stream _stream;
    private readonly Action _closeTransport;
    private readonly IBdipListener _listener;
    private readonly HelloInfo _hello;
    private readonly BdipRole _role;
    private readonly CancellationTokenSource _cts = new();
    private readonly BlockingCollection<byte[]> _writeQueue = new(boundedCapacity: 1024);
    private readonly ConcurrentQueue<TaskCompletionSource<FileAck>> _ackWaiters = new();
    private readonly TaskCompletionSource<Frame> _peerHello = new();
    private readonly TaskCompletionSource _established = new();

    private volatile bool _closed;
    private long _lastSentAt;
    private long _lastReceivedAt;
    private Task? _readerTask;
    private Task? _writerTask;
    private Task? _heartbeatTask;

    public int ChunkAckTimeoutMs { get; set; } = 60_000;
    public bool IsEstablished => _established.Task.IsCompleted;

    public BdipSession(Stream stream, Action closeTransport,
        IBdipListener listener, HelloInfo hello, BdipRole role)
    {
        _stream = stream;
        _closeTransport = closeTransport;
        _listener = listener;
        _hello = hello;
        _role = role;
    }

    public void Start()
    {
        var now = Environment.TickCount64;
        _lastSentAt = now;
        _lastReceivedAt = now;
        _writerTask = Task.Run(WriterLoop);
        _readerTask = Task.Run(ReaderLoop);
        _heartbeatTask = Task.Run(HeartbeatLoop);
        _ = Task.Run(HandshakeAsync);
    }

    public Task AwaitEstablishedAsync(CancellationToken ct = default) =>
        _established.Task.WaitAsync(ct);

    // ------------------------------------------------------------- lifecycle

    public void Shutdown(string detail = "")
    {
        SendBye(Bdip.ByeShutdown, detail);
        TearDown("bye sent");
    }

    private void Fail(string message)
    {
        SendBye(Bdip.ByeProtocolError, message);
        TearDown(message);
    }

    private void SendBye(byte reason, string detail)
    {
        try
        {
            _writeQueue.TryAdd(new Frame(Bdip.TypeBye, ByeDetail.Encode(reason, detail)).Encode());
            // let the writer flush before the hard close
            Thread.Sleep(150);
        }
        catch { /* already closing */ }
    }

    private void TearDown(string reason)
    {
        if (_closed) return;
        _closed = true;
        _cts.Cancel();
        _writeQueue.CompleteAdding();
        while (_ackWaiters.TryDequeue(out var waiter))
            waiter.TrySetResult(new FileAck { Id = "", Error = "session closed" });
        _peerHello.TrySetCanceled();
        _established.TrySetCanceled();
        try { _closeTransport(); } catch { /* transport already gone */ }
        try { _listener.OnClosed(reason); } catch { /* listener must not throw here */ }
    }

    public void Dispose()
    {
        TearDown("disposed");
        _cts.Dispose();
    }

    // ------------------------------------------------------------- handshake

    private async Task HandshakeAsync()
    {
        try
        {
            if (_role == BdipRole.Initiator)
                await SendAsync(new Frame(Bdip.TypeHello, _hello.Encode()));

            var peerFrame = await _peerHello.Task.WaitAsync(
                TimeSpan.FromMilliseconds(Bdip.HelloTimeoutMs));
            var info = HelloInfo.Decode(peerFrame.Payload);
            if (!string.IsNullOrEmpty(_hello.Token) && !string.IsNullOrEmpty(info.Token)
                && _hello.Token != info.Token)
            {
                Fail("pairing token mismatch");
                return;
            }
            if (_role == BdipRole.Responder)
                await SendAsync(new Frame(Bdip.TypeHello, _hello.Encode()));
            _established.TrySetResult();
            _listener.OnEstablished(info);
        }
        catch (Exception)
        {
            if (!_closed) Fail("hello timeout");
        }
    }

    // ------------------------------------------------------------- loops

    private async Task ReaderLoop()
    {
        try
        {
            while (!_closed)
            {
                var frame = await FrameCodec.ReadAsync(_stream, _cts.Token);
                Interlocked.Exchange(ref _lastReceivedAt, Environment.TickCount64);
                if (!IsEstablished)
                {
                    switch (frame.Type)
                    {
                        case Bdip.TypeHello:
                            _peerHello.TrySetResult(frame);
                            continue;
                        case Bdip.TypeBye:
                            TearDown($"peer bye ({(frame.Payload.Length > 0 ? frame.Payload[0] : 0)})");
                            return;
                        default:
                            Fail($"expected HELLO first, got type 0x{frame.Type:x2}");
                            return;
                    }
                }
                switch (frame.Type)
                {
                    case Bdip.TypeHello:
                        break; // duplicate HELLO after establish: ignore
                    case Bdip.TypePing:
                        await SendAsync(new Frame(Bdip.TypePong, frame.Payload));
                        break;
                    case Bdip.TypeBye:
                        TearDown($"peer bye ({(frame.Payload.Length > 0 ? frame.Payload[0] : 0)})");
                        return;
                    case Bdip.TypeClipboardText:
                        _listener.OnClipboardText(FrameCodec.Utf8(frame.Payload));
                        break;
                    case Bdip.TypeClipboardImage:
                        _listener.OnClipboardImage(frame.Payload);
                        break;
                    case Bdip.TypeFileMeta:
                        _listener.OnFileMeta(FileMeta.Decode(frame.Payload));
                        break;
                    case Bdip.TypeFileChunk when frame.Payload.Length >= 4:
                        var index = BinaryPrimitives.ReadInt32LittleEndian(frame.Payload.AsSpan(0, 4));
                        var data = new byte[frame.Payload.Length - 4];
                        Array.Copy(frame.Payload, 4, data, 0, data.Length);
                        _listener.OnFileChunk(index, data);
                        break;
                    case Bdip.TypeFileAck:
                        var ack = FileAck.Decode(frame.Payload);
                        if (_ackWaiters.TryDequeue(out var waiter))
                            waiter.TrySetResult(ack);
                        _listener.OnFileAck(ack);
                        break;
                    default:
                        break; // unknown type: skip per spec
                }
            }
        }
        catch (ProtocolException ex)
        {
            if (!_closed) Fail(ex.Message);
        }
        catch (Exception ex)
        {
            if (!_closed) TearDown($"link lost: {ex.Message}");
        }
    }

    private async Task WriterLoop()
    {
        try
        {
            foreach (var bytes in _writeQueue.GetConsumingEnumerable(_cts.Token))
            {
                await _stream.WriteAsync(bytes, _cts.Token);
                await _stream.FlushAsync(_cts.Token);
                Interlocked.Exchange(ref _lastSentAt, Environment.TickCount64);
            }
        }
        catch (OperationCanceledException) { /* shutting down */ }
        catch (Exception) { /* reader will notice the dead link */ }
    }

    private async Task HeartbeatLoop()
    {
        try
        {
            while (!_closed)
            {
                await Task.Delay(1_000, _cts.Token);
                var now = Environment.TickCount64;
                if (now - _lastReceivedAt >= Bdip.RxDeadMs)
                {
                    TearDown($"rx dead after {Bdip.RxDeadMs} ms");
                    return;
                }
                if (now - _lastSentAt >= Bdip.PingIntervalMs)
                {
                    await SendAsync(new Frame(Bdip.TypePing, [0, 1, 2, 3, 4, 5, 6, 7]));
                    Interlocked.Exchange(ref _lastSentAt, now);
                }
            }
        }
        catch (OperationCanceledException) { /* shutting down */ }
    }

    // ------------------------------------------------------------- senders

    private Task SendAsync(Frame frame)
    {
        _writeQueue.TryAdd(frame.Encode());
        return Task.CompletedTask;
    }

    public bool SendClipboardText(string text)
    {
        var bytes = FrameCodec.Utf8(text);
        if (bytes.Length > Bdip.MaxText) return false;
        return _writeQueue.TryAdd(new Frame(Bdip.TypeClipboardText, bytes).Encode());
    }

    public bool SendClipboardImage(byte[] png)
    {
        if (png.Length > Bdip.MaxImage) return false;
        return _writeQueue.TryAdd(new Frame(Bdip.TypeClipboardImage, png).Encode());
    }

    public void SendFileAck(FileAck ack) =>
        _writeQueue.TryAdd(new Frame(Bdip.TypeFileAck, ack.Encode()).Encode());

    /// <summary>Streams a file with stop-and-wait chunking (spec §3.4).</summary>
    public async Task<long> SendFileAsync(FileMeta meta, Func<long, int, byte[]?> readChunk, Action<long, long>? onProgress = null)
    {
        await SendAsync(new Frame(Bdip.TypeFileMeta, meta.Encode()));
        long offset = 0;
        uint index = 0;
        while (offset < meta.Size)
        {
            var count = (int)Math.Min(Bdip.ChunkSize, meta.Size - offset);
            var chunk = readChunk(offset, count);
            if (chunk == null || chunk.Length != count) return -1;
            await SendAsync(new Frame(Bdip.TypeFileChunk,
                [.. FrameCodec.U32Le(index), .. chunk]));
            var tcs = new TaskCompletionSource<FileAck>(TaskCreationOptions.RunContinuationsAsynchronously);
            _ackWaiters.Enqueue(tcs);
            var ack = await tcs.Task.WaitAsync(TimeSpan.FromMilliseconds(ChunkAckTimeoutMs));
            if (ack.Error != null || ack.Id != meta.Id) return -1;
            offset += count;
            index++;
            onProgress?.Invoke(offset, meta.Size);
            if (ack.Done == true) return meta.Size; // receiver combined the final ack
        }
        var doneWaiter = new TaskCompletionSource<FileAck>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ackWaiters.Enqueue(doneWaiter);
        var done = await doneWaiter.Task.WaitAsync(TimeSpan.FromMilliseconds(ChunkAckTimeoutMs));
        return done.Done == true && done.Error == null ? meta.Size : -1;
    }
}
