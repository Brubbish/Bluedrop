using InTheHand.Net.Sockets;
using Newtonsoft.Json;
using System.Collections.Concurrent;
using System.Collections.ObjectModel;
using System.IO;
using System.Text;
using System.Windows;
using BluedropWindows.Models;
using BluedropWindows.Protocol;
using BluedropWindows.Services;
using BluedropWindows.ViewModels;

namespace BluedropWindows.Views
{
    public partial class MainWindow : Window
    {
        private readonly MainViewModel _viewModel;
        private readonly ObservableCollection<BluetoothDeviceInfo> _devices = [];
        private BluetoothListener? _listener;
        private CancellationTokenSource? _cancellationTokenSource;
        private bool _isServiceRunning = false;
        private static readonly Guid ServiceUuid = new("8ce255c0-200a-11e0-ac64-0800200c9a66");

        // BDIP sessions by peer address
        private readonly ConcurrentDictionary<string, BdipSession> _sessions = new();
        private readonly ConcurrentDictionary<string, string> _peerNames = new();
        private DateTime? _linkSince;

        // Outbound transfers serialize process-wide (spec: one at a time)
        private readonly SemaphoreSlim _sendQueue = new(1, 1);

        // Incoming transfer state per session key
        private sealed class IncomingTransfer
        {
            public required FileMeta Meta;
            public required FileStream Stream;
            public long Received;
            public int ExpectedIndex;
            public required string TempPath;
        }
        private readonly ConcurrentDictionary<string, IncomingTransfer> _incoming = new();

        // Clipboard monitoring for auto-sync
        private System.Windows.Threading.DispatcherTimer? _clipboardMonitorTimer;
        private string? _lastClipboardContent;
        private byte[]? _lastClipboardImageHash;
        private DateTime _lastAutoSyncTime = DateTime.MinValue;
        private volatile bool _isReceivingClipboard = false;

        public MainWindow()
        {
            InitializeComponent();
            _viewModel = new MainViewModel();
            _viewModel.CloseSettingsRequested += (s, e) => CloseSettings();
            DataContext = _viewModel;
            DevicesListView.ItemsSource = _devices;

            RefreshDevicesButton.Click += RefreshDevicesButton_Click;
            StartServiceButton.Click += StartServiceButton_Click;
            StopServiceButton.Click += StopServiceButton_Click;
            ShareButton.Click += ShareButton_Click;
            SendFileButton.Click += SendFileButton_Click;
            ThemeToggleButton.Click += ThemeToggleButton_Click;
            HistoryButton.Click += HistoryButton_Click;
            SettingsButton.Click += SettingsButton_Click;

            LoadPairedDevices();

            DevicesListView.SelectionChanged += (s, e) =>
            {
                var hasSelection = DevicesListView.SelectedItems.Count > 0;
                ShareButton.IsEnabled = hasSelection;
                SendFileButton.IsEnabled = hasSelection;
            };

            PreviewKeyDown += (s, e) =>
            {
                if (e.Key == System.Windows.Input.Key.Escape && SettingsOverlay.Visibility == Visibility.Visible)
                {
                    CloseSettings();
                }
            };

            TransferHistory.Load();

            _clipboardMonitorTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(500)
            };
            _clipboardMonitorTimer.Tick += ClipboardMonitor_Tick;
            _clipboardMonitorTimer.Start();

            Closed += (_, _) => StopListeningService();
        }

        private void HistoryButton_Click(object sender, RoutedEventArgs e)
        {
            var window = new Views.HistoryWindow { Owner = this };
            window.Show();
        }

        private void ThemeToggleButton_Click(object sender, RoutedEventArgs e)
        {
            _viewModel.ToggleTheme();
        }

        private void SettingsButton_Click(object sender, RoutedEventArgs e)
        {
            var storyboard = (System.Windows.Media.Animation.Storyboard)FindResource("OpenSettingsStoryboard");
            storyboard.Begin();
        }

        private void SettingsOverlay_MouseDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            if (e.OriginalSource == sender)
            {
                CloseSettings();
            }
        }

        private void CloseSettings()
        {
            var storyboard = (System.Windows.Media.Animation.Storyboard)FindResource("CloseSettingsStoryboard");
            storyboard.Begin();
        }

        private void SetStatus(string text) =>
            Dispatcher.Invoke(() => StatusTextBlock.Text = text);

        // ---------------------------------------------------------------- device list

        private void RefreshDevicesButton_Click(object sender, RoutedEventArgs e)
        {
            LoadPairedDevices();
        }

        private void LoadPairedDevices()
        {
            try
            {
                StatusTextBlock.Text = "Loading paired devices...";
                _devices.Clear();

                using var client = new BluetoothClient();
                foreach (var dev in client.PairedDevices)
                {
                    _devices.Add(dev);
                }

                StatusTextBlock.Text = $"Paired devices loaded: {_devices.Count}";
            }
            catch (Exception ex)
            {
                StatusTextBlock.Text = $"Error loading devices: {ex.Message}";
                MessageBox.Show($"Failed to load Bluetooth devices: {ex.Message}", "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        // ---------------------------------------------------------------- service start/stop

        private async void StartServiceButton_Click(object sender, RoutedEventArgs e)
        {
            if (_isServiceRunning) return;

            try
            {
                StartServiceButton.IsEnabled = false;
                RefreshDevicesButton.IsEnabled = false;
                StopServiceButton.IsEnabled = true;

                StatusTextBlock.Text = "Starting service...";

                _cancellationTokenSource = new CancellationTokenSource();
                var token = _cancellationTokenSource.Token;

                _listener = new BluetoothListener(ServiceUuid)
                {
                    ServiceName = "Bluedrop",
                };
                _listener.Start();
                _isServiceRunning = true;

                StatusTextBlock.Text = "Service: Listening for bluetooth devices...";

                await Task.Run(() => ListeningLoop(token), token);
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                MessageBox.Show($"Error starting Bluetooth service: {ex.Message}", "Error", MessageBoxButton.OK, MessageBoxImage.Error);
                StopListeningService();
            }
        }

        private void StopServiceButton_Click(object sender, RoutedEventArgs e)
        {
            StopListeningService();
        }

        private void StopListeningService()
        {
            try
            {
                _cancellationTokenSource?.Cancel();
                _listener?.Stop();
                _isServiceRunning = false;

                foreach (var session in _sessions.Values)
                    session.Shutdown();
                _sessions.Clear();
                PublishLinkStatus();

                Dispatcher.Invoke(() =>
                {
                    StartServiceButton.IsEnabled = true;
                    RefreshDevicesButton.IsEnabled = true;
                    StopServiceButton.IsEnabled = false;
                    StatusTextBlock.Text = "Service stopped";
                });
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() =>
                {
                    StatusTextBlock.Text = $"Error stopping service: {ex.Message}";
                });
            }
        }

        // ---------------------------------------------------------------- listener + legacy sniffing

        private async Task ListeningLoop(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                BluetoothClient? client = null;
                try
                {
                    if (_listener == null)
                    {
                        Thread.Sleep(1000);
                        continue;
                    }

                    client = _listener.AcceptBluetoothClient();
                    var stream = client.GetStream();

                    // sniff 4 bytes: BDIP magic vs legacy ClipSync JSON
                    var peek = new byte[4];
                    var n = 0;
                    while (n < 4)
                    {
                        var r = await stream.ReadAsync(peek.AsMemory(n, 4 - n), token);
                        if (r <= 0) break;
                        n += r;
                    }
                    if (n < 4)
                    {
                        client.Close();
                        continue;
                    }

                    var address = TryPeerAddress(client);
                    if (peek.SequenceEqual(Bdip.Magic))
                    {
                        AcceptBdipSession(client, peek, address);
                    }
                    else
                    {
                        HandleLegacyConnection(client, peek);
                    }
                }
                catch (OperationCanceledException) { }
                catch (Exception ex)
                {
                    client?.Close();
                    if (!token.IsCancellationRequested)
                    {
                        SetStatus($"Listener error: {ex.Message}");
                        Thread.Sleep(1000);
                    }
                }
            }
        }

        private void AcceptBdipSession(BluetoothClient client, byte[] peek, string address)
        {
            var stream = client.GetStream();
            var prefixStream = new PrefixStream(peek, stream);
            var name = TryGetRemoteName(client, address);

            var existing = _sessions.GetValueOrDefault(address);
            if (existing is { IsEstablished: true })
            {
                // spec §1: one session per peer; refuse extras
                SetStatus($"Refusing extra connection from {name}");
                try { client.Close(); } catch { }
                return;
            }
            existing?.Shutdown("replaced");

            var session = new BdipSession(
                prefixStream,
                () => { try { client.Close(); } catch { } },
                new WindowListener(this, address),
                new HelloInfo { Name = Environment.MachineName, Caps = ["text", "image", "file"] },
                BdipRole.Responder);
            _sessions[address] = session;
            _peerNames[address] = name;
            session.Start();
            SetStatus($"Connected to: {name}");
        }

        private static string TryPeerAddress(BluetoothClient client)
        {
            try
            {
                var ep = client.Client?.RemoteEndPoint as InTheHand.Net.BluetoothEndPoint;
                return ep?.Address.ToString() ?? "unknown";
            }
            catch { return "unknown"; }
        }

        private static string TryGetRemoteName(BluetoothClient client, string address)
        {
            try { return client.RemoteMachineName is { Length: > 0 } n ? n : address; }
            catch { return address; }
        }

        /// <summary>ClipSync v1.3 clients: one JSON line then close.</summary>
        private void HandleLegacyConnection(BluetoothClient client, byte[] peek)
        {
            try
            {
                using var reader = new StreamReader(new PrefixStream(peek, client.GetStream()), Encoding.UTF8);
                var jsonText = reader.ReadLine() ?? "";
                var clipboardData = JsonConvert.DeserializeObject<ClipboardData>(jsonText);
                if (clipboardData?.Clip != null)
                {
                    ApplyRemoteText(clipboardData.Clip);
                }
            }
            catch (Exception ex)
            {
                SetStatus($"Legacy receive error: {ex.Message}");
            }
            finally
            {
                try { client.Close(); } catch { }
            }
        }

        // ---------------------------------------------------------------- session event handling

        private sealed class WindowListener(MainWindow window, string address) : IBdipListener
        {
            public void OnEstablished(HelloInfo peer)
            {
                window._peerNames[address] = peer.Name.Length > 0 ? peer.Name : address;
                window._linkSince ??= DateTime.UtcNow;
                window.PublishLinkStatus();
                window.SetStatus($"Session established with {peer.Name}");
            }

            public void OnClipboardText(string text) => window.ApplyRemoteText(text);

            public void OnClipboardImage(byte[] png)
            {
                window._isReceivingClipboard = true;
                try
                {
                    ClipboardImageUtils.SetClipboardPng(png);
                    window._lastClipboardImageHash = HashBytes(png);
                    window.SetStatus("Received clipboard image");
                    TransferHistory.Add(new TransferRecord
                    {
                        Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                        Direction = "received",
                        Kind = "image",
                        Name = "clipboard image",
                        Size = png.Length,
                        Mime = "image/png",
                    });
                    NotificationHelper.ShowSimpleNotification("Bluedrop", "Image received and copied to clipboard");
                }
                finally
                {
                    window._isReceivingClipboard = false;
                }
            }

            public void OnFileMeta(FileMeta meta) => window.StartIncomingTransfer(address, meta);

            public void OnFileChunk(int index, byte[] data) => window.HandleIncomingChunk(address, index, data);

            public void OnFileAck(FileAck ack) { /* progress surface only for now */ }

            public void OnClosed(string reason)
            {
                if (window._sessions.TryGetValue(address, out var session))
                {
                    window._sessions.TryRemove(new KeyValuePair<string, BdipSession>(address, session));
                }
                window.CleanupIncoming(address);
                window.PublishLinkStatus();
                window.SetStatus($"Disconnected ({reason})");
            }
        }

        private static byte[]? HashBytes(byte[] data)
        {
            try
            {
                return System.Security.Cryptography.SHA256.HashData(data);
            }
            catch
            {
                return null;
            }
        }

        private void ApplyRemoteText(string text)
        {
            Dispatcher.Invoke(() =>
            {
                _isReceivingClipboard = true;
                try
                {
                    Clipboard.SetText(text);
                    _lastClipboardContent = text;
                    _lastClipboardImageHash = null;
                    StatusTextBlock.Text = "Received clipboard text & copied!";
                    NotificationHelper.ShowSimpleNotification("Bluedrop", $"ClipText Received: \n {TruncateText(text, 50)}");
                }
                finally
                {
                    _isReceivingClipboard = false;
                }
            });
        }

        // ---------------------------------------------------------------- incoming files

        private string InboxFolder()
        {
            var settings = SettingsService.LoadSettings();
            var folder = string.IsNullOrWhiteSpace(settings.InboxFolder)
                ? AppSettings.DefaultInboxFolder
                : settings.InboxFolder;
            Directory.CreateDirectory(folder);
            return folder;
        }

        private void StartIncomingTransfer(string address, FileMeta meta)
        {
            try
            {
                if (meta.Size <= 0 || meta.Size > Bdip.MaxFile)
                {
                    _sessions[address]?.SendFileAck(new FileAck { Id = meta.Id, Error = "bad size" });
                    return;
                }
                CleanupIncoming(address);
                var tempPath = Path.Combine(Path.GetTempPath(), $"bluedrop-{meta.Id}.part");
                var transfer = new IncomingTransfer
                {
                    Meta = meta,
                    Stream = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None),
                    TempPath = tempPath,
                };
                _incoming[address] = transfer;
                SetStatus($"Receiving {meta.Name}…");
            }
            catch (Exception ex)
            {
                _sessions[address]?.SendFileAck(new FileAck { Id = meta.Id, Error = ex.Message });
            }
        }

        private void HandleIncomingChunk(string address, int index, byte[] data)
        {
            if (!_incoming.TryGetValue(address, out var transfer)) return;
            var session = _sessions.GetValueOrDefault(address);
            lock (transfer)
            {
                if (index != transfer.ExpectedIndex)
                {
                    FailIncoming(address, transfer, $"out-of-order chunk {index}");
                    return;
                }
                try
                {
                    transfer.Stream.Write(data, 0, data.Length);
                    transfer.Received += data.Length;
                    transfer.ExpectedIndex++;
                    if (transfer.Meta.Size > 0)
                    {
                        var pct = (int)(transfer.Received * 100 / transfer.Meta.Size);
                        SetStatus($"Receiving {transfer.Meta.Name}… {pct}%");
                    }
                    session?.SendFileAck(new FileAck { Id = transfer.Meta.Id, Received = transfer.Received });
                    if (transfer.Received >= transfer.Meta.Size)
                    {
                        FinalizeIncoming(address, transfer, session);
                    }
                }
                catch (Exception ex)
                {
                    FailIncoming(address, transfer, ex.Message);
                }
            }
        }

        private void FinalizeIncoming(string address, IncomingTransfer transfer, BdipSession? session)
        {
            try
            {
                transfer.Stream.Dispose();
                var targetDir = InboxFolder();
                var target = Path.Combine(targetDir, MakeUniqueFileName(targetDir, transfer.Meta.Name));
                File.Move(transfer.TempPath, target);
                _incoming.TryRemove(address, out _);
                session?.SendFileAck(new FileAck
                {
                    Id = transfer.Meta.Id,
                    Received = transfer.Received,
                    Done = true,
                    Path = target,
                });
                SetStatus($"Received {transfer.Meta.Name}");
                NotificationHelper.ShowSimpleNotification("Bluedrop", $"File received: {target}");
                TransferHistory.Add(new TransferRecord
                {
                    Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    Direction = "received",
                    Kind = "file",
                    Name = transfer.Meta.Name,
                    Size = transfer.Meta.Size,
                    Path = target,
                    Mime = transfer.Meta.Mime,
                });
            }
            catch (Exception ex)
            {
                FailIncoming(address, transfer, ex.Message);
            }
        }

        private void FailIncoming(string address, IncomingTransfer transfer, string error)
        {
            try { transfer.Stream.Dispose(); } catch { }
            try { File.Delete(transfer.TempPath); } catch { }
            _incoming.TryRemove(address, out _);
            _sessions.GetValueOrDefault(address)?.SendFileAck(new FileAck { Id = transfer.Meta.Id, Error = error });
            SetStatus($"Transfer failed: {error}");
        }

        private void CleanupIncoming(string address)
        {
            if (_incoming.TryRemove(address, out var transfer))
            {
                try { transfer.Stream.Dispose(); } catch { }
                try { File.Delete(transfer.TempPath); } catch { }
            }
        }

        private static string MakeUniqueFileName(string dir, string name)
        {
            if (!File.Exists(Path.Combine(dir, name))) return name;
            var stem = Path.GetFileNameWithoutExtension(name);
            var ext = Path.GetExtension(name);
            for (var i = 1; ; i++)
            {
                var candidate = $"{stem} ({i}){ext}";
                if (!File.Exists(Path.Combine(dir, candidate))) return candidate;
            }
        }

        // ---------------------------------------------------------------- sending

        private IEnumerable<BluetoothDeviceInfo> SelectedDevices() =>
            DevicesListView.SelectedItems.Cast<BluetoothDeviceInfo>();

        private async void ShareButton_Click(object sender, RoutedEventArgs e)
        {
            ShareButton.IsEnabled = false;
            try
            {
                var ok = await Task.Run(SendClipboardNowAsync);
                if (!ok) SetStatus("Clipboard is empty or no reachable device");
            }
            finally
            {
                ShareButton.IsEnabled = true;
            }
        }

        /// <summary>Sends the current clipboard: image if present, else text.</summary>
        private async Task<bool> SendClipboardNowAsync()
        {
            byte[]? png = null;
            string? text = null;
            await Dispatcher.InvokeAsync(() =>
            {
                if (Clipboard.ContainsImage()) png = ClipboardImageUtils.GetClipboardPng();
                if (png == null && Clipboard.ContainsText()) text = Clipboard.GetText();
            });
            if (png == null && string.IsNullOrEmpty(text)) return false;

            var sent = 0;
            var targets = SelectedDevices().ToList();
            foreach (var device in targets)
            {
                var session = await SessionOrDialAsync(device);
                if (session == null) continue;
                var success = png != null ? session.SendClipboardImage(png) : session.SendClipboardText(text!);
                if (success) sent++;
            }
            if (sent > 0)
            {
                SetStatus($"Clipboard shared with {sent}/{targets.Count} devices");
                if (png != null)
                {
                    TransferHistory.Add(new TransferRecord
                    {
                        Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                        Direction = "sent",
                        Kind = "image",
                        Name = "clipboard image",
                        Size = png.Length,
                        Mime = "image/png",
                    });
                }
                if (text != null) _lastClipboardContent = text;
                if (png != null) _lastClipboardImageHash = HashBytes(png);
            }
            return sent > 0;
        }

        private async void SendFileButton_Click(object sender, RoutedEventArgs e)
        {
            var dialog = new Microsoft.Win32.OpenFileDialog { Multiselect = true };
            if (dialog.ShowDialog() != true) return;
            await SendFilesAsync(dialog.FileNames);
        }

        private void Window_DragOver(object sender, DragEventArgs e)
        {
            e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop)
                ? DragDropEffects.Copy
                : DragDropEffects.None;
            e.Handled = true;
        }

        private async void Window_Drop(object sender, DragEventArgs e)
        {
            if (e.Data.GetData(DataFormats.FileDrop) is string[] files && files.Length > 0)
            {
                await SendFilesAsync(files);
            }
        }

        private async Task SendFilesAsync(IEnumerable<string> files)
        {
            SendFileButton.IsEnabled = false;
            try
            {
                var targets = SelectedDevices().ToList();
                if (targets.Count == 0)
                {
                    SetStatus("Select a device first");
                    return;
                }
                foreach (var file in files)
                {
                    var info = new FileInfo(file);
                    if (!info.Exists || info.Length > Bdip.MaxFile)
                    {
                        SetStatus($"Skipped {Path.GetFileName(file)} (missing or over {Bdip.MaxFile / (1024 * 1024)} MiB)");
                        continue;
                    }
                    var sessions = new List<BdipSession>();
                    foreach (var device in targets)
                    {
                        var session = await SessionOrDialAsync(device);
                        if (session != null) sessions.Add(session);
                    }
                    if (sessions.Count == 0)
                    {
                        SetStatus("No reachable device");
                        return;
                    }
                    await _sendQueue.WaitAsync();
                    try
                    {
                        var meta = new FileMeta
                        {
                            Id = Guid.NewGuid().ToString("N"),
                            Name = info.Name,
                            Size = info.Length,
                            Mime = MimeFor(info.Extension),
                            Chunks = (int)((info.Length + Bdip.ChunkSize - 1) / Bdip.ChunkSize),
                        };
                        SetStatus($"Sending {info.Name}…");
                        var okCount = 0;
                        foreach (var session in sessions)
                        {
                            await using var fs = File.OpenRead(file);
                            var sent = await session.SendFileAsync(
                                meta,
                                (offset, count) =>
                                {
                                    fs.Position = offset;
                                    var buf = new byte[count];
                                    var read = 0;
                                    while (read < count)
                                    {
                                        var n = fs.Read(buf, read, count - read);
                                        if (n <= 0) break;
                                        read += n;
                                    }
                                    return read == count ? buf : null;
                                },
                                (done, total) => SetStatus(
                                    $"Sending {info.Name}… {(int)(done * 100 / total)}%"));
                            if (sent > 0) okCount++;
                        }
                        if (okCount > 0)
                        {
                            TransferHistory.Add(new TransferRecord
                            {
                                Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                                Direction = "sent",
                                Kind = "file",
                                Name = info.Name,
                                Size = info.Length,
                                Mime = meta.Mime,
                            });
                        }
                        SetStatus(okCount > 0
                            ? $"Sent {info.Name} to {okCount}/{sessions.Count} device(s)"
                            : $"Failed to send {info.Name}");
                    }
                    finally
                    {
                        _sendQueue.Release();
                    }
                }
            }
            finally
            {
                SendFileButton.IsEnabled = true;
            }
        }

        private static string MimeFor(string extension) => extension.ToLowerInvariant() switch
        {
            ".png" => "image/png",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".gif" => "image/gif",
            ".webp" => "image/webp",
            ".pdf" => "application/pdf",
            ".txt" => "text/plain",
            ".zip" => "application/zip",
            _ => "application/octet-stream",
        };

        /// <summary>Returns the live session for a device, dialing if needed.</summary>
        private async Task<BdipSession?> SessionOrDialAsync(BluetoothDeviceInfo device)
        {
            var address = device.DeviceAddress.ToString();
            if (_sessions.TryGetValue(address, out var existing) && existing.IsEstablished)
                return existing;

            try
            {
                var client = new BluetoothClient();
                using var connectCts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                await Task.Run(() => client.Connect(device.DeviceAddress, ServiceUuid), connectCts.Token);

                var stream = client.GetStream();
                var session = new BdipSession(
                    stream,
                    () => { try { client.Close(); } catch { } },
                    new WindowListener(this, address),
                    new HelloInfo { Name = Environment.MachineName, Caps = ["text", "image", "file"] },
                    BdipRole.Initiator);
                _sessions[address] = session;
                _peerNames[address] = device.DeviceName;
                session.Start();
                using var helloCts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                await session.AwaitEstablishedAsync(helloCts.Token);
                return session;
            }
            catch (Exception ex)
            {
                SetStatus($"Connect to {device.DeviceName} failed: {ex.Message}");
                return null;
            }
        }

        // ---------------------------------------------------------------- auto-sync

        private void ClipboardMonitor_Tick(object? sender, EventArgs e)
        {
            var settings = SettingsService.LoadSettings();
            if (!settings.AutoSyncEnabled) return;
            if (_isReceivingClipboard) return;
            if (DevicesListView.SelectedItems.Count == 0) return;

            try
            {
                if (Clipboard.ContainsImage())
                {
                    var png = ClipboardImageUtils.GetClipboardPng();
                    if (png != null)
                    {
                        var hash = HashBytes(png);
                        if (!ByteEquals(hash, _lastClipboardImageHash) && DebounceOk())
                        {
                            _ = SendClipboardNowAsync();
                        }
                        return;
                    }
                }
                if (!Clipboard.ContainsText()) return;

                var currentClipboard = Clipboard.GetText();
                if (string.IsNullOrWhiteSpace(currentClipboard)) return;
                if (currentClipboard == _lastClipboardContent) return;
                if (DebounceOk())
                {
                    _lastClipboardContent = currentClipboard;
                    _ = SendClipboardNowAsync();
                }
            }
            catch
            {
                // Ignore clipboard access errors
            }
        }

        private bool DebounceOk()
        {
            if ((DateTime.Now - _lastAutoSyncTime).TotalMilliseconds >= 500)
            {
                _lastAutoSyncTime = DateTime.Now;
                return true;
            }
            return false;
        }

        private static bool ByteEquals(byte[]? a, byte[]? b)
        {
            if (a == null || b == null) return a == b;
            return a.SequenceEqual(b);
        }

        // ---------------------------------------------------------------- presence

        private void PublishLinkStatus()
        {
            var live = _sessions.Values.FirstOrDefault(s => s.IsEstablished);
            if (live == null)
            {
                _linkSince = null;
                LinkStatusWriter.PublishDisconnected();
                return;
            }
            var address = _sessions.FirstOrDefault(kv => kv.Value == live).Key;
            var name = _peerNames.GetValueOrDefault(address) ?? "peer";
            LinkStatusWriter.Publish(true, name, _linkSince ?? DateTime.UtcNow);
        }

        private static string TruncateText(string text, int maxLength)
        {
            if (string.IsNullOrEmpty(text) || text.Length <= maxLength)
                return text;

            return string.Concat(text.AsSpan(0, maxLength), "...");
        }
    }
}
