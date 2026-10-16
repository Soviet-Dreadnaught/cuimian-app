# build.ps1 - 极简“催眠爱心”APK 一键构建（不依赖 Gradle）
# 流程: javac -> d8 -> aapt2 link -> aapt add classes.dex -> zipalign -> apksigner
$ErrorActionPreference = 'Stop'

# ---------- 定位工具链 ----------
function Find-Sdk {
    foreach ($p in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
                     'D:\xq-sdk',
                     "$env:LOCALAPPDATA\Android\Sdk",
                     'C:\Android\Sdk')) {
        if ($p -and (Test-Path (Join-Path $p 'build-tools'))) { return $p }
    }
    throw 'ANDROID_HOME / ANDROID_SDK_ROOT'
}
function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { return $env:JAVA_HOME }
    foreach ($pat in @("$env:ProgramFiles\Eclipse Adoptium\jdk-17*",
                       "$env:ProgramFiles\Java\jdk-17*",
                       "$env:ProgramFiles\Microsoft\jdk-17*")) {
        $c = Get-ChildItem $pat -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($c) { return $c.FullName }
    }
    throw '需要 JDK 17：请设置 JAVA_HOME'
}

$SDK  = Find-Sdk
$JH   = Find-Jdk
$BT   = (Get-ChildItem (Join-Path $SDK 'build-tools') -Directory |
         Sort-Object { [version]($_.Name -replace '[^\d.]') } -Descending | Select-Object -First 1).FullName
$PLAT = (Get-ChildItem (Join-Path $SDK 'platforms') -Directory | Select-Object -First 1).FullName
$AJAR = Join-Path $PLAT 'android.jar'
$env:PATH = "$JH\bin;$env:PATH"

Write-Host "SDK    : $SDK"
Write-Host "build-tools: $(Split-Path $BT -Leaf)"
Write-Host "platform   : $(Split-Path $PLAT -Leaf)"
Write-Host "JDK        : $JH"
Write-Host ''

$root = $PSScriptRoot
$src  = Join-Path $root 'src'
$wrk  = Join-Path $root 'build'
Remove-Item $wrk -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path (Join-Path $wrk 'classes'), (Join-Path $wrk 'dex') -Force | Out-Null

Write-Host '[1/6] javac'
$javaSrc = (Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object FullName)
& "$JH\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options `
    -classpath $AJAR -d (Join-Path $wrk 'classes') $javaSrc
if ($LASTEXITCODE) { throw 'javac failed' }

Write-Host '[2/6] d8'
$classes = (Get-ChildItem (Join-Path $wrk 'classes') -Recurse -Filter *.class | ForEach-Object FullName)
& "$BT\d8.bat" --release --min-api 21 --lib $AJAR --output (Join-Path $wrk 'dex') $classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Write-Host '[3/6] aapt2 link'
$unsigned = Join-Path $wrk 'app-unsigned.apk'
& "$BT\aapt2.exe" link -o $unsigned -I $AJAR --manifest (Join-Path $root 'AndroidManifest.xml') `
    --min-sdk-version 21 --target-sdk-version 34 --version-code 1 --version-name 1.0
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

Write-Host '[4/6] add classes.dex'
Push-Location (Join-Path $wrk 'dex')
& "$BT\aapt.exe" add $unsigned classes.dex
Pop-Location
if ($LASTEXITCODE) { throw 'aapt add failed' }

Write-Host '[5/6] zipalign'
$aligned = Join-Path $wrk 'app-aligned.apk'
& "$BT\zipalign.exe" -f -p 4 $unsigned $aligned
if ($LASTEXITCODE) { throw 'zipalign failed' }

Write-Host '[6/6] sign'
$ks = Join-Path $root 'debug.keystore'
if (-not (Test-Path $ks)) {
    & "$JH\bin\keytool.exe" -genkeypair -keystore $ks -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE) { throw 'keytool failed' }
}
$out = Join-Path $root 'HypnoHeart.apk'
Remove-Item $out -Force -ErrorAction SilentlyContinue
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $out $aligned
if ($LASTEXITCODE) { throw 'apksigner sign failed' }

& "$BT\apksigner.bat" verify --print-certs $out
Write-Host ''
Get-Item $out | Select-Object FullName, Length | Format-List
