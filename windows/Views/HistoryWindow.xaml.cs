using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using BluedropWindows.Services;

namespace BluedropWindows.Views;

/// <summary>Read-only view over TransferHistory with per-entry removal.</summary>
public partial class HistoryWindow : Window
{
    private sealed class Row(TransferRecord record)
    {
        public TransferRecord Record => record;
        public string Name => record.Name;
        public string DirectionGlyph => record.Direction == "sent" ? "⬆" : "⬇";
        public string Detail =>
            $"{HumanSize(record.Size)} · {DirectionLabel()} · {KindLabel()} · " +
            $"{DateTimeOffset.FromUnixTimeMilliseconds(record.Timestamp).LocalDateTime:yyyy-MM-dd HH:mm}" +
            (record.Path is { Length: > 0 } ? $" · {record.Path}" : "");
        public Visibility DeleteFileVisibility =>
            record.Path is { Length: > 0 } ? Visibility.Visible : Visibility.Collapsed;

        private string DirectionLabel() => record.Direction == "sent" ? "sent" : "received";
        private string KindLabel() => record.Kind == "image" ? "image" : "file";

        private static string HumanSize(long bytes) => bytes switch
        {
            >= 1024 * 1024 => $"{bytes / (1024.0 * 1024.0):F1} MB",
            >= 1024 => $"{bytes / 1024.0:F0} KB",
            _ => $"{bytes} B",
        };
    }

    private readonly ObservableCollection<Row> _rows = [];

    public HistoryWindow()
    {
        InitializeComponent();
        HistoryList.ItemsSource = _rows;
        TransferHistory.Changed += OnHistoryChanged;
        Closed += (_, _) => TransferHistory.Changed -= OnHistoryChanged;
        Refresh();
    }

    private void OnHistoryChanged() => Dispatcher.Invoke(Refresh);

    private void Refresh()
    {
        _rows.Clear();
        foreach (var record in TransferHistory.Records)
        {
            _rows.Add(new Row(record));
        }
        EmptyHint.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void Remove_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is Row row)
        {
            TransferHistory.Remove(row.Record.Id);
        }
    }

    private void DeleteFile_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not Row row) return;
        var result = MessageBox.Show(
            $"\"{row.Record.Name}\" will be deleted from disk and removed from the list.\n" +
            $"This cannot be undone.",
            "Delete file",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (result != MessageBoxResult.Yes) return;

        if (!TransferHistory.RemoveWithFile(row.Record.Id, out var failedPath) && failedPath != null)
        {
            MessageBox.Show(
                "The entry was removed, but the file could not be deleted:\n" + failedPath,
                "Bluedrop", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(
            "All entries will be removed from the list. Stored files are kept.",
            "Clear history",
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (result == MessageBoxResult.Yes)
        {
            TransferHistory.Clear();
        }
    }
}
