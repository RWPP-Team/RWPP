#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$GameRoot = 'D:\steam\steamapps\common\Rusted Warfare',
    [switch]$EnableClient
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ResolvedPath([string]$Path) {
    return [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath)
}

function Assert-ContainedPath([string]$Path, [string]$Root, [string]$Description) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd([char[]]@('\', '/'))
    if ($fullRoot.Length -eq 2 -and $fullRoot[1] -eq ':') { $fullRoot += '\' }
    $prefix = if ($fullRoot.EndsWith('\') -or $fullRoot.EndsWith('/')) { $fullRoot } else { $fullRoot + '\' }
    if (-not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description resolves outside its allowed directory: $fullPath"
    }
}

function Assert-DirectChildPath([string]$Path, [string]$Parent, [string]$Description) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd([char[]]@('\', '/'))
    $actualParent = [System.IO.Path]::GetDirectoryName($fullPath).TrimEnd([char[]]@('\', '/'))
    if (-not $actualParent.Equals($fullParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description is not a direct child of the intended directory: $fullPath"
    }
}

function Assert-NotReparsePoint([string]$Path, [string]$Description) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description must not be a symbolic link or reparse point: $Path"
    }
}

function Read-ExtensionMetadata([string]$ArchivePath, [switch]$RequireVersion) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $entries = @($zip.Entries | Where-Object { $_.FullName -ceq 'META-INF/extension.properties' })
        if ($entries.Count -eq 0) { return $null }
        if ($entries.Count -ne 1) { throw "Expected one META-INF/extension.properties in $ArchivePath" }

        $stream = $entries[0].Open()
        try {
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true)
            try { $contents = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
        }
        finally { $stream.Dispose() }

        $properties = @{}
        foreach ($line in ($contents -split "`r?`n")) {
            if ($line -match '^\s*[#!]' -or $line -match '^\s*$') { continue }
            $match = [System.Text.RegularExpressions.Regex]::Match($line, '^\s*([^#!\s:=]+)\s*(?:=|:)\s*(.*?)\s*$')
            if ($match.Success) { $properties[$match.Groups[1].Value] = $match.Groups[2].Value }
        }

        $id = [string]$properties['id']
        $version = [string]$properties['version']
        if ([string]::IsNullOrWhiteSpace($id)) { throw "Missing extension id in $ArchivePath" }
        if ($RequireVersion -and [string]::IsNullOrWhiteSpace($version)) { throw "Missing extension version in $ArchivePath" }
        return [pscustomobject]@{
            Id = $id.Trim()
            Version = $version.Trim()
            DependsOn = [string]$properties['dependsOn']
        }
    }
    finally { $zip.Dispose() }
}

function Get-NewestBuiltArchive([string]$Directory, [string]$Filter, [string]$Description) {
    $files = @(Get-ChildItem -LiteralPath $Directory -File -Filter $Filter -ErrorAction Stop)
    if ($files.Count -eq 0) { throw "No built $Description archive found in $Directory" }
    $candidate = $files | Sort-Object -Property @{ Expression = 'LastWriteTimeUtc'; Descending = $true }, @{ Expression = 'Name'; Descending = $false } | Select-Object -First 1
    return (Get-ResolvedPath $candidate.FullName)
}

function Set-PropertyLine([System.Collections.Generic.List[string]]$Lines, [string]$Key, [string]$Value) {
    $pattern = '^\s*' + [System.Text.RegularExpressions.Regex]::Escape($Key) + '\s*(?:=|:).*$'
    $indices = [System.Collections.Generic.List[int]]::new()
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ([System.Text.RegularExpressions.Regex]::IsMatch($Lines[$i], $pattern)) { $indices.Add($i) }
    }
    if ($indices.Count -eq 0) {
        $Lines.Add("$Key=$Value")
        return
    }
    $keepIndex = $indices[$indices.Count - 1]
    $Lines[$keepIndex] = "$Key=$Value"
    for ($j = $indices.Count - 2; $j -ge 0; $j--) { $Lines.RemoveAt($indices[$j]) }
}

function Enable-ClientInDisabledProperty([System.Collections.Generic.List[string]]$Lines) {
    $indices = [System.Collections.Generic.List[int]]::new()
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ([System.Text.RegularExpressions.Regex]::IsMatch($Lines[$i], '^\s*disabled\s*(?:=|:).*$')) { $indices.Add($i) }
    }
    if ($indices.Count -eq 0) { return }

    $index = $indices[$indices.Count - 1]
    $match = [System.Text.RegularExpressions.Regex]::Match($Lines[$index], '^\s*disabled\s*(?:=|:)\s*(.*)$')
    if (-not $match.Success) { return }
    $parts = @($match.Groups[1].Value.Split(','))
    $kept = @($parts | Where-Object { $_.Trim() -cne 'io.github.rwpp' })
    if ($kept.Count -ne $parts.Count) { $Lines[$index] = 'disabled=' + [string]::Join(',', [string[]]$kept) }
}

function Write-JsonFile([string]$Path, [object]$Value) {
    $json = ConvertTo-Json -InputObject $Value -Depth 12
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

function Move-ToBackup([string]$SourcePath, [string]$DestinationPath, [string]$ParentPath, [string]$Description) {
    Assert-DirectChildPath $DestinationPath $ParentPath $Description
    if (Test-Path -LiteralPath $DestinationPath) { throw "$Description already exists: $DestinationPath" }
    [void](Move-Item -LiteralPath $SourcePath -Destination $DestinationPath -ErrorAction Stop)
}

$projectRoot = Get-ResolvedPath (Join-Path $PSScriptRoot '..')
$gameRootResolved = Get-ResolvedPath $GameRoot
Assert-NotReparsePoint $gameRootResolved 'Game root'

$extensionsDirectory = Get-ResolvedPath (Join-Path $gameRootResolved 'extensions')
$dataDirectory = Get-ResolvedPath (Join-Path $gameRootResolved 'data')
Assert-ContainedPath $extensionsDirectory $gameRootResolved 'Extensions directory'
Assert-ContainedPath $dataDirectory $gameRootResolved 'Data directory'
Assert-DirectChildPath $extensionsDirectory $gameRootResolved 'Extensions directory'
Assert-DirectChildPath $dataDirectory $gameRootResolved 'Data directory'
Assert-NotReparsePoint $extensionsDirectory 'Extensions directory'
Assert-NotReparsePoint $dataDirectory 'Data directory'

$apiBuildDirectory = Get-ResolvedPath (Join-Path $projectRoot 'rwpp-api\build\extensions')
$clientBuildDirectory = Get-ResolvedPath (Join-Path $projectRoot 'rwpp-client\build\extensions')
Assert-ContainedPath $apiBuildDirectory $projectRoot 'API build directory'
Assert-ContainedPath $clientBuildDirectory $projectRoot 'Client build directory'
Assert-NotReparsePoint $apiBuildDirectory 'API build directory'
Assert-NotReparsePoint $clientBuildDirectory 'Client build directory'

$apiSourcePath = Get-NewestBuiltArchive $apiBuildDirectory 'rwpp-api-*-jvm.jar' 'RWPP API'
$clientSourcePath = Get-NewestBuiltArchive $clientBuildDirectory 'rwpp-client-*-jvm.jar' 'RWPP client'
Assert-ContainedPath $apiSourcePath $apiBuildDirectory 'API archive'
Assert-ContainedPath $clientSourcePath $clientBuildDirectory 'Client archive'
Assert-NotReparsePoint $apiSourcePath 'API archive'
Assert-NotReparsePoint $clientSourcePath 'Client archive'
Assert-DirectChildPath $apiSourcePath $apiBuildDirectory 'API archive'
Assert-DirectChildPath $clientSourcePath $clientBuildDirectory 'Client archive'

$apiMetadata = Read-ExtensionMetadata $apiSourcePath -RequireVersion
$clientMetadata = Read-ExtensionMetadata $clientSourcePath -RequireVersion
if ($null -eq $apiMetadata) { throw "API archive is missing META-INF/extension.properties: $apiSourcePath" }
if ($null -eq $clientMetadata) { throw "Client archive is missing META-INF/extension.properties: $clientSourcePath" }
if ($apiMetadata.Id -cne 'io.github.rwpp.api') { throw "API artifact has unexpected extension id '$($apiMetadata.Id)'" }
if ($clientMetadata.Id -cne 'io.github.rwpp') { throw "Client artifact has unexpected extension id '$($clientMetadata.Id)'" }
if ($apiMetadata.Version -notmatch '^[A-Za-z0-9][A-Za-z0-9._+-]*$' -or $clientMetadata.Version -notmatch '^[A-Za-z0-9][A-Za-z0-9._+-]*$') {
    throw 'An extension version contains characters unsafe for a canonical archive filename.'
}

$apiTargetName = "rwpp-api-$($apiMetadata.Version)-jvm.jar"
$clientTargetName = "rwpp-client-$($clientMetadata.Version)-jvm.jar"
$apiTargetPath = Join-Path $extensionsDirectory $apiTargetName
$clientTargetPath = Join-Path $extensionsDirectory $clientTargetName
Assert-DirectChildPath $apiTargetPath $extensionsDirectory 'API destination'
Assert-DirectChildPath $clientTargetPath $extensionsDirectory 'Client destination'
if ($apiTargetPath.Equals($clientTargetPath, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'API and client destinations unexpectedly collide.' }

$apiHash = (Get-FileHash -LiteralPath $apiSourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
$clientHash = (Get-FileHash -LiteralPath $clientSourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
$statePath = Join-Path $dataDirectory 'extension-state.properties'
Assert-DirectChildPath $statePath $dataDirectory 'Extension state file'
if ((Test-Path -LiteralPath $statePath) -and -not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
    throw "Extension state path is not a regular file: $statePath"
}
$stateExists = Test-Path -LiteralPath $statePath -PathType Leaf
if ($stateExists) { Assert-NotReparsePoint $statePath 'Extension state file' }
$stateEncoding = [System.Text.Encoding]::GetEncoding(28591)
$originalStateHash = if ($stateExists) { (Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }

$installedArchives = [System.Collections.Generic.List[object]]::new()
foreach ($file in (Get-ChildItem -LiteralPath $extensionsDirectory -File -Filter '*.jar' -ErrorAction Stop)) {
    Assert-DirectChildPath $file.FullName $extensionsDirectory 'Installed archive'
    Assert-NotReparsePoint $file.FullName 'Installed archive'
    $metadata = Read-ExtensionMetadata $file.FullName
    if ($null -ne $metadata -and $metadata.Id -cin @('io.github.rwpp.api', 'io.github.rwpp')) {
        $installedArchives.Add([pscustomobject]@{
            Id = $metadata.Id
            Version = $metadata.Version
            Name = $file.Name
            Path = [System.IO.Path]::GetFullPath($file.FullName)
            SHA256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        })
    }
}

foreach ($destination in @($apiTargetPath, $clientTargetPath)) {
    if (Test-Path -LiteralPath $destination) {
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            throw "Canonical destination is not a regular file: $destination"
        }
        $isTrackedArchive = @($installedArchives | Where-Object { $_.Path.Equals([System.IO.Path]::GetFullPath($destination), [System.StringComparison]::OrdinalIgnoreCase) }).Count -gt 0
        if (-not $isTrackedArchive) { throw "Canonical destination is occupied by a file that is not a recognized RWPP API/client archive: $destination" }
    }
}

$buildDirectory = Join-Path $projectRoot 'build'
if (-not (Test-Path -LiteralPath $buildDirectory -PathType Container)) { [void][System.IO.Directory]::CreateDirectory($buildDirectory) }
$buildDirectory = Get-ResolvedPath $buildDirectory
Assert-ContainedPath $buildDirectory $projectRoot 'Project build directory'
Assert-DirectChildPath $buildDirectory $projectRoot 'Project build directory'
Assert-NotReparsePoint $buildDirectory 'Project build directory'
$installManifestPath = Join-Path $buildDirectory 'client-extension-install.json'
Assert-DirectChildPath $installManifestPath $buildDirectory 'Install manifest'
if ((Test-Path -LiteralPath $installManifestPath) -and -not (Test-Path -LiteralPath $installManifestPath -PathType Leaf)) {
    throw "Install manifest path is not a regular file: $installManifestPath"
}
if (Test-Path -LiteralPath $installManifestPath -PathType Leaf) { Assert-NotReparsePoint $installManifestPath 'Existing install manifest' }

$runningGame = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -match 'Rusted Warfare|RWPP' -or
    ($_.ProcessName -match '^javaw?$' -and $_.MainWindowTitle -match 'Rusted Warfare|RWPP')
})
$nativeJavaGames = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'com\.corrodinggames\.rts\.java\.Main'
})
if ($runningGame.Count -gt 0 -or $nativeJavaGames.Count -gt 0) {
    # Loaded JARs can be locked by the JVM. Keep those archives intact and select the new
    # content-addressed archive for the next process instead of terminating a user's game.
    $liveBackup = Join-Path $gameRootResolved ('rwpp-backup-client-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-live')
    Assert-DirectChildPath $liveBackup $gameRootResolved 'Live-update backup'
    [void][IO.Directory]::CreateDirectory($liveBackup)
    $liveStateBackup = Join-Path $liveBackup 'extension-state.properties'
    if ($stateExists) { Copy-Item -LiteralPath $statePath -Destination $liveStateBackup }
    foreach ($archive in $installedArchives) {
        Copy-Item -LiteralPath $archive.Path -Destination (Join-Path $liveBackup $archive.Name)
    }
    $liveCreated = [Collections.Generic.List[string]]::new()
    $liveRows = [Collections.Generic.List[object]]::new()
    $liveStateCommitted = $false
    $liveStateHash = $null
    try {
        foreach ($artifact in @(
            @{ Id='io.github.rwpp.api'; Version=$apiMetadata.Version; Source=$apiSourcePath; Hash=$apiHash; Prefix='rwpp-api' },
            @{ Id='io.github.rwpp'; Version=$clientMetadata.Version; Source=$clientSourcePath; Hash=$clientHash; Prefix='rwpp-client' }
        )) {
            $same = $installedArchives | Where-Object { $_.Id -ceq $artifact.Id -and $_.SHA256 -eq $artifact.Hash } | Select-Object -First 1
            if ($same) { $destination = $same.Path }
            else {
                $name = "$($artifact.Prefix)-$($artifact.Version)-$($artifact.Hash.Substring(0,16))-jvm.jar"
                $destination = Join-Path $extensionsDirectory $name
                Assert-DirectChildPath $destination $extensionsDirectory 'Live-update archive'
                if (Test-Path -LiteralPath $destination) { throw "Live-update destination already exists: $destination" }
                $pending = $destination + '.pending'
                Assert-DirectChildPath $pending $extensionsDirectory 'Staged live-update archive'
                Copy-Item -LiteralPath $artifact.Source -Destination $pending
                if ((Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash.ToLowerInvariant() -ne $artifact.Hash) {
                    throw 'Live-update archive checksum mismatch.'
                }
                [IO.File]::Move($pending, $destination)
                $liveCreated.Add($destination)
            }
            if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $artifact.Hash) {
                throw "Installed live-update checksum mismatch: $destination"
            }
            $liveRows.Add([ordered]@{ id=$artifact.Id; version=$artifact.Version; source=$artifact.Source;
                destination=$destination; sha256=$artifact.Hash })
        }
        $liveLines = [Collections.Generic.List[string]]::new()
        if ($stateExists) {
            foreach ($line in [IO.File]::ReadAllLines($statePath, $stateEncoding)) { $liveLines.Add($line) }
            if ((Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $originalStateHash) {
                throw 'Extension state changed during live-update preparation.'
            }
        }
        Set-PropertyLine $liveLines 'selected.io.github.rwpp.api' $apiHash
        Set-PropertyLine $liveLines 'selected.io.github.rwpp' $clientHash
        if ($EnableClient) { Enable-ClientInDisabledProperty $liveLines }
        $livePendingState = Join-Path $dataDirectory ('extension-state.' + [Guid]::NewGuid().ToString('N') + '.pending')
        Assert-DirectChildPath $livePendingState $dataDirectory 'Staged live-update state'
        [IO.File]::WriteAllLines($livePendingState, [string[]]$liveLines, $stateEncoding)
        $liveStateHash = (Get-FileHash -LiteralPath $livePendingState -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::Move($livePendingState, $statePath, $true)
        $liveStateCommitted = $true
        $liveManifest = [ordered]@{ schemaVersion=1; installedAt=(Get-Date).ToString('o');
            gameRoot=$gameRootResolved; backupDirectory=$liveBackup; enabledClient=[bool]$EnableClient;
            stateFile=$statePath; stateSha256=$liveStateHash; restartRequired=$true;
            retainedLoadedArchives=@($installedArchives.ToArray()); extensions=@($liveRows.ToArray()) }
        Write-JsonFile (Join-Path $liveBackup 'backup-manifest.json') $liveManifest
        Write-JsonFile $installManifestPath $liveManifest
        [pscustomobject]@{ Status='InstalledAlongsideRunningGame'; RestartRequired=$true;
            Client_SHA256=$clientHash; Backup=$liveBackup; Manifest=$installManifestPath }
    } catch {
        if ($liveStateCommitted -and (Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash.ToLowerInvariant() -eq $liveStateHash -and $stateExists) {
            Copy-Item -LiteralPath $liveStateBackup -Destination ($statePath + '.rollback')
            [IO.File]::Move(($statePath + '.rollback'), $statePath, $true)
        }
        foreach ($created in $liveCreated) {
            Assert-DirectChildPath $created $extensionsDirectory 'Failed live-update archive'
            Move-ToBackup $created (Join-Path $liveBackup ([IO.Path]::GetFileName($created) + '.failed')) $liveBackup 'Failed live-update backup'
        }
        throw
    }
    return
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backupLeaf = "rwpp-backup-client-$stamp-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$backupRoot = Join-Path $gameRootResolved $backupLeaf
Assert-ContainedPath $backupRoot $gameRootResolved 'Backup directory'
Assert-DirectChildPath $backupRoot $gameRootResolved 'Backup directory'
if (Test-Path -LiteralPath $backupRoot) { throw "Backup directory already exists: $backupRoot" }

$quarantineDirectory = Join-Path $backupRoot 'quarantine'
$failedDirectory = Join-Path $backupRoot 'failed-install'
$stateBackupPath = Join-Path $backupRoot 'extension-state.properties'
$backupManifestPath = Join-Path $backupRoot 'backup-manifest.json'
$originalManifestBackupPath = Join-Path $backupRoot 'client-extension-install.previous.json'
$pendingApiPath = Join-Path $extensionsDirectory ($apiTargetName + '.' + [Guid]::NewGuid().ToString('N') + '.pending')
$pendingClientPath = Join-Path $extensionsDirectory ($clientTargetName + '.' + [Guid]::NewGuid().ToString('N') + '.pending')
$pendingStatePath = Join-Path $dataDirectory ('extension-state.properties.' + [Guid]::NewGuid().ToString('N') + '.pending')
foreach ($path in @($pendingApiPath, $pendingClientPath)) { Assert-DirectChildPath $path $extensionsDirectory 'Staged archive' }
Assert-DirectChildPath $pendingStatePath $dataDirectory 'Staged extension state'
if ((Test-Path -LiteralPath $pendingApiPath) -or (Test-Path -LiteralPath $pendingClientPath) -or (Test-Path -LiteralPath $pendingStatePath)) {
    throw 'A generated staging path is already occupied; refusing to overwrite it.'
}
Assert-DirectChildPath $stateBackupPath $backupRoot 'State backup'
Assert-DirectChildPath $backupManifestPath $backupRoot 'Backup manifest'
Assert-DirectChildPath $originalManifestBackupPath $backupRoot 'Previous install manifest backup'
Assert-ContainedPath $quarantineDirectory $backupRoot 'Quarantine directory'
Assert-DirectChildPath $quarantineDirectory $backupRoot 'Quarantine directory'
Assert-ContainedPath $failedDirectory $backupRoot 'Failed-install directory'
Assert-DirectChildPath $failedDirectory $backupRoot 'Failed-install directory'

$stateLines = [System.Collections.Generic.List[string]]::new()
if ($stateExists) {
    foreach ($line in [System.IO.File]::ReadAllLines($statePath, $stateEncoding)) { $stateLines.Add($line) }
}
Set-PropertyLine $stateLines 'selected.io.github.rwpp.api' $apiHash
Set-PropertyLine $stateLines 'selected.io.github.rwpp' $clientHash
if ($EnableClient) { Enable-ClientInDisabledProperty $stateLines }
$stateText = [string]::Join("`r`n", [string[]]$stateLines)
if ($stateLines.Count -gt 0) { $stateText += "`r`n" }
$updatedStateHash = $null

$manifestWritten = $false
$stateReplaced = $false
$installAttempts = [System.Collections.Generic.List[object]]::new()
$manifestPendingPath = $null
$plannedArchives = @($installedArchives | ForEach-Object {
    [pscustomobject]@{
        Id = $_.Id
        Version = $_.Version
        Name = $_.Name
        OriginalPath = $_.Path
        QuarantinePath = Join-Path $quarantineDirectory $_.Name
        SHA256 = $_.SHA256
    }
})
$backupManifest = [ordered]@{
    schemaVersion = 1
    status = 'prepared'
    createdAt = (Get-Date).ToString('o')
    gameRoot = $gameRootResolved
    backupRoot = $backupRoot
    sourceArtifacts = @(
        [ordered]@{ id = $apiMetadata.Id; version = $apiMetadata.Version; path = $apiSourcePath; sha256 = $apiHash },
        [ordered]@{ id = $clientMetadata.Id; version = $clientMetadata.Version; path = $clientSourcePath; sha256 = $clientHash }
    )
    quarantinedArchives = $plannedArchives
    stateBackup = if ($stateExists) { [ordered]@{ path = $stateBackupPath; sha256 = $originalStateHash } } else { $null }
    previousInstallManifest = if (Test-Path -LiteralPath $installManifestPath -PathType Leaf) { $originalManifestBackupPath } else { $null }
    enableClientRequested = [bool]$EnableClient
    installedArchives = @()
    installedStateSha256 = $null
    failure = $null
    rollbackIssues = @()
}

try {
    [void][System.IO.Directory]::CreateDirectory($backupRoot)
    Assert-NotReparsePoint $backupRoot 'Backup directory'
    [void][System.IO.Directory]::CreateDirectory($quarantineDirectory)
    Assert-ContainedPath $quarantineDirectory $backupRoot 'Quarantine directory'
    Assert-DirectChildPath $quarantineDirectory $backupRoot 'Quarantine directory'

    if ($stateExists) {
        Copy-Item -LiteralPath $statePath -Destination $stateBackupPath -ErrorAction Stop
        if ((Get-FileHash -LiteralPath $stateBackupPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $originalStateHash) {
            throw 'Extension state backup checksum mismatch.'
        }
    }
    if (Test-Path -LiteralPath $installManifestPath -PathType Leaf) {
        Copy-Item -LiteralPath $installManifestPath -Destination $originalManifestBackupPath -ErrorAction Stop
    }
    Write-JsonFile $backupManifestPath $backupManifest

    Copy-Item -LiteralPath $apiSourcePath -Destination $pendingApiPath -ErrorAction Stop
    Copy-Item -LiteralPath $clientSourcePath -Destination $pendingClientPath -ErrorAction Stop
    if ((Get-FileHash -LiteralPath $pendingApiPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $apiHash) { throw 'Staged API checksum mismatch.' }
    if ((Get-FileHash -LiteralPath $pendingClientPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $clientHash) { throw 'Staged client checksum mismatch.' }
    $stagedApiMetadata = Read-ExtensionMetadata $pendingApiPath -RequireVersion
    $stagedClientMetadata = Read-ExtensionMetadata $pendingClientPath -RequireVersion
    if ($stagedApiMetadata.Id -cne 'io.github.rwpp.api' -or $stagedClientMetadata.Id -cne 'io.github.rwpp') { throw 'Staged extension identity validation failed.' }

    [System.IO.File]::WriteAllText($pendingStatePath, $stateText, $stateEncoding)
    $updatedStateHash = (Get-FileHash -LiteralPath $pendingStatePath -Algorithm SHA256).Hash.ToLowerInvariant()

    if ($stateExists) {
        $currentStateHash = (Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($currentStateHash -ne $originalStateHash) { throw 'Extension state changed during install preparation; aborting without overwriting it.' }
    }
    elseif (Test-Path -LiteralPath $statePath) {
        throw 'An extension state file appeared during install preparation; aborting without overwriting it.'
    }

    foreach ($archive in $plannedArchives) {
        Assert-DirectChildPath $archive.OriginalPath $extensionsDirectory 'Existing archive'
        Assert-DirectChildPath $archive.QuarantinePath $quarantineDirectory 'Quarantined archive'
        if (Test-Path -LiteralPath $archive.QuarantinePath) { throw "Quarantine target already exists: $($archive.QuarantinePath)" }
        [void](Move-Item -LiteralPath $archive.OriginalPath -Destination $archive.QuarantinePath -ErrorAction Stop)
    }

    foreach ($item in @(
        [pscustomobject]@{ Path = $apiTargetPath; Pending = $pendingApiPath; Id = $apiMetadata.Id; Version = $apiMetadata.Version; SHA256 = $apiHash },
        [pscustomobject]@{ Path = $clientTargetPath; Pending = $pendingClientPath; Id = $clientMetadata.Id; Version = $clientMetadata.Version; SHA256 = $clientHash }
    )) {
        Assert-DirectChildPath $item.Path $extensionsDirectory 'Install destination'
        if (Test-Path -LiteralPath $item.Path) { throw "Install destination unexpectedly became occupied: $($item.Path)" }
        $installAttempts.Add($item)
        [System.IO.File]::Move($item.Pending, $item.Path)
    }

    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $originalStateHash) {
            throw 'Extension state changed before commit; refusing to overwrite the concurrent change.'
        }
        [System.IO.File]::Move($pendingStatePath, $statePath, $true)
    }
    else {
        [System.IO.File]::Move($pendingStatePath, $statePath)
    }
    $stateReplaced = $true

    foreach ($item in @(
        [pscustomobject]@{ Path = $apiTargetPath; Id = $apiMetadata.Id; Version = $apiMetadata.Version; SHA256 = $apiHash },
        [pscustomobject]@{ Path = $clientTargetPath; Id = $clientMetadata.Id; Version = $clientMetadata.Version; SHA256 = $clientHash }
    )) {
        if ((Get-FileHash -LiteralPath $item.Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.SHA256) { throw "Installed archive checksum mismatch: $($item.Path)" }
        $metadata = Read-ExtensionMetadata $item.Path -RequireVersion
        if ($metadata.Id -cne $item.Id -or $metadata.Version -cne $item.Version) { throw "Installed archive identity mismatch: $($item.Path)" }
    }
    if ((Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $updatedStateHash) { throw 'Installed extension state checksum mismatch.' }

    $backupManifest.status = 'succeeded'
    $backupManifest.completedAt = (Get-Date).ToString('o')
    $backupManifest.installedStateSha256 = $updatedStateHash
    $backupManifest.installedArchives = @(
        [ordered]@{ id = $apiMetadata.Id; version = $apiMetadata.Version; path = $apiTargetPath; sha256 = $apiHash },
        [ordered]@{ id = $clientMetadata.Id; version = $clientMetadata.Version; path = $clientTargetPath; sha256 = $clientHash }
    )
    Write-JsonFile $backupManifestPath $backupManifest

    $installManifest = [ordered]@{
        schemaVersion = 1
        installedAt = (Get-Date).ToString('o')
        gameRoot = $gameRootResolved
        backupDirectory = $backupRoot
        enabledClient = [bool]$EnableClient
        stateFile = $statePath
        stateSha256 = $updatedStateHash
        extensions = @(
            [ordered]@{ id = $apiMetadata.Id; version = $apiMetadata.Version; source = $apiSourcePath; destination = $apiTargetPath; sha256 = $apiHash },
            [ordered]@{ id = $clientMetadata.Id; version = $clientMetadata.Version; source = $clientSourcePath; destination = $clientTargetPath; sha256 = $clientHash }
        )
    }
    $manifestPendingPath = Join-Path $buildDirectory ('client-extension-install.json.' + [Guid]::NewGuid().ToString('N') + '.pending')
    Assert-DirectChildPath $manifestPendingPath $buildDirectory 'Staged install manifest'
    Write-JsonFile $manifestPendingPath $installManifest
    if (Test-Path -LiteralPath $installManifestPath -PathType Leaf) {
        [System.IO.File]::Move($manifestPendingPath, $installManifestPath, $true)
    }
    else {
        [System.IO.File]::Move($manifestPendingPath, $installManifestPath)
    }
    $manifestWritten = $true

    [pscustomobject]@{
        Status = 'Installed'
        API = $apiTargetPath
        Client = $clientTargetPath
        API_SHA256 = $apiHash
        Client_SHA256 = $clientHash
        EnabledClient = [bool]$EnableClient
        Backup = $backupRoot
        Manifest = $installManifestPath
    }
}
catch {
    $failureMessage = $_.Exception.Message
    $rollbackIssues = [System.Collections.Generic.List[string]]::new()

    if (Test-Path -LiteralPath $backupRoot -PathType Container) {
        try { [void][System.IO.Directory]::CreateDirectory($failedDirectory) }
        catch { $rollbackIssues.Add("Could not create failed-install quarantine: $($_.Exception.Message)") }

        if ($stateReplaced -and (Test-Path -LiteralPath $statePath -PathType Leaf)) {
            try {
                $currentHash = (Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($currentHash -eq $updatedStateHash) {
                    if ($stateExists -and (Test-Path -LiteralPath $stateBackupPath -PathType Leaf)) {
                        $restorePath = Join-Path $dataDirectory ('extension-state.properties.' + [Guid]::NewGuid().ToString('N') + '.rollback')
                        Copy-Item -LiteralPath $stateBackupPath -Destination $restorePath -ErrorAction Stop
                        [System.IO.File]::Move($restorePath, $statePath, $true)
                    }
                    else {
                        $failedStatePath = Join-Path $failedDirectory 'extension-state.properties.installed'
                        Move-ToBackup $statePath $failedStatePath $failedDirectory 'Failed state archive'
                    }
                }
                else { $rollbackIssues.Add('Extension state changed after commit; it was left untouched during rollback.') }
            }
            catch { $rollbackIssues.Add("Could not restore extension state: $($_.Exception.Message)") }
        }

        foreach ($item in $installAttempts) {
            if (-not (Test-Path -LiteralPath $item.Path -PathType Leaf)) { continue }
            try {
                $currentHash = (Get-FileHash -LiteralPath $item.Path -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($currentHash -ne $item.SHA256) {
                    $rollbackIssues.Add("Install destination changed after commit and was left untouched: $($item.Path)")
                    continue
                }
                $failedPath = Join-Path $failedDirectory ([System.IO.Path]::GetFileName($item.Path) + '.failed')
                Move-ToBackup $item.Path $failedPath $failedDirectory 'Failed archive'
            }
            catch { $rollbackIssues.Add("Could not quarantine failed archive $($item.Path): $($_.Exception.Message)") }
        }

        foreach ($archive in $installedArchives) {
            $quarantinePath = Join-Path $quarantineDirectory $archive.Name
            if (-not (Test-Path -LiteralPath $quarantinePath -PathType Leaf)) { continue }
            try {
                if ((Get-FileHash -LiteralPath $quarantinePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archive.SHA256) {
                    $rollbackIssues.Add("Quarantined archive checksum changed; backup retained at $quarantinePath")
                    continue
                }
                if (Test-Path -LiteralPath $archive.Path) {
                    $rollbackIssues.Add("Original archive path is occupied; backup retained at $quarantinePath")
                    continue
                }
                Assert-DirectChildPath $archive.Path $extensionsDirectory 'Restored archive'
                [void](Move-Item -LiteralPath $quarantinePath -Destination $archive.Path -ErrorAction Stop)
            }
            catch { $rollbackIssues.Add("Could not restore original archive $($archive.Path): $($_.Exception.Message)") }
        }

        foreach ($pendingPath in @($pendingApiPath, $pendingClientPath, $pendingStatePath)) {
            if (-not (Test-Path -LiteralPath $pendingPath -PathType Leaf)) { continue }
            try {
                $pendingName = [System.IO.Path]::GetFileName($pendingPath) + '.pending'
                $pendingBackup = Join-Path $failedDirectory $pendingName
                if ($pendingPath.StartsWith($extensionsDirectory, [System.StringComparison]::OrdinalIgnoreCase)) {
                    Move-ToBackup $pendingPath $pendingBackup $failedDirectory 'Pending archive'
                }
                elseif ($pendingPath.StartsWith($dataDirectory, [System.StringComparison]::OrdinalIgnoreCase)) {
                    Move-ToBackup $pendingPath $pendingBackup $failedDirectory 'Pending state'
                }
            }
            catch { $rollbackIssues.Add("Could not quarantine staged file $pendingPath`: $($_.Exception.Message)") }
        }

        if ($null -ne $manifestPendingPath -and (Test-Path -LiteralPath $manifestPendingPath -PathType Leaf)) {
            try {
                Assert-DirectChildPath $manifestPendingPath $buildDirectory 'Pending install manifest'
                Move-ToBackup $manifestPendingPath (Join-Path $failedDirectory 'client-extension-install.json.pending') $failedDirectory 'Pending install manifest'
            }
            catch { $rollbackIssues.Add("Could not quarantine staged install manifest: $($_.Exception.Message)") }
        }

        if ($null -ne $backupManifest) {
            $backupManifest.status = if ($rollbackIssues.Count -eq 0) { 'rolled-back' } else { 'rollback-incomplete' }
            $backupManifest.failedAt = (Get-Date).ToString('o')
            $backupManifest.failure = $failureMessage
            $backupManifest.rollbackIssues = @($rollbackIssues.ToArray())
            try { Write-JsonFile $backupManifestPath $backupManifest }
            catch { $rollbackIssues.Add("Could not update backup manifest: $($_.Exception.Message)") }
        }
    }

    if ($manifestWritten -and (Test-Path -LiteralPath $installManifestPath -PathType Leaf)) {
        # This path is reached only if a later operation failed; preserve the record in the game backup.
        try { Move-ToBackup $installManifestPath (Join-Path $backupRoot 'client-extension-install.failed.json') $backupRoot 'Failed install manifest' }
        catch { $rollbackIssues.Add("Could not preserve failed install manifest: $($_.Exception.Message)") }
        if (Test-Path -LiteralPath $originalManifestBackupPath -PathType Leaf) {
            try { Copy-Item -LiteralPath $originalManifestBackupPath -Destination $installManifestPath -ErrorAction Stop }
            catch { $rollbackIssues.Add("Could not restore previous install manifest: $($_.Exception.Message)") }
        }
    }

    if ($rollbackIssues.Count -gt 0) {
        throw "Client extension install failed: $failureMessage. Rollback needs attention: $([string]::Join('; ', $rollbackIssues.ToArray())) Backup: $backupRoot"
    }
    throw
}
