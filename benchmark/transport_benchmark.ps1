param(
    [string] $ProviderImage = "rest-sample-dubbo-provider:catalog-static-memory-20260715",
    [string] $ConsumerImage = "rest-sample-dubbo-consumer:native-static-memory-20260715",
    [string] $WrkImage = "williamyeh/wrk:latest",
    [int] $Concurrency = 64,
    [int] $Threads = 2,
    [int] $DurationSeconds = 10,
    [int] $RepeatCount = 3,
    [string] $ResultsDir = ""
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if ($RepeatCount -lt 1) {
    throw "RepeatCount must be >= 1."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\transport_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$Network = "reactor-dubbo-transport-bench"
$Provider = "rest-sample-dubbo-provider"
$Blocking = "reactor-dubbo-blocking"
$Demux = "reactor-dubbo-demux"
$Endpoint = "/api/v1/catalog/nested"
$MetricsEndpoint = "/api/v1/catalog/dubbo-metrics"

function Invoke-Docker {
    param([string[]] $Arguments, [switch] $IgnoreFailure)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $IgnoreFailure) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

function Convert-ToMiB {
    param([string] $Value)
    if ($Value.Trim() -notmatch "^([0-9.]+)(KiB|MiB|GiB)$") {
        throw "Unsupported Docker memory value: $Value"
    }
    $number = [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
    switch ($Matches[2]) {
        "KiB" { return $number / 1024.0 }
        "MiB" { return $number }
        "GiB" { return $number * 1024.0 }
    }
}

function Get-ContainerMemoryMiB {
    param([string] $Container)
    $usage = (Invoke-Docker @("stats", "--no-stream", "--format", "{{.MemUsage}}", $Container) | Select-Object -First 1)
    return Convert-ToMiB (($usage -split "/")[0].Trim())
}

function Invoke-InNetworkGet {
    param([string] $Container, [string] $Path)
    return (Invoke-Docker @(
        "run", "--rm", "--network", $Network,
        "alpine:3.20", "wget", "-qO-", "http://${Container}:8080${Path}"
    ) | Out-String).Trim()
}

function Wait-Ready {
    param([string] $Container)
    $deadline = (Get-Date).AddSeconds(90)
    do {
        try {
            $ready = Invoke-InNetworkGet $Container "/app/ready"
            if (-not [string]::IsNullOrWhiteSpace($ready)) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Container did not become ready before timeout."
}

function Wait-ProviderPort {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        & docker run --rm --network $Network alpine:3.20 sh -c "nc -z $Provider 20880" 2>$null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$Provider did not open port 20880 before timeout."
}

function Convert-LatencyToMs {
    param([double] $Value, [string] $Unit)
    switch ($Unit) {
        "us" { return $Value / 1000.0 }
        "ms" { return $Value }
        "s" { return $Value * 1000.0 }
        default { throw "Unsupported latency unit: $Unit" }
    }
}

function Start-Consumer {
    param([string] $Name, [string] $Transport)
    Invoke-Docker @(
        "run", "-d", "--name", $Name, "--network", $Network,
        "--memory", "128m", "--cpus", "1",
        "-e", "REACTOR_DUBBO_PROVIDERS=${Provider}:20880",
        "-e", "REACTOR_DUBBO_NATIVE_ASYNC_TRANSPORT=$Transport",
        "-e", "REACTOR_DUBBO_NATIVE_THREAD_STACK_BYTES=262144",
        $ConsumerImage
    ) | Out-Null
}

function Invoke-WrkRun {
    param([string] $Variant, [string] $Container, [int] $Run)
    $stdout = Join-Path $ResultsDir ("{0}-r{1}.txt" -f $Variant, $Run)
    $stderr = Join-Path $ResultsDir ("{0}-r{1}.err.txt" -f $Variant, $Run)
    $arguments = @(
        "run", "--rm", "--network", $Network, $WrkImage,
        "-t$Threads", "-c$Concurrency", "-d${DurationSeconds}s", "--latency",
        "http://${Container}:8080${Endpoint}"
    )
    $process = Start-Process -FilePath "docker" -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden
    $maxMemory = 0.0
    while (-not $process.HasExited) {
        try {
            $maxMemory = [Math]::Max($maxMemory, (Get-ContainerMemoryMiB $Container))
        } catch {
            # Final sample below handles a process-exit race.
        }
        Start-Sleep -Milliseconds 250
        $process.Refresh()
    }
    if ($process.ExitCode -ne 0) {
        throw "wrk failed for $Variant run $Run. See $stderr"
    }
    $maxMemory = [Math]::Max($maxMemory, (Get-ContainerMemoryMiB $Container))
    $text = Get-Content -LiteralPath $stdout -Raw
    $rpsMatch = [regex]::Match($text, "Requests/sec:\s+([0-9.]+)")
    $p99Match = [regex]::Match($text, "(?m)^\s*99%\s+([0-9.]+)(us|ms|s)")
    if (-not $rpsMatch.Success -or -not $p99Match.Success) {
        throw "Unable to parse wrk output: $stdout"
    }
    $non2xxMatch = [regex]::Match($text, "Non-2xx or 3xx responses:\s+([0-9]+)")
    $requestsMatch = [regex]::Match($text, "([0-9]+) requests in")
    if (-not $requestsMatch.Success) {
        throw "Unable to parse total request count: $stdout"
    }
    $rps = [double]::Parse($rpsMatch.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
    $p99 = [double]::Parse($p99Match.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
    $requests = [int64] $requestsMatch.Groups[1].Value
    $non2xx = if ($non2xxMatch.Success) { [int64] $non2xxMatch.Groups[1].Value } else { 0L }
    $successRatio = if ($requests -gt 0) { [double] ($requests - $non2xx) / $requests } else { 0.0 }
    return [PSCustomObject]@{
        variant = $Variant
        run = $Run
        concurrency = $Concurrency
        rps = [Math]::Round($rps, 2)
        useful_200_rps = [Math]::Round($rps * $successRatio, 2)
        p99_ms = [Math]::Round((Convert-LatencyToMs $p99 $p99Match.Groups[2].Value), 3)
        requests = $requests
        non_2xx = $non2xx
        non_2xx_pct = [Math]::Round(100.0 * (1.0 - $successRatio), 3)
        max_memory_mib = [Math]::Round($maxMemory, 3)
    }
}

function Assert-TransportMetrics {
    param([string] $Variant, $Metrics)
    if ($Variant -eq "blocking") {
        if ($Metrics.nativeDubboAsyncTransport -ne "blocking" -or
            $Metrics.nativeDubboBlockingEndpointPools -lt 1 -or
            $Metrics.nativeDubboAsyncEndpointPools -ne 0) {
            throw "Blocking transport allocated the wrong pool set: $($Metrics | ConvertTo-Json -Compress)"
        }
        return
    }
    if ($Metrics.nativeDubboAsyncTransport -ne "tokio-demux" -or
        $Metrics.nativeDubboBlockingEndpointPools -ne 0 -or
        $Metrics.nativeDubboAsyncEndpointPools -lt 1) {
        throw "Tokio demux transport allocated the wrong pool set: $($Metrics | ConvertTo-Json -Compress)"
    }
}

$rows = [System.Collections.Generic.List[object]]::new()
try {
    Invoke-Docker @("rm", "-f", $Provider, $Blocking, $Demux) -IgnoreFailure | Out-Null
    Invoke-Docker @("network", "rm", $Network) -IgnoreFailure | Out-Null
    Invoke-Docker @("network", "create", $Network) | Out-Null
    Invoke-Docker @(
        "run", "-d", "--name", $Provider, "--network", $Network,
        "--memory", "256m", "--cpus", "1", $ProviderImage
    ) | Out-Null
    Wait-ProviderPort
    Start-Consumer $Blocking "blocking"
    Start-Consumer $Demux "tokio-demux"
    Wait-Ready $Blocking
    Wait-Ready $Demux

    1..3 | ForEach-Object {
        Invoke-Docker @("run", "--rm", "--network", $Network, $WrkImage, "-t1", "-c16", "-d2s", "http://${Blocking}:8080${Endpoint}") | Out-Null
        Invoke-Docker @("run", "--rm", "--network", $Network, $WrkImage, "-t1", "-c16", "-d2s", "http://${Demux}:8080${Endpoint}") | Out-Null
    }

    $blockingMetricsJson = Invoke-InNetworkGet $Blocking $MetricsEndpoint
    $demuxMetricsJson = Invoke-InNetworkGet $Demux $MetricsEndpoint
    $blockingMetrics = $blockingMetricsJson | ConvertFrom-Json
    $demuxMetrics = $demuxMetricsJson | ConvertFrom-Json
    Assert-TransportMetrics "blocking" $blockingMetrics
    Assert-TransportMetrics "tokio-demux" $demuxMetrics
    $blockingIdle = Get-ContainerMemoryMiB $Blocking
    $demuxIdle = Get-ContainerMemoryMiB $Demux

    for ($run = 1; $run -le $RepeatCount; $run++) {
        $order = if (($run % 2) -eq 1) {
            @(
                [PSCustomObject]@{ Variant = "blocking"; Container = $Blocking },
                [PSCustomObject]@{ Variant = "tokio-demux"; Container = $Demux }
            )
        } else {
            @(
                [PSCustomObject]@{ Variant = "tokio-demux"; Container = $Demux },
                [PSCustomObject]@{ Variant = "blocking"; Container = $Blocking }
            )
        }
        foreach ($item in $order) {
            $rows.Add((Invoke-WrkRun $item.Variant $item.Container $run))
        }
    }

    $rows | Export-Csv -LiteralPath (Join-Path $ResultsDir "results.csv") -NoTypeInformation -Encoding utf8
    $summary = foreach ($group in ($rows | Group-Object variant)) {
        [PSCustomObject]@{
            variant = $group.Name
            runs = $group.Count
            avg_rps = [Math]::Round((($group.Group | Measure-Object rps -Average).Average), 2)
            avg_useful_200_rps = [Math]::Round((($group.Group | Measure-Object useful_200_rps -Average).Average), 2)
            avg_p99_ms = [Math]::Round((($group.Group | Measure-Object p99_ms -Average).Average), 3)
            total_non_2xx = ($group.Group | Measure-Object non_2xx -Sum).Sum
            avg_non_2xx_pct = [Math]::Round((($group.Group | Measure-Object non_2xx_pct -Average).Average), 3)
            avg_max_memory_mib = [Math]::Round((($group.Group | Measure-Object max_memory_mib -Average).Average), 3)
        }
    }
    $summary | Export-Csv -LiteralPath (Join-Path $ResultsDir "summary.csv") -NoTypeInformation -Encoding utf8

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# Dubbo Native Transport Benchmark")
    $lines.Add("")
    $lines.Add("| Transport | Runs | Avg RPS | Useful 200 RPS | Avg p99 ms | Non-2xx % | Avg max memory MiB |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|")
    foreach ($row in ($summary | Sort-Object variant)) {
        $lines.Add("| $($row.variant) | $($row.runs) | $($row.avg_rps) | $($row.avg_useful_200_rps) | $($row.avg_p99_ms) | $($row.avg_non_2xx_pct) | $($row.avg_max_memory_mib) |")
    }
    $lines.Add("")
    $lines.Add("- Blocking idle memory MiB: $([Math]::Round($blockingIdle, 3))")
    $lines.Add("- Tokio demux idle memory MiB: $([Math]::Round($demuxIdle, 3))")
    $lines.Add("- Blocking metrics: $blockingMetricsJson")
    $lines.Add("- Tokio demux metrics: $demuxMetricsJson")
    $lines | Set-Content -LiteralPath (Join-Path $ResultsDir "report.md") -Encoding utf8
    Write-Host "Dubbo transport report: $(Join-Path $ResultsDir 'report.md')"
} finally {
    foreach ($container in @($Provider, $Blocking, $Demux)) {
        try {
            Invoke-Docker @("logs", $container) -IgnoreFailure |
                Set-Content -LiteralPath (Join-Path $ResultsDir "$container.log") -Encoding utf8
        } catch {
            # Cleanup must continue even when a container was never created.
        }
    }
    Invoke-Docker @("rm", "-f", $Provider, $Blocking, $Demux) -IgnoreFailure | Out-Null
    Invoke-Docker @("network", "rm", $Network) -IgnoreFailure | Out-Null
}
