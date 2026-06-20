$LocalRoot = Join-Path $env:USERPROFILE ".local"
$JdkHome = Get-ChildItem -LiteralPath (Join-Path $LocalRoot "jdk") -Directory -Filter "jdk-17*" |
    Select-Object -First 1 -ExpandProperty FullName
$AndroidSdk = Join-Path $LocalRoot "android-sdk"
$GradleHome = Join-Path $LocalRoot "gradle\gradle-8.10.2"

$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:Path = "$JdkHome\bin;$AndroidSdk\cmdline-tools\latest\bin;$AndroidSdk\platform-tools;$GradleHome\bin;$env:Path"

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
