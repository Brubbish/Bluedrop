namespace BluedropWindows.Models
{
    public class AppSettings
    {
        public AppTheme Theme { get; set; } = AppTheme.Light;
        public bool AutoSyncEnabled { get; set; } = false;
        public string? InboxFolder { get; set; } = null; // null = default below

        public static string DefaultInboxFolder =>
            System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads", "Bluedrop");
    }

    public enum AppTheme
    {
        Light,
        Dark
    }
}
