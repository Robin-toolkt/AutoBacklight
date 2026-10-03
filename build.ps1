# Builds AdaptiveBrightness.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Libraries: libs\*.aar (TensorFlow Lite). Model: assets\movenet_multipose.tflite.
$ErrorActionPreference = 'Stop'
$root   = $PSScriptRoot
$jdk    = "$env:LOCALAPPDATA\Android\jdk17"
$sdk    = "$env:LOCALAPPDATA\Android\Sdk"
$bt     = "$sdk\build-tools\34.0.0"
$jar    = "$sdk\platforms\android-34\android.jar"
$out    = "$root\build"
$abi    = 'arm64-v8a'

function Run($exe, [string[]]$argList) {
    & $exe @argList
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed ($LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes", "$out\dex", "$out\aar", "$out\native\lib\$abi" | Out-Null

# 0. Unpack the .aar libraries: classes.jar for compiling/dexing, .so files for the APK
Add-Type -AssemblyName System.IO.Compression.FileSystem
$libJars = @()
foreach ($aar in Get-ChildItem "$root\libs" -Filter *.aar) {
    $zip = [IO.Compression.ZipFile]::OpenRead($aar.FullName)
    try {
        foreach ($e in $zip.Entries) {
            if ($e.FullName -eq 'classes.jar') {
                $dest = "$out\aar\$($aar.BaseName).jar"
                [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
                $libJars += $dest
            } elseif ($e.FullName -like "jni/$abi/*.so") {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($e, "$out\native\lib\$abi\$($e.Name)", $true)
            }
        }
    } finally { $zip.Dispose() }
}
$classpath = (@($jar) + $libJars) -join ';'

# 1. Resources + manifest + assets (the model must stay uncompressed so it can be memory-mapped)
Run "$bt\aapt2.exe" @('compile', '--dir', "$root\res", '-o', "$out\res.zip")
Run "$bt\aapt2.exe" @('link', '-o', "$out\base.apk", '-I', $jar, '--manifest', "$root\AndroidManifest.xml",
    '--min-sdk-version', '26', '--target-sdk-version', '34', '--version-code', '2', '--version-name', '1.1',
    '-A', "$root\assets", '-0', 'tflite', "$out\res.zip")

# 2. Java -> class files
$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Run "$jdk\bin\javac.exe" (@('--release', '8', '-encoding', 'UTF-8', '-nowarn',
    '-classpath', $classpath, '-d', "$out\classes") + $sources)

# 3. class files + library jars -> classes.dex
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Run "$jdk\bin\java.exe" (@('-cp', "$bt\lib\d8.jar", 'com.android.tools.r8.D8', '--release',
    '--min-api', '26', '--lib', $jar, '--output', "$out\dex") + $classes + $libJars)

# 4. Add classes.dex and native libraries to the APK, align, sign
Push-Location "$out\dex"
try { Run "$bt\aapt.exe" @('add', "$out\base.apk", 'classes.dex') } finally { Pop-Location }
Push-Location "$out\native"
try {
    foreach ($so in Get-ChildItem "lib\$abi" -Filter *.so) {
        Run "$bt\aapt.exe" @('add', "$out\base.apk", "lib/$abi/$($so.Name)")
    }
} finally { Pop-Location }
Run "$bt\zipalign.exe" @('-f', '-p', '4', "$out\base.apk", "$out\aligned.apk")

$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    Run "$jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $ks, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'autobacklight', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-dname', 'CN=AutoBacklight')
}
Run "$jdk\bin\java.exe" @('-jar', "$bt\lib\apksigner.jar", 'sign', '--ks', $ks, '--ks-pass', 'pass:android',
    '--out', "$root\AdaptiveBrightness.apk", "$out\aligned.apk")
Write-Host "Built $root\AdaptiveBrightness.apk"
