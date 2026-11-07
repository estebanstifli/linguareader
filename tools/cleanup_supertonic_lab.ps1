# Local cleanup of the retired Supertonic laboratory. Dry-run unless -Apply is supplied.
# The assistant's execution was blocked by the tool policy; this script has NOT been applied.
[CmdletBinding()]
param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\')
$relativeTargets = @(
    'experiments/supertonic-word-timing',
    '.models/aligners',
    '.models/audiocpp',
    '.models/align-corpus',
    '.models/release/vp-es.model.onnx',
    '.models/release/vp-es.vocab.json',
    '.models/release/vp-fr.model.onnx',
    '.models/release/vp-fr.vocab.json',
    '.models/release/w2v2-en.model.onnx',
    '.models/release/w2v2-en.vocab.json'
)
$requiredFiles = @(
    '.models/supertonic-3/aafc6e32416a594460b32413efc49d7fe4ce6d46/onnx/vector_estimator.onnx',
    'engine/supertonic/src/jvmTest/resources/word-timing-golden.json',
    'docs/SUPERTONIC_RESEARCH_HISTORY.md',
    'tools/build_supertonic_timing_graph.py'
)
foreach ($relative in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $workspaceRoot $relative) -PathType Leaf)) {
        throw "Required retained file missing: $relative"
    }
}
$sourceWeights = Join-Path $workspaceRoot $requiredFiles[0]
if ((Get-FileHash -LiteralPath $sourceWeights -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    '883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c') {
    throw 'The retained production weights failed integrity verification.'
}

# Complete the entire path/link audit before deleting the first entry.
$plan = @(foreach ($relative in $relativeTargets) {
    $candidate = Join-Path $workspaceRoot $relative
    if (-not (Test-Path -LiteralPath $candidate)) { continue }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    if (-not $resolved.StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Target outside workspace: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved -Force
    $ancestor = $item
    while ($null -ne $ancestor) {
        if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Redirected cleanup path: $($ancestor.FullName)"
        }
        if ($ancestor.FullName -eq $workspaceRoot) { break }
        # .Parent returns a native DirectoryInfo without the provider's PSIsContainer
        # property. Test its .NET type so walking more than one level remains valid.
        $ancestor = if ($ancestor -is [System.IO.DirectoryInfo]) { $ancestor.Parent } else { $ancestor.Directory }
    }
    if ($null -eq $ancestor) { throw "Target has no workspace ancestor: $resolved" }
    $entries = @($item)
    if ($item.PSIsContainer) { $entries += @(Get-ChildItem -LiteralPath $resolved -Force -Recurse) }
    if (@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -ne 0) {
        throw "Target contains links; review it manually: $resolved"
    }
    $files = @($entries | Where-Object { -not $_.PSIsContainer })
    [pscustomobject]@{ path=$resolved; files=$files.Count; bytes=($files | Measure-Object Length -Sum).Sum }
})
$plan | Format-Table -AutoSize
$bytes = ($plan | Measure-Object bytes -Sum).Sum
Write-Output "Selected bytes: $bytes. Production model, voices, source, tests and conclusions are retained."
if (-not $Apply) {
    Write-Output 'Dry-run only. To delete the listed targets, run this script locally with -Apply.'
    return
}
foreach ($entry in $plan) {
    Remove-Item -LiteralPath $entry.path -Recurse -Force
    if (Test-Path -LiteralPath $entry.path) { throw "Cleanup incomplete: $($entry.path)" }
}
$report = Join-Path $workspaceRoot 'artifacts/supertonic-internal-android/lab-cleanup.json'
[pscustomobject]@{
    completed=$true; deletedBytes=$bytes; deletedFiles=($plan | Measure-Object files -Sum).Sum; targets=$plan
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $report -Encoding utf8
Write-Output "Cleanup completed. Evidence: $report"
