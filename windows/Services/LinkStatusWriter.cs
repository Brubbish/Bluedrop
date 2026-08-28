using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace BluedropWindows.Services;

/// <summary>
/// Phase 4 presence contract (Bluedrop TODO): the Windows app publishes its
/// Bluetooth link state to %LOCALAPPDATA%\Bluedrop\link.json on every change.
/// External consumers (e.g. Alfred) must treat a missing/stale file as
/// "no signal" and never block on it.
/// </summary>
public record LinkStatus
{
    [JsonPropertyName("connected")] public bool Connected { get; init; }
    [JsonPropertyName("since")] public string? Since { get; init; } // ISO-8601 UTC, null when disconnected
    [JsonPropertyName("peer_name")] public string? PeerName { get; init; }
    [JsonPropertyName("updated_at")] public string UpdatedAt { get; init; } = "";
}

public static class LinkStatusWriter
{
    private static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Bluedrop");

    private static readonly string FilePath = Path.Combine(Dir, "link.json");

    private static LinkStatus? _last;

    /// <summary>Writes the status when it actually changed; safe to call often.</summary>
    public static void Publish(bool connected, string? peerName, DateTime? sinceUtc)
    {
        var status = new LinkStatus
        {
            Connected = connected,
            PeerName = connected ? peerName : null,
            Since = connected ? sinceUtc?.ToUniversalTime().ToString("o") : null,
            UpdatedAt = DateTime.UtcNow.ToString("o"),
        };
        if (_last is not null && StatusEqual(_last, status)) return;
        _last = status;
        try
        {
            Directory.CreateDirectory(Dir);
            System.IO.File.WriteAllText(FilePath, JsonSerializer.Serialize(status, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // presence is best-effort; never crash the link over it
        }
    }

    public static void PublishDisconnected() => Publish(false, null, null);

    private static bool StatusEqual(LinkStatus a, LinkStatus b) =>
        a.Connected == b.Connected && a.PeerName == b.PeerName && a.Since == b.Since;
}
