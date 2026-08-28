using System.Text.Json;
using System.Text.Json.Serialization;

namespace BluedropWindows.Protocol;

/// <summary>JSON payload types carried inside BDIP frames (PROTOCOL.md §3).</summary>
public record HelloInfo
{
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("proto")] public int Proto { get; init; } = Bdip.Version;
    [JsonPropertyName("caps")] public List<string> Caps { get; init; } = ["text"];
    [JsonPropertyName("token")] public string Token { get; init; } = "";

    public byte[] Encode() => JsonSerializer.SerializeToUtf8Bytes(this);
    public static HelloInfo Decode(byte[] bytes) =>
        JsonSerializer.Deserialize<HelloInfo>(bytes) ?? new HelloInfo();
}

public record FileMeta
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("size")] public long Size { get; init; }
    [JsonPropertyName("mime")] public string Mime { get; init; } = "application/octet-stream";
    [JsonPropertyName("chunkSize")] public int ChunkSize { get; init; } = Bdip.ChunkSize;
    [JsonPropertyName("chunks")] public int Chunks { get; init; }

    public byte[] Encode() => JsonSerializer.SerializeToUtf8Bytes(this);
    public static FileMeta Decode(byte[] bytes) =>
        JsonSerializer.Deserialize<FileMeta>(bytes) ?? throw new ProtocolException("bad FILE_META");
}

public record FileAck
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("received")] public long Received { get; init; }
    [JsonPropertyName("done")] public bool? Done { get; init; }
    [JsonPropertyName("error")] public string? Error { get; init; }
    [JsonPropertyName("path")] public string? Path { get; init; }

    public byte[] Encode() => JsonSerializer.SerializeToUtf8Bytes(this);
    public static FileAck Decode(byte[] bytes) =>
        JsonSerializer.Deserialize<FileAck>(bytes) ?? new FileAck();
}

public static class ByeDetail
{
    public static byte[] Encode(byte reason, string message) =>
        [reason, .. FrameCodec.Utf8($"{{\"message\":{JsonSerializer.Serialize(message)}}}")];
}
