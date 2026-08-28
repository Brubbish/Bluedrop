using System.IO;

namespace BluedropWindows.Protocol;

/// <summary>
/// Replays the bytes already consumed while sniffing for the BDIP magic,
/// then reads through to the wrapped stream. Writes pass straight through.
/// Lets the listener decide protocol after peeking without losing data.
/// </summary>
public sealed class PrefixStream(byte[] prefix, Stream inner) : Stream
{
    private int _prefixPos;

    public override bool CanRead => inner.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => inner.CanWrite;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct)
    {
        if (_prefixPos < prefix.Length)
        {
            var n = Math.Min(count, prefix.Length - _prefixPos);
            Array.Copy(prefix, _prefixPos, buffer, offset, n);
            _prefixPos += n;
            return n;
        }
        return await inner.ReadAsync(buffer, offset, count, ct);
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (_prefixPos < prefix.Length)
        {
            var n = Math.Min(count, prefix.Length - _prefixPos);
            Array.Copy(prefix, _prefixPos, buffer, offset, n);
            _prefixPos += n;
            return n;
        }
        return inner.Read(buffer, offset, count);
    }

    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        inner.WriteAsync(buffer, offset, count, ct);

    public override void Write(byte[] buffer, int offset, int count) =>
        inner.Write(buffer, offset, count);

    public override Task FlushAsync(CancellationToken ct) => inner.FlushAsync(ct);
    public override void Flush() => inner.Flush();

    protected override void Dispose(bool disposing)
    {
        if (disposing) inner.Dispose();
        base.Dispose(disposing);
    }
}
