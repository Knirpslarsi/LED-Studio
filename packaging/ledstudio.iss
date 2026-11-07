; Inno Setup: Installation pro Benutzer, ohne Adminrechte. Der Autoupdater startet dieses Setup still.
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{7C1B3E52-4B7D-4E0A-9C39-5A6F1D2E8B41}
AppName=LED Studio
AppVersion={#AppVersion}
AppVerName=LED Studio {#AppVersion}
AppPublisher=Knirpslarsi
AppPublisherURL=https://github.com/Knirpslarsi/LED-Studio
DefaultDirName={localappdata}\Programs\LED Studio
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=LEDStudio-Setup-{#AppVersion}
SetupIconFile=ledstudio.ico
UninstallDisplayIcon={app}\LEDStudio.exe
UninstallDisplayName=LED Studio
Compression=lzma2/ultra
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force
RestartApplications=no

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"

[Tasks]
Name: "desktopicon"; Description: "Verknüpfung auf dem Desktop anlegen"; GroupDescription: "Zusätzlich:"

[InstallDelete]
; Alte Programmdateien entfernen, damit nach Updates keine veralteten Bibliotheken liegen bleiben.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\LED Studio"; Filename: "{app}\LEDStudio.exe"
Name: "{autodesktop}\LED Studio"; Filename: "{app}\LEDStudio.exe"; Tasks: desktopicon

[Registry]
; Beim Deinstallieren den Autostart-Eintrag entfernen, falls er gesetzt wurde.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "LEDStudio"; Flags: uninsdeletevalue dontcreatekey

[Run]
Filename: "{app}\LEDStudio.exe"; Description: "LED Studio starten"; Flags: nowait postinstall skipifsilent
; Nach einem stillen Update startet die App von selbst wieder.
Filename: "{app}\LEDStudio.exe"; Flags: nowait; Check: WizardSilent
