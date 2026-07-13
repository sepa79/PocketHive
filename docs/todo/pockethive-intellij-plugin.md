# IntelliJ Plugin — Spec

## Status
`FUTURE / DESIGN`

## Overview

New Kotlin plugin for IntelliJ IDEA (and other JetBrains IDEs via the
IntelliJ Platform). Provides the same capabilities as the VS Code extension
using IntelliJ-native APIs. Webview panels reuse the same `ui-v2` built
assets via JCEF (Chromium Embedded Framework).

## Target IDEs

- IntelliJ IDEA (Community + Ultimate)
- Rider (via IntelliJ Platform)
- WebStorm (via IntelliJ Platform)

Minimum platform version: 2023.1 (build 231)

## Directory structure

```
intellij-pockethive/
  src/
    main/
      kotlin/io/pockethive/plugin/
        PocketHivePlugin.kt           <- plugin service entry point
        mcp/
          McpServerManager.kt         <- process lifecycle (spawn/restart)
          McpClient.kt                <- JSON-RPC client over stdio/HTTP
          McpTools.kt                 <- typed wrappers for all MCP tools
        settings/
          PocketHiveSettings.kt       <- PersistentStateComponent
          PocketHiveConfigurable.kt   <- Settings UI (File -> Settings -> Tools)
          EnvironmentEditor.kt        <- Add/Edit environment dialog
        toolwindows/
          PocketHiveToolWindowFactory.kt
          HiveToolWindow.kt           <- swarm list + environment switcher
          ScenariosToolWindow.kt      <- bundle list + folder switcher
          JournalToolWindow.kt        <- journal entries
          SettingsToolWindow.kt       <- quick settings summary
        webviews/
          JcefWebviewPanel.kt         <- base JCEF panel
          SwarmDetailPanel.kt         <- swarm topology (ui-v2)
          BundleDetailPanel.kt        <- bundle pipeline (ui-v2)
          QueueMonitorPanel.kt        <- queue depths (ui-v2)
          TapViewerPanel.kt           <- debug tap viewer (ui-v2)
        actions/
          AddEnvironmentAction.kt
          SwitchEnvironmentAction.kt
          AddBundlesFolderAction.kt
          ValidateBundleAction.kt
          DeployBundleAction.kt
          StartSwarmAction.kt
          StopSwarmAction.kt
          RemoveSwarmAction.kt
          OpenSwarmDetailAction.kt
          OpenQueueMonitorAction.kt
          RestartMcpServerAction.kt
        statusbar/
          PocketHiveStatusBarWidget.kt
      resources/
        META-INF/
          plugin.xml
        dist-plugin/                  <- built ui-v2 assets (gitignored)
  build.gradle.kts
  gradle.properties
  settings.gradle.kts
```

## plugin.xml

```xml
<idea-plugin>
  <id>io.pockethive.plugin</id>
  <name>PocketHive</name>
  <version>1.0.0</version>
  <vendor>PocketHive</vendor>
  <description>PocketHive swarm management, scenario authoring, and live monitoring.</description>

  <depends>com.intellij.modules.platform</depends>

  <extensions defaultExtensionNs="com.intellij">

    <!-- Tool window -->
    <toolWindow id="PocketHive"
                displayName="PocketHive"
                anchor="right"
                factoryClass="io.pockethive.plugin.toolwindows.PocketHiveToolWindowFactory"
                icon="/icons/hive.svg" />

    <!-- Application-level settings -->
    <applicationService
        serviceImplementation="io.pockethive.plugin.settings.PocketHiveSettings" />

    <!-- Settings page -->
    <applicationConfigurable
        parentId="tools"
        displayName="PocketHive"
        id="io.pockethive.plugin.settings"
        instance="io.pockethive.plugin.settings.PocketHiveConfigurable" />

    <!-- Status bar widget -->
    <statusBarWidgetFactory
        id="PocketHiveStatusBar"
        implementation="io.pockethive.plugin.statusbar.PocketHiveStatusBarWidgetFactory"
        order="after positionWidget" />

  </extensions>

  <actions>
    <group id="PocketHive.Actions" text="PocketHive" popup="true">
      <add-to-group group-id="ToolsMenu" anchor="last" />
      <action id="PocketHive.AddEnvironment"
              class="io.pockethive.plugin.actions.AddEnvironmentAction"
              text="Add Environment" />
      <action id="PocketHive.SwitchEnvironment"
              class="io.pockethive.plugin.actions.SwitchEnvironmentAction"
              text="Switch Environment" />
      <action id="PocketHive.RestartMcp"
              class="io.pockethive.plugin.actions.RestartMcpServerAction"
              text="Restart MCP Server" />
    </group>
  </actions>
</idea-plugin>
```

## Settings — PersistentStateComponent

```kotlin
@Service(Service.Level.APP)
@State(
    name = "PocketHiveSettings",
    storages = [Storage("pockethive.xml")]
)
class PocketHiveSettings : PersistentStateComponent<PocketHiveSettings.State> {

    data class Environment(
        var name: String = "",
        var baseUrl: String = "",
        var rabbitUser: String = "guest",
        var tcpMockUrl: String = "",
        var wiremockUrl: String = ""
        // authToken and rabbitPass stored in PasswordSafe, not here
    )

    data class State(
        var environments: MutableList<Environment> = mutableListOf(),
        var activeEnvironment: String = "",
        var bundlesFolders: MutableList<String> = mutableListOf(),
        var activeBundlesFolder: String = "",
        var pockethiveRoot: String = "",
        var mcpTransport: String = "stdio",   // "stdio" or "http"
        var mcpHttpUrl: String = "",
        var mcpServerPath: String = ""        // override path, blank = use npm package
    )

    private var _state = State()

    override fun getState(): State = _state
    override fun loadState(state: State) { _state = state }

    fun activeEnvironment(): Environment? =
        _state.environments.find { it.name == _state.activeEnvironment }

    companion object {
        fun getInstance(): PocketHiveSettings =
            ApplicationManager.getApplication().getService(PocketHiveSettings::class.java)
    }
}
```

Stored at: `~/.config/JetBrains/<product>/options/pockethive.xml` (Linux/Mac)
           `%APPDATA%\JetBrains\<product>\options\pockethive.xml` (Windows)

## Secrets — PasswordSafe

Auth tokens and passwords are stored in IntelliJ's `PasswordSafe`, which
uses the OS keychain (macOS Keychain, Windows Credential Manager, Linux
libsecret/KWallet).

```kotlin
object PocketHiveCredentials {

    private fun credAttr(envName: String, key: String) = CredentialAttributes(
        ServiceNameProvider.generateServiceName("PocketHive", "$envName/$key")
    )

    fun getAuthToken(envName: String): String? =
        PasswordSafe.instance.getPassword(credAttr(envName, "authToken"))

    fun setAuthToken(envName: String, token: String) =
        PasswordSafe.instance.setPassword(credAttr(envName, "authToken"), token)

    fun getRabbitPass(envName: String): String =
        PasswordSafe.instance.getPassword(credAttr(envName, "rabbitPass")) ?: "guest"

    fun setRabbitPass(envName: String, pass: String) =
        PasswordSafe.instance.setPassword(credAttr(envName, "rabbitPass"), pass)

    fun clearAll(envName: String) {
        PasswordSafe.instance.setPassword(credAttr(envName, "authToken"), null)
        PasswordSafe.instance.setPassword(credAttr(envName, "rabbitPass"), null)
    }
}
```

## MCP server — config delivery via ProcessBuilder

```kotlin
class McpServerManager(private val project: Project) {

    private var process: Process? = null

    fun start() {
        val settings = PocketHiveSettings.getInstance()
        val env = settings.activeEnvironment() ?: return
        val serverPath = resolveServerPath(settings)

        val pb = ProcessBuilder("node", serverPath)
        pb.environment().apply {
            putAll(System.getenv())  // inherit system env
            put("POCKETHIVE_BASE_URL",    env.baseUrl)
            put("POCKETHIVE_ROOT",        settings.state.pockethiveRoot)
            put("BUNDLES_ROOT",           settings.state.activeBundlesFolder)
            put("RABBITMQ_DEFAULT_USER",  env.rabbitUser.ifBlank { "guest" })
            put("RABBITMQ_DEFAULT_PASS",  PocketHiveCredentials.getRabbitPass(env.name))
            put("PH_BUNDLES_ROOTS",       Json.encodeToString(settings.state.bundlesFolders))
            if (env.tcpMockUrl.isNotBlank())  put("TCP_MOCK_BASE_URL",  env.tcpMockUrl)
            if (env.wiremockUrl.isNotBlank()) put("WIREMOCK_BASE_URL",  env.wiremockUrl)
        }
        pb.redirectErrorStream(true)

        process = pb.start()
        connectMcpClient(process!!)
        notifyStatusChange("running")
    }

    fun restart() {
        stop()
        Thread.sleep(500)
        start()
    }

    fun stop() {
        process?.destroy()
        process = null
        notifyStatusChange("stopped")
    }

    private fun resolveServerPath(settings: PocketHiveSettings): String {
        if (settings.state.mcpServerPath.isNotBlank()) {
            return settings.state.mcpServerPath
        }
        // Find globally installed npm package
        val npmRoot = runCommand("npm", "root", "-g")
        return "$npmRoot/@pockethive/mcp-server/server.mjs"
    }
}
```

## JCEF webview panels

IntelliJ JCEF renders the same `dist-plugin/index.html` built from `ui-v2`.
The `CefMessageRouter` provides the postMessage bridge.

```kotlin
class SwarmDetailPanel(private val swarmId: String) : JcefWebviewPanel() {

    override fun getTitle() = "Swarm: $swarmId"

    override fun onBrowserCreated(browser: JBCefBrowser) {
        // Register message handler
        val router = JBCefJSQuery.create(browser)
        router.addHandler { msg ->
            handleWebviewMessage(msg)
            null
        }
        // Load the ui-v2 app
        browser.loadURL(getDistUrl("index.html"))
        // Send initial config after load
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                sendConfig(browser)
            }
        }, browser.cefBrowser)
    }

    private fun sendConfig(browser: CefBrowser) {
        val settings = PocketHiveSettings.getInstance()
        val env = settings.activeEnvironment() ?: return
        val config = """{ "type": "config", "payload": { "baseUrl": "${env.baseUrl}", "swarmId": "$swarmId" } }"""
        browser.executeJavaScript("window.__phPluginMessage($config)", "", 0)
    }

    private fun handleWebviewMessage(msg: String): String {
        val parsed = Json.parseToJsonElement(msg).jsonObject
        return when (parsed["type"]?.jsonPrimitive?.content) {
            "api"  -> proxyApiCall(parsed)
            "mcp"  -> callMcpTool(parsed)
            else   -> "{}"
        }
    }
}
```

## Settings UI — PocketHiveConfigurable

The settings page appears at File → Settings → Tools → PocketHive.

```kotlin
class PocketHiveConfigurable : Configurable {

    override fun getDisplayName() = "PocketHive"

    override fun createComponent(): JComponent {
        return panel {
            group("Environments") {
                row {
                    // JBTable showing environments: name | baseUrl | active
                    // Buttons: Add | Edit | Remove | Set Token | Use
                    cell(environmentsTable)
                }
            }
            group("Bundles Folders") {
                row {
                    // JBList showing bundle folder paths
                    // Buttons: Add (file picker) | Remove | Use
                    cell(bundlesFoldersList)
                }
            }
            group("PocketHive Root") {
                row("Path:") {
                    textFieldWithBrowseButton(
                        settings.state::pockethiveRoot,
                        "Select PocketHive Repository Root"
                    )
                }
            }
            group("MCP Server") {
                buttonsGroup("Transport:") {
                    row { radioButton("stdio (spawn locally)", "stdio") }
                    row { radioButton("http (connect to running server)", "http") }
                }
                row("HTTP URL:") {
                    textField().bindText(settings.state::mcpHttpUrl)
                        .enabledIf(mcpTransportProperty.equalsTo("http"))
                }
                row("Server path override:") {
                    textFieldWithBrowseButton(settings.state::mcpServerPath, "Select server.mjs")
                }
            }
        }
    }
}
```

## Tool window layout

The tool window docks to the right side panel. It has four tabs:

```
[Hive] [Scenarios] [Journal] [Settings]
```

Each tab is a `SimpleToolWindowPanel` containing a `JBScrollPane` with
a tree or list component. Topology and queue views open as separate
editor tabs (JCEF panels), not inside the tool window.

## Status bar widget

```kotlin
class PocketHiveStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID() = "PocketHiveStatusBar"
    override fun getPresentation() = this

    override fun getText(): String {
        val settings = PocketHiveSettings.getInstance()
        val envName = settings.state.activeEnvironment.ifBlank { "not configured" }
        val status = McpServerManager.getInstance().statusIcon()
        return "$status PocketHive: $envName"
    }

    override fun getTooltipText(): String {
        val env = PocketHiveSettings.getInstance().activeEnvironment()
        return buildString {
            appendLine("PocketHive")
            appendLine("Environment: ${env?.name ?: "none"}")
            appendLine("Base URL: ${env?.baseUrl ?: "—"}")
            appendLine("MCP: ${McpServerManager.getInstance().statusText()}")
        }
    }

    override fun getClickConsumer() = Consumer<MouseEvent> {
        // Open settings tool window tab
        ToolWindowManager.getInstance(project).getToolWindow("PocketHive")?.show()
    }
}
```

## build.gradle.kts

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "io.pockethive"
version = "1.0.0"

intellij {
    version.set("2023.1")
    type.set("IC")  // IntelliJ IDEA Community
    plugins.set(listOf())
}

tasks {
    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("251.*")
    }

    // Build ui-v2 plugin assets before packaging
    register<Exec>("buildWebviews") {
        workingDir("../ui-v2")
        commandLine("npm", "run", "build:plugin")
        doFirst {
            environment("PLUGIN_MODE", "true")
        }
    }

    prepareSandbox {
        dependsOn("buildWebviews")
        from("../ui-v2/dist-plugin") {
            into("pockethive/dist-plugin")
        }
    }
}
```
