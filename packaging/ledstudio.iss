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

[Run]
Filename: "{app}\LEDStudio.exe"; Description: "LED Studio starten"; Flags: nowait postinstall skipifsilent
; Nach einem stillen Update startet die App von selbst wieder.
Filename: "{app}\LEDStudio.exe"; Flags: nowait; Check: WizardSilent

[Code]
// Autostart nur entfernen, wenn er auf diese App zeigt. Der gleichnamige Eintrag der alten Python-Version bleibt stehen.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Value: String;
begin
  if (CurUninstallStep = usPostUninstall)
    and RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'LEDStudio', Value)
    and (Pos('ledstudio.exe', Lowercase(Value)) > 0) then
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'LEDStudio');
end;
