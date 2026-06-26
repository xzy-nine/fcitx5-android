$inputJson = [Console]::In.ReadToEnd()
if (-not $inputJson) { exit 0 }

try {
    $inputObj = $inputJson | ConvertFrom-Json
} catch { exit 0 }

$command = $inputObj.tool_input.command
if (-not $command) { exit 0 }

$reasons = @{
    '2>&1' = "命令包含禁止使用的 2>&1 语法。请务使用重定向方式。"
    '&&'   = "命令包含禁止使用的 && 语法。请使用分号代替。"
}

foreach ($pattern in $reasons.Keys) {
    if ($command -match [regex]::Escape($pattern)) {
        $result = @{
            hookSpecificOutput = @{
                hookEventName = "PreToolUse"
                permissionDecision = "deny"
                permissionDecisionReason = $reasons[$pattern]
            }
        }
        $result | ConvertTo-Json -Depth 10 -Compress
        exit 0
    }
}

exit 0
