# Refreshes the JavaFX More artwork and offline help from the reference PCL-2 checkout.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path -Parent $PSScriptRoot
$original = Join-Path $root 'PCL-2/Plain Craft Launcher 2'
$assets = Join-Path $root 'src/main/resources/io/pclnt/ui'
$help = Join-Path $assets 'help'
$utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Path $help -Force | Out-Null

[xml]$menu = Get-Content -LiteralPath "$original/Pages/PageOther/PageOtherLeft.xaml" -Encoding UTF8 -Raw
$keys = [ordered]@{ help = 'ItemHelp'; about = 'ItemAbout'; toolbox = 'ItemTest'; feedback = 'ItemFeedback'; vote = 'ItemVote' }
$artworkFile = Join-Path $assets 'pcl-artwork.properties'
$artwork = @(Get-Content -LiteralPath $artworkFile -Encoding UTF8 | Where-Object { $_ -notmatch '^(help|about|toolbox|feedback|vote|help-refresh|setup-launch|setup-link|setup-ui|setup-system|setup-reset)=' })
foreach ($key in $keys.Keys) {
    $item = $menu.SelectSingleNode("//*[@*[local-name()='Name']='$($keys[$key])']")
    $artwork += "$key=$($item.GetAttribute('Logo'))"
}
$refresh = $menu.SelectSingleNode("//*[local-name()='MyIconButton' and @Click='Refresh']")
$artwork += "help-refresh=$($refresh.GetAttribute('Logo'))"
[xml]$setup = Get-Content -LiteralPath "$original/Pages/PageSetup/PageSetupLeft.xaml" -Encoding UTF8 -Raw
$setupKeys = [ordered]@{ 'setup-launch' = 'ItemLaunch'; 'setup-link' = 'ItemLink'; 'setup-ui' = 'ItemUI'; 'setup-system' = 'ItemSystem' }
foreach ($key in $setupKeys.Keys) {
    $item = $setup.SelectSingleNode("//*[@*[local-name()='Name']='$($setupKeys[$key])']")
    $artwork += "$key=$($item.GetAttribute('Logo'))"
}
$reset = $setup.SelectSingleNode("//*[local-name()='MyIconButton' and @Click='Reset']")
$artwork += "setup-reset=$($reset.GetAttribute('Logo'))"
[IO.File]::WriteAllLines($artworkFile, [string[]]$artwork, $utf8)

[xml]$personalization = Get-Content -LiteralPath "$original/Pages/PageSetup/PageSetupUI.xaml" -Encoding UTF8 -Raw
$presetNodes = $personalization.SelectNodes("//*[@*[local-name()='Name']='PanCustomPreset']//*[local-name()='MyComboBoxItem']")
$homeCode = Get-Content -LiteralPath "$original/Pages/PageLaunch/PageLaunchRight.xaml.vb" -Encoding UTF8 -Raw
$presetLines = foreach ($preset in $presetNodes) {
    $id = $preset.GetAttribute('local:SettingService.Value')
    $url = [regex]::Match($homeCode, '(?m)^\s*Case ' + $id + '\s*\r?\n\s*Logger\.Info\([^\r\n]+\)\s*\r?\n\s*Url = "([^"]+)"').Groups[1].Value
    "$id=$($preset.GetAttribute('Content'))|$url"
}
[IO.File]::WriteAllLines((Join-Path $assets 'home-presets.properties'), [string[]]$presetLines, $utf8)
Copy-Item -LiteralPath "$original/Resources/Custom.xaml" -Destination (Join-Path $assets 'custom-home-example.xaml')

function Read-Entry($entry) {
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Add-Block($parent, $type, $value, $title = '') {
    $block = $parent.OwnerDocument.CreateElement($type)
    if ($title) { $block.SetAttribute('title', $title) }
    if ($value) { $block.InnerText = $value }
    [void]$parent.AppendChild($block)
    $block
}

function Import-Icon([string]$name) {
    if ($name -match '^pack://application:,,,/images/Blocks/([a-zA-Z0-9_-]+\.png)$') {
        $file = $Matches[1]
        $source = Join-Path $original "Images/Blocks/$file"
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination $help
            $file
        }
    }
}

function Convert-Blocks($node, $parent) {
    foreach ($element in $node.ChildNodes) {
        if ($element.NodeType -ne 'Element') { continue }
        $title = $element.GetAttribute('Title')
        $value = $element.GetAttribute('Text')
        if (!$title) { $title = $value }
        if (!$title) { $title = $element.GetAttribute('Content') }
        $data = $element.GetAttribute('EventData')
        switch ($element.LocalName) {
            'MyCard' {
                $section = Add-Block $parent 'section' '' $title
                if ($element.GetAttribute('CanSwap') -eq 'True' -or $element.HasAttribute('IsSwapped')) {
                    $section.SetAttribute('expanded', ($element.GetAttribute('IsSwapped') -ne 'True').ToString().ToLowerInvariant())
                }
                Convert-Blocks $element $section
            }
            'TextBlock' {
                if (!$value) { $value = $element.InnerText.Trim() }
                if ($value) { $null = Add-Block $parent 'p' $value }
                Convert-Blocks $element $parent
            }
            'MyHint' {
                if ($value) { $null = Add-Block $parent 'hint' $value }
            }
            'MyImage' {
                $source = $element.GetAttribute('Source')
                if ($source -notmatch '^https?://') { $source = Import-Icon $source }
                if ($source) {
                    $image = Add-Block $parent 'image' $source
                    foreach ($dimension in 'Width', 'Height', 'MaxWidth', 'MaxHeight', 'HorizontalAlignment') {
                        $value = $element.GetAttribute($dimension)
                        if ($value) { $image.SetAttribute($dimension, $value) }
                    }
                }
            }
            { $_ -in 'MyButton','MyListItem','MyTextButton','MyIconTextButton','MyIconButton','Label' } {
                switch ($element.GetAttribute('EventType')) {
                    '打开网页' {
                        if ($data -match '^https?://') {
                            if (!$title) { $title = $data }
                            $null = Add-Block $parent 'link' $data $title
                        }
                    }
                    '打开帮助' { if ($data) { $null = Add-Block $parent 'topic' $data $title } }
                    '复制文本' { if ($data) { $null = Add-Block $parent 'copy' $data $title } }
                    default { if ($title) { $null = Add-Block $parent 'p' $title } }
                }
            }
            default { Convert-Blocks $element $parent }
        }
    }
}

[xml]$catalog = '<?xml version="1.0" encoding="utf-8"?><!-- Adapted from PCL Resources/Help.zip. See README for credits. --><help/>'
$archive = [IO.Compression.ZipFile]::OpenRead("$original/Resources/Help.zip")
try {
    foreach ($entry in $archive.Entries | Where-Object Name -like '*.json') {
        $metadata = ConvertFrom-Json (Read-Entry $entry)
        if ($metadata.ShowInPublic -eq $false) { continue }
        $topic = Add-Block $catalog.DocumentElement 'entry' '' $metadata.Title
        $topic.SetAttribute('id', $entry.FullName)
        $topic.SetAttribute('description', [string]$metadata.Description)
        $topic.SetAttribute('keywords', [string]$metadata.Keywords)
        $topic.SetAttribute('categories', ($metadata.Types -join '|'))
        if ($metadata.ShowInSearch -eq $false) { $topic.SetAttribute('searchable', 'false') }
        $asset = switch ($metadata.EventType) {
            '打开网页' { 'CommandBlock.png' }
            '弹出窗口' { 'GrassPath.png' }
            default { 'Grass.png' }
        }
        $topic.SetAttribute('icon', (Import-Icon "pack://application:,,,/images/Blocks/$asset"))

        if ($metadata.EventType -eq '打开网页' -and $metadata.EventData -match '^https?://') {
            $topic.SetAttribute('url', $metadata.EventData)
        } elseif ($metadata.EventType -eq '弹出窗口') {
            $message = ($metadata.EventData -split '\|', 2)[-1] -replace '\\n', "`n"
            $null = Add-Block $topic 'p' $message
        } else {
            $article = $archive.GetEntry([IO.Path]::ChangeExtension($entry.FullName, '.xaml'))
            if (!$article) { throw "Missing original help article: $($entry.FullName)" }
            # Parse it as data; never load WPF markup or execute its events.
            [xml]$markup = '<root xmlns:local="urn:pcl" xmlns:x="urn:x" xmlns:sys="urn:sys">' + (Read-Entry $article) + '</root>'
            Convert-Blocks $markup.DocumentElement $topic
        }
    }
} finally {
    $archive.Dispose()
}
$settings = [Xml.XmlWriterSettings]::new()
$settings.Indent = $true
$settings.Encoding = $utf8
$writer = [Xml.XmlWriter]::Create((Join-Path $help 'catalog.xml'), $settings)
try { $catalog.Save($writer) } finally { $writer.Dispose() }
