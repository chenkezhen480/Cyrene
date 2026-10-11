param([string[]]$services, [switch]$noBuild, [string]$envFile)

$cyreneComposeFile = Join-Path $PSScriptRoot 'docker-compose.yml'
$cyreneDefaultEnvFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'

function Invoke-CyreneDocker([string[]]$arguments) {
    $result = & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE)." }
    return $result
}

function Assert-CyreneLocalDocker {
    if ($env:DOCKER_HOST -and -not $env:DOCKER_CONTEXT) { $endpoint = $env:DOCKER_HOST }
    else {
        $arguments = @('context', 'inspect')
        if ($env:DOCKER_CONTEXT) { $arguments += $env:DOCKER_CONTEXT }
        $endpoint = (Invoke-CyreneDocker ($arguments + @('--format', '{{json .Endpoints.docker.Host}}'))) | ConvertFrom-Json
    }
    if ($endpoint -notmatch '^npipe:/{2,4}(\.|localhost)/pipe/[^/\\]+$' -and $endpoint -notmatch '^unix:///') {
        throw 'Port selection requires a local Docker named-pipe or Unix-socket context; remote Docker is unsupported.'
    }
}

function Get-CyreneSelectedServices($config, [string[]]$services) {
    if (-not $services) { return $config.services.PSObject.Properties.Name }
    $selected = @{}
    $pending = [System.Collections.Generic.Queue[string]]::new()
    foreach ($serviceName in $services) { $pending.Enqueue($serviceName) }
    while ($pending.Count) {
        $serviceName = $pending.Dequeue()
        $service = $config.services.PSObject.Properties[$serviceName]
        if (-not $service) { throw "Unknown Compose service: $serviceName" }
        if ($selected.ContainsKey($serviceName)) { continue }
        $selected[$serviceName] = $true
        foreach ($dependency in $service.Value.depends_on.PSObject.Properties.Name) { $pending.Enqueue($dependency) }
    }
    return $selected.Keys
}

function Get-CyrenePort($value, [string]$description) {
    $portNumber = 0
    if (-not [int]::TryParse([string]$value, [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) {
        throw "Invalid $description port; expected one number between 1 and 65535."
    }
    return $portNumber
}

function Select-CyrenePorts($config, $containers, [string[]]$services) {
    $middleware = @('document-parser', 'mysql', 'minio', 'milvus', 'neo4j', 'redis', 'searxng')
    $selected = @(Get-CyreneSelectedServices $config $services)
    $reserved = [System.Collections.Generic.HashSet[int]]::new()
    $listeners = [System.Collections.Generic.List[object]]::new()
    $mappings = [System.Collections.Generic.List[object]]::new()
    foreach ($service in $config.services.PSObject.Properties) {
        if ($service.Name -in $middleware) { continue }
        foreach ($port in $service.Value.ports) { [void]$reserved.Add((Get-CyrenePort $port.published $service.Name)) }
    }
    try {
        foreach ($service in $config.services.PSObject.Properties) {
            if ($service.Name -notin $middleware -or $service.Name -notin $selected) { continue }
            foreach ($port in $service.Value.ports) {
                $target = Get-CyrenePort $port.target "$($service.Name) target"
                $candidate = Get-CyrenePort $port.published "$($service.Name) published"
                $protocol = $port.protocol
                if (-not $protocol) { $protocol = 'tcp' }
                if ($protocol -ne 'tcp') { throw "Unsupported $protocol port for $($service.Name); this launcher checks TCP ports." }
                $hostIp = $port.host_ip
                if (-not $hostIp) { $hostIp = '0.0.0.0' }
                $address = [System.Net.IPAddress]::Parse($hostIp)
                for (; $candidate -le 65535; $candidate++) {
                    if ($reserved.Contains($candidate)) { continue }
                    $owned = @($containers | Where-Object { $_.State -eq 'running' -and $_.Service -eq $service.Name } |
                        ForEach-Object { $_.Publishers } | Where-Object {
                            $_.TargetPort -eq $target -and $_.Protocol -eq $protocol -and
                            $_.PublishedPort -eq $candidate -and $_.URL -eq $address.ToString()
                        })
                    if ($owned.Count) { break }
                    $bindAddresses = @($address)
                    if ($address.Equals([System.Net.IPAddress]::Any)) { $bindAddresses += [System.Net.IPAddress]::IPv6Any }
                    $attemptListeners = [System.Collections.Generic.List[object]]::new()
                    try {
                        foreach ($bindAddress in $bindAddresses) {
                            $listener = $null
                            try {
                                $listener = [System.Net.Sockets.TcpListener]::new($bindAddress, $candidate)
                                $listener.ExclusiveAddressUse = $true
                                if ($bindAddress.AddressFamily -eq 'InterNetworkV6') { $listener.Server.DualMode = $false }
                                $listener.Start(); $attemptListeners.Add($listener)
                            }
                            catch {
                                if ($listener) { $listener.Stop() }
                                $socketError = $_.Exception
                                while ($socketError.InnerException) { $socketError = $socketError.InnerException }
                                if ($bindAddress.AddressFamily -eq 'InterNetworkV6' -and
                                    $socketError -is [System.Net.Sockets.SocketException] -and
                                    $socketError.SocketErrorCode -eq 'AddressFamilyNotSupported') { continue }
                                throw
                            }
                        }
                        $listeners.AddRange($attemptListeners); break
                    }
                    catch {
                        foreach ($listener in $attemptListeners) { $listener.Stop() }
                        $socketError = $_.Exception
                        while ($socketError.InnerException) { $socketError = $socketError.InnerException }
                        if ($socketError -isnot [System.Net.Sockets.SocketException] -or
                            $socketError.SocketErrorCode -notin @('AddressAlreadyInUse', 'AccessDenied')) { throw }
                    }
                }
                if ($candidate -gt 65535) { throw "No free TCP port for $($service.Name) from $($port.published) through 65535." }
                [void]$reserved.Add($candidate)
                $mapping = [ordered]@{}
                foreach ($property in $port.PSObject.Properties) { $mapping[$property.Name] = $property.Value }
                $mapping['published'] = [string]$candidate
                $mappings.Add([pscustomobject]@{ service = $service.Name; port = $mapping; hostIp = $hostIp })
            }
        }
        return [pscustomobject]@{ mappings = $mappings; listeners = $listeners }
    }
    catch { foreach ($listener in $listeners) { $listener.Stop() }; throw }
}

function ConvertTo-CyrenePortsOverride($mappings) {
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('services:')
    foreach ($group in ($mappings | Group-Object service)) {
        $lines.Add("  $($group.Name):")
        $lines.Add('    ports: !override')
        foreach ($mapping in $group.Group) { $lines.Add('      - ' + ($mapping.port | ConvertTo-Json -Depth 10 -Compress)) }
    }
    return $lines -join "`n"
}

function Start-CyreneCompose([string[]]$services, [switch]$noBuild, [string]$envFile) {
    if (-not $envFile) { $envFile = $cyreneDefaultEnvFile }
    $envFile = (Resolve-Path -LiteralPath $envFile -ErrorAction Stop).Path
    Assert-CyreneLocalDocker
    $composeArguments = @('compose', '--env-file', $envFile, '-f', $cyreneComposeFile)
    $configJson = (Invoke-CyreneDocker ($composeArguments + @('config', '--format', 'json'))) -join "`n"
    try { $config = $configJson | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Docker Compose config returned invalid JSON.' }
    if (-not $config.services) { throw 'Docker Compose config did not return a services object.' }
    $containerJson = Invoke-CyreneDocker ($composeArguments + @('ps', '--format', 'json'))
    $containers = @($containerJson | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $selection = Select-CyrenePorts $config $containers $services
    $overrideFile = Join-Path $PSScriptRoot ('.ports-' + [guid]::NewGuid().ToString('N') + '.yml')
    try {
        if ($selection.mappings.Count) {
            [System.IO.File]::WriteAllText($overrideFile, (ConvertTo-CyrenePortsOverride $selection.mappings))
            $composeArguments += @('-f', $overrideFile)
            foreach ($mapping in $selection.mappings) {
                Write-Host "$($mapping.service): $($mapping.hostIp):$($mapping.port.published) -> $($mapping.port.target)/$($mapping.port.protocol)"
            }
        }
        foreach ($listener in $selection.listeners) { $listener.Stop() }
        # shortcut: another process can claim a checked port before Docker binds it; rerun this launcher after a bind conflict.
        $upArguments = @('up', '-d')
        if (-not $noBuild) { $upArguments += '--build' }
        Invoke-CyreneDocker ($composeArguments + $upArguments + $services)
        Invoke-CyreneDocker ($composeArguments + @('ps'))
    }
    finally {
        foreach ($listener in $selection.listeners) { $listener.Stop() }
        if (Test-Path -LiteralPath $overrideFile) { Remove-Item -LiteralPath $overrideFile -ErrorAction Stop }
    }
}

if ($MyInvocation.InvocationName -ne '.') { Start-CyreneCompose -Services $services -NoBuild:$noBuild -EnvFile $envFile }
