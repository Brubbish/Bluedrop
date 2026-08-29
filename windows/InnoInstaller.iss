; Script for Bluedrop Installer
#define MyAppName "Bluedrop"
#ifndef AppVersion
#define AppVersion "1.5.0-dev"
#endif
#define MyAppPublisher "Brubbish"
#define MyAppVersion AppVersion
#define MyAppExeName "BluedropWindows.exe"

[Setup]
; NOTE: The value of AppId uniquely identifies this application
AppId={{156DCBB3-4DFE-44ED-AA91-736BABE00F9D}}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
UninstallDisplayIcon={app}\{#MyAppExeName}
; Architecture settings
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Output settings
OutputDir=.
#ifndef OutputBase
#define OutputBase "Bluedrop_Setup"
#endif
OutputBaseFilename={#OutputBase}
SetupIconFile=Assets\app.ico
Compression=lzma
SolidCompression=yes
WizardStyle=modern
; Add Windows startup option
DisableProgramGroupPage=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startupicon"; Description: "Start Bluedrop when Windows starts"; GroupDescription: "Startup options:"; Flags: unchecked

[Files]
; Include ALL files from the publish directory
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
Name: "{userstartup}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: startupicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
