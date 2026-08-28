using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;

namespace BluedropWindows.Services;

/// <summary>
/// Clipboard image bridging: CLIPBOARD_IMAGE carries PNG bytes (spec §3.3),
/// while the WPF clipboard speaks BitmapSource. PNG → BitmapSource is direct;
/// BitmapSource → PNG re-encodes via PngBitmapEncoder.
/// </summary>
public static class ClipboardImageUtils
{
    public static byte[]? GetClipboardPng()
    {
        try
        {
            if (!Clipboard.ContainsImage()) return null;
            var source = Clipboard.GetImage();
            if (source == null) return null;
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(source));
            using var ms = new MemoryStream();
            encoder.Save(ms);
            return ms.ToArray();
        }
        catch
        {
            return null;
        }
    }

    public static void SetClipboardPng(byte[] png)
    {
        using var ms = new MemoryStream(png);
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.StreamSource = ms;
        bitmap.EndInit();
        bitmap.Freeze();
        Application.Current.Dispatcher.Invoke(() => Clipboard.SetImage(bitmap));
    }
}
