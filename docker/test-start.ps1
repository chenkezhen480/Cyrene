$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'start.ps1')

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Assert-Throws([scriptblock]$action, [string]$messagePattern) {
    try { & $action; throw 'Expected an exception.' }
    catch { if ($_.Exception.Message -notmatch $messagePattern) { throw } }
}

function New-TestListeners([int]$count) {
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        $listeners = @()
        $firstPort = Get-Random -Minimum 43000 -Maximum 60000
        try {
            for ($index = 0; $index -lt $count; $index++) {
                $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, ($firstPort + $index))
                $listener.ExclusiveAddressUse = $true
                $listener.Start()
                $listeners += $listener
            }
            return $listeners
        }
        catch { foreach ($listener in $listeners) { $listener.Stop() } }
    }
    throw 'Could not reserve consecutive test ports.'
}

function New-TestConfig([int]$published) {
    return ('{"services":{"mysql":{"image":"mysql:8.0","ports":[{"target":3306,"published":"' +
        $published + '","host_ip":"127.0.0.1","protocol":"tcp"}]}}}') | ConvertFrom-Json
}

$checks = 0
$held = @(New-TestListeners 3)
$firstPort = $held[0].LocalEndpoint.Port
try {
    $held[1].Stop(); $held[2].Stop()
    $selection = Select-CyrenePorts (New-TestConfig $firstPort) @()
    try { Assert-True ($selection.mappings[0].port.published -eq ($firstPort + 1)) 'One occupied port must advance by one.'; $checks++ }
    finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }

    $held[1].Start()
    $selection = Select-CyrenePorts (New-TestConfig $firstPort) @()
    try { Assert-True ($selection.mappings[0].port.published -eq ($firstPort + 2)) 'Two occupied ports must advance by two.'; $checks++ }
    finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }
    $held[1].Stop()

    $ownContainer = [pscustomobject]@{ State = 'running'; Service = 'mysql'; Publishers = @(
        [pscustomobject]@{ URL = '127.0.0.1'; TargetPort = 3306; PublishedPort = $firstPort; Protocol = 'tcp' }) }
    $selection = Select-CyrenePorts (New-TestConfig $firstPort) @($ownContainer)
    try { Assert-True ($selection.mappings[0].port.published -eq $firstPort) 'The same running service mapping must be reused.'; $checks++ }
    finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }
    foreach ($mismatch in @('Service', 'TargetPort', 'Protocol', 'URL')) {
        $foreign = $ownContainer | ConvertTo-Json -Depth 5 | ConvertFrom-Json
        switch ($mismatch) {
            'Service' { $foreign.Service = 'redis' }
            'TargetPort' { $foreign.Publishers[0].TargetPort = 3307 }
            'Protocol' { $foreign.Publishers[0].Protocol = 'udp' }
            'URL' { $foreign.Publishers[0].URL = '0.0.0.0' }
        }
        $selection = Select-CyrenePorts (New-TestConfig $firstPort) @($foreign)
        try { Assert-True ($selection.mappings[0].port.published -eq ($firstPort + 1)) "A different $mismatch cannot claim this port."; $checks++ }
        finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }
    }
    $held[0].Stop()
    $config = New-TestConfig $firstPort
    $config.services | Add-Member redis ($config.services.mysql | ConvertTo-Json -Depth 5 | ConvertFrom-Json)
    $selection = Select-CyrenePorts $config @()
    try { Assert-True (($selection.mappings.port.published | Select-Object -Unique).Count -eq 2) 'One batch must reserve distinct host ports.'; $checks++ }
    finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }

    $config = New-TestConfig $firstPort
    $config.services | Add-Member cyrene-agent ([pscustomobject]@{ ports = @([pscustomobject]@{ published = "$firstPort" }) })
    $selection = Select-CyrenePorts $config @()
    try { Assert-True ($selection.mappings[0].port.published -eq ($firstPort + 1)) 'Middleware must not take the fixed Agent port.'; $checks++ }
    finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }
}
finally { foreach ($listener in $held) { $listener.Stop() } }

$lastPortListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 65535)
try {
    try { $lastPortListener.Start() } catch [System.Net.Sockets.SocketException] {
        if ($_.Exception.SocketErrorCode -notin @('AddressAlreadyInUse', 'AccessDenied')) { throw }
    }
    Assert-Throws { Select-CyrenePorts (New-TestConfig 65535) @() } 'No free TCP port'; $checks++
}
finally { $lastPortListener.Stop() }
Assert-Throws { Select-CyrenePorts (New-TestConfig 0) @() } 'Invalid.*published'; $checks++
Assert-Throws { Get-CyreneSelectedServices (New-TestConfig 3306) @('missing') } 'Unknown Compose service'; $checks++
$dependencyConfig = '{"services":{"mysql":{"depends_on":{"redis":{"condition":"service_healthy"}}},"redis":{},"milvus":{}}}' | ConvertFrom-Json
$selectedServices = @(Get-CyreneSelectedServices $dependencyConfig @('mysql'))
Assert-True ($selectedServices.Count -eq 2 -and 'redis' -in $selectedServices -and 'milvus' -notin $selectedServices) 'Selecting a service must include its dependencies only.'; $checks++

$held = @(New-TestListeners 2)
$firstPort = $held[0].LocalEndpoint.Port
$ipv6Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::IPv6Loopback, $firstPort)
try {
    foreach ($listener in $held) { $listener.Stop() }
    $ipv6Listener.Server.DualMode = $false
    $ipv6Listener.ExclusiveAddressUse = $true
    $ipv6Listener.Start()
    $config = New-TestConfig $firstPort
    $config.services.mysql.ports[0].PSObject.Properties.Remove('host_ip')
    $selection = Select-CyrenePorts $config @()
    try { Assert-True ($selection.mappings[0].port.published -eq ($firstPort + 1)) 'An IPv6-only occupant must also advance the default host port.'; $checks++ }
    finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }
}
finally { $ipv6Listener.Stop(); foreach ($listener in $held) { $listener.Stop() } }

$originalDocker = ${function:Invoke-CyreneDocker}
$originalDockerHost = $env:DOCKER_HOST
$originalDockerContext = $env:DOCKER_CONTEXT
$overrideFile = Join-Path $PSScriptRoot ('.ports-test-' + [guid]::NewGuid().ToString('N') + '.yml')
try {
    $env:DOCKER_HOST = 'tcp://remote.example:2375'; $env:DOCKER_CONTEXT = $null
    Assert-Throws { Assert-CyreneLocalDocker } 'remote Docker'; $checks++
    $env:DOCKER_HOST = 'npipe:////remoteServer/pipe/docker_engine'
    Assert-Throws { Assert-CyreneLocalDocker } 'remote Docker'; $checks++
    $env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
    Assert-CyreneLocalDocker; $checks++
    $env:DOCKER_HOST = $null
    $config = (Invoke-CyreneDocker @('compose', '--env-file', $cyreneDefaultEnvFile, '-f', $cyreneComposeFile, 'config', '--format', 'json')) -join "`n" | ConvertFrom-Json
    $selection = Select-CyrenePorts $config @() @('mysql')
    try {
        $override = ConvertTo-CyrenePortsOverride $selection.mappings
        Assert-True ($override -notmatch 'PASSWORD|MYSQL_ROOT_PASSWORD|environment:') 'The override must contain ports only.'; $checks++
        [System.IO.File]::WriteAllText($overrideFile, $override)
        $merged = (Invoke-CyreneDocker @('compose', '--env-file', $cyreneDefaultEnvFile, '-f', $cyreneComposeFile, '-f', $overrideFile, 'config', '--format', 'json')) -join "`n" | ConvertFrom-Json
        Assert-True ($merged.services.mysql.ports.Count -eq 1) '!override must replace rather than append the old mapping.'
        Assert-True ($merged.services.mysql.ports[0].published -eq $selection.mappings[0].port.published) 'Compose must retain the selected host port.'
        Assert-True ($merged.services.mysql.ports[0].target -eq 3306) 'The container target must not move.'; $checks++
    }
    finally { foreach ($listener in $selection.listeners) { $listener.Stop() } }

    $script:mockConfig = '{"services":{"mysql":{"ports":[{"target":3306,"published":"54321","host_ip":"127.0.0.1","protocol":"tcp"}]}}}'
    function Invoke-CyreneDocker([string[]]$arguments) {
        if ($arguments -contains 'context') { return '"npipe:////./pipe/dockerDesktopLinuxEngine"' }
        if ($arguments -contains 'config') { return $script:mockConfig }
        if ($arguments -contains 'ps') { return '' }
        if ($arguments -contains 'up') {
            Assert-True ($arguments -notcontains '--build' -and $arguments[-1] -eq 'mysql') 'NoBuild and the requested service must reach Compose up.'
            $script:lastOverride = $arguments[($arguments.IndexOf('up') - 1)]
            throw 'Simulated startup failure.'
        }
        throw 'Unexpected mocked Docker command.'
    }
    Assert-Throws { Start-CyreneCompose -Services mysql -NoBuild } 'Simulated startup failure'
    Assert-True (-not (Test-Path -LiteralPath $script:lastOverride)) 'A failed startup must remove its temporary override.'; $checks++
    $script:mockConfig = 'not json'
    Assert-Throws { Start-CyreneCompose -Services mysql -NoBuild } 'invalid JSON'; $checks++
}
finally {
    ${function:Invoke-CyreneDocker} = $originalDocker
    $env:DOCKER_HOST = $originalDockerHost
    $env:DOCKER_CONTEXT = $originalDockerContext
    if (Test-Path -LiteralPath $overrideFile) { Remove-Item -LiteralPath $overrideFile }
}
Write-Host "$checks Docker launcher checks passed; Docker startup was mocked."
