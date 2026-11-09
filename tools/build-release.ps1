<#
Builds the signed release APK (R8 + resource shrinking).

Signing key: from the environment, nothing secret is in the repository (same scheme as MoeMusicPlayer).
  BAE_ANDROID_KEYSTORE            default C:\APP\@Android\Play KEY.jks
  BAE_ANDROID_KEY_ALIAS           default "anime theme key"
  BAE_ANDROID_KEYSTORE_PASSWORD   default: the "Password" line of C:\APP\@Android\Play KEY.txt
  BAE_ANDROID_KEY_PASSWORD        default: the keystore password
The values are set only for this process and restored afterwards. Without a keystore Gradle falls back to the
debug key (not accepted for a release).

Output: release\bahamut-anime-enhancer-<version>.apk, plus its SHA-256 and the apksigner verification.
#>
$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Get-NotesPassword([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    # keytool output saved by a Traditional Chinese Windows console (code page 950), or UTF-8.
    $text = try { [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes) } catch { [System.Text.Encoding]::GetEncoding(950).GetString($bytes) }
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '^\s*(?i:password|密碼)\s*[:：=]\s*(?<value>\S.*?)\s*$') { return $Matches['value'] }
    }
    return $null
}

Push-Location -LiteralPath $repoRoot
$names = 'BAE_ANDROID_KEYSTORE', 'BAE_ANDROID_KEY_ALIAS', 'BAE_ANDROID_KEYSTORE_PASSWORD', 'BAE_ANDROID_KEY_PASSWORD'
$saved = @{}
foreach ($n in $names) { $saved[$n] = [Environment]::GetEnvironmentVariable($n, 'Process') }
try {
    $keystore = if ($env:BAE_ANDROID_KEYSTORE) { $env:BAE_ANDROID_KEYSTORE } else { 'C:\APP\@Android\Play KEY.jks' }
    if (Test-Path -LiteralPath $keystore -PathType Leaf) {
        $password = if ($env:BAE_ANDROID_KEYSTORE_PASSWORD) { $env:BAE_ANDROID_KEYSTORE_PASSWORD } else { Get-NotesPassword 'C:\APP\@Android\Play KEY.txt' }
        if ([string]::IsNullOrEmpty($password)) { throw 'No keystore password: set BAE_ANDROID_KEYSTORE_PASSWORD.' }
        $env:BAE_ANDROID_KEYSTORE = $keystore
        $env:BAE_ANDROID_KEY_ALIAS = if ($saved['BAE_ANDROID_KEY_ALIAS']) { $saved['BAE_ANDROID_KEY_ALIAS'] } else { 'anime theme key' }
        $env:BAE_ANDROID_KEYSTORE_PASSWORD = $password
        if (-not $env:BAE_ANDROID_KEY_PASSWORD) { $env:BAE_ANDROID_KEY_PASSWORD = $password }
        Write-Output "Signing with the release key (alias $($env:BAE_ANDROID_KEY_ALIAS))."
    } else {
        Write-Warning 'No release keystore found: signing with the debug key.'
    }

    & (Join-Path $repoRoot 'gradlew.bat') assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "gradlew failed ($LASTEXITCODE)" }

    $built = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
    $versionName = (Select-String -LiteralPath (Join-Path $repoRoot 'app\build.gradle') -Pattern "versionName '([^']+)'").Matches[0].Groups[1].Value
    $outDir = Join-Path $repoRoot 'release'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $out = Join-Path $outDir "bahamut-anime-enhancer-$versionName.apk"
    Copy-Item -LiteralPath $built -Destination $out -Force
    Write-Output ("{0}  {1:N0} bytes" -f $out, (Get-Item -LiteralPath $out).Length)
    Write-Output ("SHA-256 {0}" -f (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash)

    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $apksigner = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Recurse -Filter apksigner.bat -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1
    if ($apksigner) { & $apksigner.FullName verify --print-certs -v $out } else { Write-Warning 'apksigner not found; signature not verified.' }
} finally {
    foreach ($n in $names) { [Environment]::SetEnvironmentVariable($n, $saved[$n], 'Process') }
    Pop-Location
}
