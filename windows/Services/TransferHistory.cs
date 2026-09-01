using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace BluedropWindows.Services;

/// <summary>One completed transfer, shown in the history window.</summary>
public record TransferRecord
{
    [JsonPropertyName("id")] public string Id { get; init; } = Guid.NewGuid().ToString("N");
    [JsonPropertyName("timestamp")] public long Timestamp { get; init; } // epoch ms
    [JsonPropertyName("direction")] public string Direction { get; init; } = "sent"; // sent|received
    [JsonPropertyName("kind")] public string Kind { get; init; } = "file"; // file|image
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("size")] public long Size { get; init; }
    [JsonPropertyName("path")] public string? Path { get; init; } // on-disk payload; null for sent/text items
    [JsonPropertyName("mime")] public string Mime { get; init; } = "application/octet-stream";
    [JsonPropertyName("status")] public string Status { get; init; } = "ok"; // ok|failed; default keeps old files readable
    [JsonPropertyName("error")] public string? Error { get; init; }
}

/// <summary>
/// Transfer history persisted to %APPDATA%\Bluedrop\history.json. Records
/// file/image/text transfers in both directions, including failed/cancelled
/// ones (Status = "failed").
/// </summary>
public static class TransferHistory
{
    private const int MaxRecords = 500;

    private static readonly string FilePath =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Bluedrop", "history.json");

    private static readonly object Gate = new();
    private static List<TransferRecord> _records = [];

    /// <summary>Raised on the calling thread whenever the list changes.</summary>
    public static event Action? Changed;

    public static IReadOnlyList<TransferRecord> Records
    {
        get { lock (Gate) { return _records.ToList(); } }
    }

    public static void Load()
    {
        lock (Gate)
        {
            _records = LoadLocked();
        }
        Changed?.Invoke();
    }

    public static void Add(TransferRecord record)
    {
        lock (Gate)
        {
            _records = new[] { record }.Concat(_records).Take(MaxRecords).ToList();
            SaveLocked();
        }
        Changed?.Invoke();
    }

    /// <summary>Removes the record, keeping any stored payload.</summary>
    public static void Remove(string id)
    {
        lock (Gate)
        {
            _records = _records.Where(r => r.Id != id).ToList();
            SaveLocked();
        }
        Changed?.Invoke();
    }

    /// <summary>Removes the record and deletes its stored payload.</summary>
    public static bool RemoveWithFile(string id, out string? failedPath)
    {
        TransferRecord? record;
        lock (Gate) { record = _records.FirstOrDefault(r => r.Id == id); }
        failedPath = null;
        var deleted = true;
        if (record?.Path is { Length: > 0 } path)
        {
            try { if (File.Exists(path)) File.Delete(path); }
            catch { deleted = false; failedPath = path; }
        }
        Remove(id);
        return deleted;
    }

    public static void Clear()
    {
        lock (Gate)
        {
            _records = [];
            SaveLocked();
        }
        Changed?.Invoke();
    }

    private static List<TransferRecord> LoadLocked()
    {
        try
        {
            if (!File.Exists(FilePath)) return [];
            using var doc = JsonDocument.Parse(File.ReadAllBytes(FilePath));
            return doc.RootElement.EnumerateArray()
                .Select(e => e.Deserialize<TransferRecord>())
                .Where(r => r != null)
                .Cast<TransferRecord>()
                .ToList();
        }
        catch
        {
            return [];
        }
    }

    private static void SaveLocked()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(_records));
        }
        catch
        {
            // history is best-effort; never break transfers over it
        }
    }
}
