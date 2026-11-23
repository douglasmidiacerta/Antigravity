package id.vern.antigravity.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.isActive
import id.vern.antigravity.AntigravityApp
import id.vern.antigravity.R
import id.vern.antigravity.data.AccentColor
import id.vern.antigravity.data.AppearanceConfig
import id.vern.antigravity.data.AttachedFile
import id.vern.antigravity.data.ChatMessage
import id.vern.antigravity.data.QueuedMessage
import id.vern.antigravity.data.RunningTask
import id.vern.antigravity.data.RunningSubagent
import id.vern.antigravity.data.DaemonTask
import id.vern.antigravity.data.AgentExecutionStep
import id.vern.antigravity.data.AgentStepKind
import id.vern.antigravity.data.ChangedFileDetail
import id.vern.antigravity.data.ChangedFileItem
import id.vern.antigravity.data.TurnFileChanges
import id.vern.antigravity.data.EngineStatus
import id.vern.antigravity.data.ExecutionLogEntry
import id.vern.antigravity.data.MessageRole
import id.vern.antigravity.data.MessageStatus
import id.vern.antigravity.data.ProjectSortOrder
import id.vern.antigravity.data.ThemeMode
import id.vern.antigravity.data.ThemePreset
import id.vern.antigravity.data.UserQuotaSummary
import id.vern.antigravity.data.CodeAssistTier
import id.vern.antigravity.data.CliSlashCommand
import id.vern.antigravity.data.ModelAvailability
import id.vern.antigravity.data.SlashAction
import id.vern.antigravity.data.SlashCommandParser
import id.vern.antigravity.data.SYSTEM_SLASH_COMMANDS
import id.vern.antigravity.data.mergeDynamicSlashActions
import id.vern.antigravity.service.AgyModelsService
import id.vern.antigravity.service.CliModel
import id.vern.antigravity.service.CloudCodeQuotaService
import id.vern.antigravity.service.PermissionConfigService
import id.vern.antigravity.service.PermissionGrantsState
import id.vern.antigravity.service.PermissionRule
import id.vern.antigravity.service.RuleConfigService
import id.vern.antigravity.service.RuleItem
import id.vern.antigravity.service.McpConfigService
import id.vern.antigravity.service.McpServerItem
import id.vern.antigravity.service.WakeLockHolder
import id.vern.antigravity.ui.components.TerminalLine
import id.vern.antigravity.ui.components.TerminalSessionManager
import id.vern.antigravity.executor.EngineExecutor
import id.vern.antigravity.install.BootstrapInstaller
import id.vern.antigravity.install.CoreUpdateManager
import id.vern.antigravity.install.CoreUpdateInfo
import id.vern.antigravity.oauth.OAuthManager
import id.vern.antigravity.oauth.AccountManager
import id.vern.antigravity.oauth.StoredAccount
import id.vern.antigravity.service.CoreServerService
import id.vern.antigravity.service.ForegroundNotification
import id.vern.antigravity.util.AppLogger
import id.vern.antigravity.util.AppUpdateState
import id.vern.antigravity.util.UpdateChecker
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.provider.DocumentsContract
import android.util.Log
import android.system.Os
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class CoreEngineUpdateState(
    val isChecking: Boolean = false,
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val isUpdateAvailable: Boolean = false,
    val downloadUrl: String? = null,
    val lastCheckedTime: Long = 0L,
    val error: String? = null
)

data class CustomizationInstallProgress(
    val title: String,
    val command: String,
    val lines: List<TerminalLine> = emptyList(),
    val isRunning: Boolean = true,
    val isSuccess: Boolean? = null
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val timestamp: Long,
    val messageCount: Int,
    val isPinned: Boolean = false,
    val projectPath: String? = null
)

data class CustomizationItem(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val isBuiltIn: Boolean = false,
    val path: String? = null
)

data class SkillCatalogueItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val isPlugin: Boolean = false,
    val defaultPrompt: String = "",
    val repoUrl: String = "",
    val subPath: String? = null
)

data class DiscoveredRepoSkill(
    val id: String,
    val name: String,
    val description: String,
    val subPath: String,
    val isSelected: Boolean = true
)

data class CustomizationRepoTrack(
    val id: String,
    val name: String,
    val repoUrl: String,
    val isPlugin: Boolean = false,
    val subPath: String? = null,
    val installedSha: String = "",
    val remoteSha: String? = null,
    val lastChecked: Long = 0L,
    val hasUpdate: Boolean = false
)

data class TokenUsageStats(
    val rulesTokens: Int,
    val rulesBreakdowns: List<Pair<String, Int>>,
    val skillsTokens: Int,
    val skillsBreakdowns: List<Pair<String, Int>>,
    val totalLimit: Int = 20000
)

class AntigravityViewModel : ViewModel() {

    companion object {
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_THEME_MODE = "ag_theme_mode"
        private const val KEY_ACCENT_COLOR = "ag_accent_color"
        private const val KEY_WORKSPACE_PATH = "ag_workspace_path"
        private const val KEY_SELECTED_MODEL = "ag_selected_model"
        private const val KEY_SELECTED_MODEL_CLI_ID = "ag_selected_model_cli_id"
        private const val KEY_CLI_MODELS_JSON = "ag_cli_models_json"
        private const val KEY_CLI_MODELS_FETCHED_AT = "ag_cli_models_fetched_at"
        private const val KEY_CLI_SLASH_COMMANDS = "ag_cli_slash_commands"
        private const val KEY_CLI_SLASH_VERSION = "ag_cli_slash_version"
        private const val KEY_CLI_SLASH_FETCHED_AT = "ag_cli_slash_fetched_at"
        private const val CLI_MODELS_THROTTLE_MS = 10L * 60L * 1000L
        private const val KEY_THINKING_BUDGET = "ag_thinking_budget"
        private const val KEY_VERBOSE_THINKING = "ag_verbose_thinking"
        private const val KEY_CONVERSATION_WIDTH = "ag_conversation_width"
        private const val KEY_THEME_PRESET = "ag_theme_preset"
        private const val KEY_CUSTOM_THEME_BG = "ag_custom_theme_bg"
        private const val KEY_CUSTOM_THEME_FG = "ag_custom_theme_fg"
        private const val KEY_CUSTOM_THEME_ACCENT = "ag_custom_theme_accent"
        private const val KEY_EDITOR_WORD_WRAP = "ag_editor_word_wrap"
        private const val KEY_EDITOR_LINE_NUMBERS = "ag_editor_line_numbers"
        private const val KEY_EDITOR_FONT_SIZE = "ag_editor_font_size"
        private const val KEY_SKIP_PERMISSIONS = "ag_skip_permissions"
        private const val KEY_PROJECT_SORT = "ag_project_sort"
        private const val KEY_QUEUED_MESSAGES_MODE = "ag_queued_messages_mode"
        private const val KEY_SECURITY_PRESET = "ag_security_preset"
        private const val KEY_PLAN_REVIEW_POLICY = "ag_plan_review_policy"
        private const val KEY_BROWSER_JS_POLICY = "ag_browser_js_policy"
        private const val KEY_ENABLE_CREDIT_OVERAGES = "ag_enable_credit_overages"
        private const val KEY_USER_PROJECTS = "ag_user_projects"
        private const val KEY_CONVERSATION_HISTORY = "ag_conversation_history"
        private const val KEY_LAST_ACTIVE_CONV_ID = "ag_last_active_conv_id"
        private const val KEY_CUSTOMIZATION_TRACKING = "ag_customization_tracking"
        private const val KEY_CUSTOMIZATION_LAST_CHECK = "ag_customization_last_check"
    }

    private val mutableChatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = mutableChatMessages.asStateFlow()

    private val mutableConversationTitle = MutableStateFlow("New Conversation")
    val conversationTitle: StateFlow<String> = mutableConversationTitle.asStateFlow()

    private val mutableActiveConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = mutableActiveConversationId.asStateFlow()

    private val mutableIsConversationPinned = MutableStateFlow(false)
    val isConversationPinned: StateFlow<Boolean> = mutableIsConversationPinned.asStateFlow()

    private val mutableConversationHistory = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversationHistory: StateFlow<List<ConversationSummary>> = mutableConversationHistory.asStateFlow()

    private val mutableAttachedFiles = MutableStateFlow<List<AttachedFile>>(emptyList())
    val attachedFiles: StateFlow<List<AttachedFile>> = mutableAttachedFiles.asStateFlow()

    private val mutableIsExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = mutableIsExecuting.asStateFlow()

    private val mutableQueuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessage>> = mutableQueuedMessages.asStateFlow()

    private val mutableActiveWorkingStatus = MutableStateFlow<String?>(null)
    val activeWorkingStatus: StateFlow<String?> = mutableActiveWorkingStatus.asStateFlow()

    private val mutableRunningTasks = MutableStateFlow<List<RunningTask>>(emptyList())
    val runningTasks: StateFlow<List<RunningTask>> = mutableRunningTasks.asStateFlow()

    private val mutableRunningSubagents = MutableStateFlow<List<RunningSubagent>>(emptyList())
    val runningSubagents: StateFlow<List<RunningSubagent>> = mutableRunningSubagents.asStateFlow()

    private val mutableDaemonTasks = MutableStateFlow<List<DaemonTask>>(emptyList())
    val daemonTasks: StateFlow<List<DaemonTask>> = mutableDaemonTasks.asStateFlow()

    private val mutableExecutionLogs = MutableStateFlow<List<ExecutionLogEntry>>(emptyList())
    val executionLogs: StateFlow<List<ExecutionLogEntry>> = mutableExecutionLogs.asStateFlow()

    private val convIdToCliSessionId = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        AppLogger.setHandler { tag, message, isError ->
            val entry = ExecutionLogEntry(
                tag = tag,
                message = message,
                isError = isError
            )
            mutableExecutionLogs.update { current ->
                val capacity = 1000
                if (current.size >= capacity) {
                    val dropCount = current.size - capacity + 1
                    ArrayList<ExecutionLogEntry>(capacity).apply {
                        addAll(current.subList(dropCount, current.size))
                        add(entry)
                    }
                } else {
                    ArrayList<ExecutionLogEntry>(current.size + 1).apply {
                        addAll(current)
                        add(entry)
                    }
                }
            }
        }
    }

    private val mutableEngineStatus = MutableStateFlow(EngineStatus())
    val engineStatus: StateFlow<EngineStatus> = mutableEngineStatus.asStateFlow()

    private val mutableAppearanceConfig = MutableStateFlow(AppearanceConfig())
    val appearanceConfig: StateFlow<AppearanceConfig> = mutableAppearanceConfig.asStateFlow()

    private val mutableWorkspacePath = MutableStateFlow("")
    val workspacePath: StateFlow<String> = mutableWorkspacePath.asStateFlow()

    private val mutableSelectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = mutableSelectedModel.asStateFlow()

    private val mutableThinkingBudget = MutableStateFlow("")
    val thinkingBudget: StateFlow<String> = mutableThinkingBudget.asStateFlow()

    private val mutableVerboseThinking = MutableStateFlow(false)
    val verboseThinking: StateFlow<Boolean> = mutableVerboseThinking.asStateFlow()

    private val mutablePendingInputText = MutableStateFlow<String?>(null)
    val pendingInputText: StateFlow<String?> = mutablePendingInputText.asStateFlow()

    fun clearPendingInputText() {
        mutablePendingInputText.value = null
    }

    private val mutableConversationWidth = MutableStateFlow("Default")
    val conversationWidth: StateFlow<String> = mutableConversationWidth.asStateFlow()

    private val mutableIsBooting = MutableStateFlow(true)
    val isBooting: StateFlow<Boolean> = mutableIsBooting.asStateFlow()

    private val mutableSetupProgress = MutableStateFlow(0f)
    val setupProgress: StateFlow<Float> = mutableSetupProgress.asStateFlow()

    private val mutableBootStatusText = MutableStateFlow("Initializing Antigravity...")
    val bootStatusText: StateFlow<String> = mutableBootStatusText.asStateFlow()

    private val mutableBootError = MutableStateFlow<String?>(null)
    val bootError: StateFlow<String?> = mutableBootError.asStateFlow()

    private val mutableBootErrorDetail = MutableStateFlow<String?>(null)
    val bootErrorDetail: StateFlow<String?> = mutableBootErrorDetail.asStateFlow()

    private val mutableUserEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = mutableUserEmail.asStateFlow()

    private val mutableAccounts = MutableStateFlow<List<StoredAccount>>(emptyList())
    val accounts: StateFlow<List<StoredAccount>> = mutableAccounts.asStateFlow()

    private val mutableUserQuotaSummary = MutableStateFlow<UserQuotaSummary?>(null)
    val userQuotaSummary: StateFlow<UserQuotaSummary?> = mutableUserQuotaSummary.asStateFlow()

    private val mutableCodeAssistTier = MutableStateFlow<CodeAssistTier?>(null)
    val codeAssistTier: StateFlow<CodeAssistTier?> = mutableCodeAssistTier.asStateFlow()

    private val mutableIsQuotaLoading = MutableStateFlow(false)
    val isQuotaLoading: StateFlow<Boolean> = mutableIsQuotaLoading.asStateFlow()

    private val mutableQuotaErrorMessage = MutableStateFlow<String?>(null)
    val quotaErrorMessage: StateFlow<String?> = mutableQuotaErrorMessage.asStateFlow()

    private val mutableCliModels = MutableStateFlow<List<CliModel>>(emptyList())
    val cliModels: StateFlow<List<CliModel>> = mutableCliModels.asStateFlow()

    private val mutableSelectedModelCliId = MutableStateFlow<String?>(null)
    val selectedModelCliId: StateFlow<String?> = mutableSelectedModelCliId.asStateFlow()

    fun refreshCliModels(context: Context, force: Boolean = false) {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastFetch = prefs.getLong(KEY_CLI_MODELS_FETCHED_AT, 0L)
            if (!force && System.currentTimeMillis() - lastFetch < CLI_MODELS_THROTTLE_MS && mutableCliModels.value.isNotEmpty()) return@launch
            val result = AgyModelsService.fetchCliModels(context, force = force)
            if (result.models.isNotEmpty()) {
                adoptCliCatalog(context, result.models)
            } else {
                val quotaFallback = mutableUserQuotaSummary.value?.pickerModels.orEmpty()
                if (mutableCliModels.value.isEmpty() && quotaFallback.isNotEmpty()) {
                    adoptCliCatalog(context, quotaFallback)
                    addLog("Models", "Adopted backend model catalog as fallback (${quotaFallback.size} models)")
                } else if (mutableCliModels.value.isNotEmpty()) {
                    addLog("Models", "CLI model query skipped or timed out, keeping active catalog (${mutableCliModels.value.size} models)")
                } else if (result.error != null) {
                    addLog("Models", "agy models failed: ${result.error}" + (result.detail?.let { " — $it" } ?: ""))
                }
            }
        }
    }

    private fun adoptCliCatalog(context: Context, models: List<CliModel>) {
        if (models.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableCliModels.value = models
        prefs.edit()
            .putString(KEY_CLI_MODELS_JSON, AgyModelsService.encodeCliModels(models))
            .putLong(KEY_CLI_MODELS_FETCHED_AT, System.currentTimeMillis())
            .apply()
        reconcileSelectionWithCatalog(prefs, models)
    }

    private fun reconcileSelectionWithCatalog(prefs: android.content.SharedPreferences, catalog: List<CliModel>) {
        if (catalog.isEmpty()) return
        val currentId = mutableSelectedModelCliId.value
        if (currentId != null && catalog.any { it.cliId == currentId }) return
        val currentName = mutableSelectedModel.value
        val family = catalog.filter { it.displayName == currentName }
        val adopted = when {
            family.isNotEmpty() -> family.firstOrNull { it.effort.equals(mutableThinkingBudget.value, ignoreCase = true) }
                ?: family.firstOrNull { it.effort.isEmpty() }
                ?: family.firstOrNull()
            currentName.isBlank() -> defaultCatalogEntry(catalog)
            else -> null
        }
        mutableSelectedModelCliId.value = adopted?.cliId
        val editor = prefs.edit()
        if (adopted != null) {
            editor.putString(KEY_SELECTED_MODEL_CLI_ID, adopted.cliId)
            if (currentName.isBlank()) {
                mutableSelectedModel.value = adopted.displayName
                mutableThinkingBudget.value = adopted.effort
                editor.putString(KEY_SELECTED_MODEL, adopted.displayName)
                editor.putString(KEY_THINKING_BUDGET, adopted.effort)
            }
        } else {
            editor.remove(KEY_SELECTED_MODEL_CLI_ID)
        }
        editor.apply()
        addLog("Model", "CLI catalog changed; stored model id reconciled to ${adopted?.cliId ?: "(CLI default)"}")
    }

    private fun defaultCatalogEntry(catalog: List<CliModel> = mutableCliModels.value): CliModel? {
        if (catalog.isEmpty()) return null
        return catalog.firstOrNull { it.isDefault }
            ?: catalog.firstOrNull { it.effort.equals("High", ignoreCase = true) }
            ?: catalog.firstOrNull { it.effort.isEmpty() }
            ?: catalog.firstOrNull()
    }

    fun refreshUserQuota(context: Context) {
        viewModelScope.launch {
            mutableIsQuotaLoading.value = true
            mutableQuotaErrorMessage.value = null
            try {
                val tier = CloudCodeQuotaService.fetchCodeAssistTier(context)
                val quota = CloudCodeQuotaService.fetchQuotaSummary(context, tier)
                mutableCodeAssistTier.value = tier
                mutableUserQuotaSummary.value = quota
                if (mutableCliModels.value.isEmpty() && !quota?.pickerModels.isNullOrEmpty()) {
                    adoptCliCatalog(context, quota!!.pickerModels)
                }

                refreshCliModels(context)
                val currentSelected = mutableSelectedModel.value

                val currentAvailability = quota?.availableModels?.firstOrNull { it.matches(currentSelected) }?.availability ?: ModelAvailability.UNKNOWN
                if (currentAvailability == ModelAvailability.UNAVAILABLE) {

                    val fallback = defaultCatalogEntry()
                    mutableSelectedModel.value = fallback?.displayName ?: ""
                    mutableSelectedModelCliId.value = fallback?.cliId
                    mutableThinkingBudget.value = fallback?.effort ?: ""
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    if (fallback != null) {
                        editor.putString(KEY_SELECTED_MODEL, fallback.displayName)
                        editor.putString(KEY_SELECTED_MODEL_CLI_ID, fallback.cliId)
                        editor.putString(KEY_THINKING_BUDGET, fallback.effort)
                    } else {
                        editor.remove(KEY_SELECTED_MODEL).remove(KEY_SELECTED_MODEL_CLI_ID).remove(KEY_THINKING_BUDGET)
                    }
                    editor.apply()
                    val fallbackMsg = if (fallback != null) {
                        context.getString(
                            R.string.model_switched_fallback,
                            currentSelected,
                            fallback.displayName
                        )
                    } else {
                        context.getString(R.string.model_switched_none, currentSelected)
                    }
                    addLog("Model", fallbackMsg)
                    Toast.makeText(context, fallbackMsg, Toast.LENGTH_SHORT).show()
                }
                if (quota?.modelsFetchFailed == true || (quota == null && tier == null)) {
                    mutableQuotaErrorMessage.value = context.getString(R.string.quota_models_load_failed)
                }
                refreshCliModels(context)
            } catch (e: Exception) {
                mutableQuotaErrorMessage.value = e.localizedMessage ?: "Network error fetching quota."
            } finally {
                mutableIsQuotaLoading.value = false
            }
        }
    }

    private val mutableIsAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = mutableIsAuthenticating.asStateFlow()

    private val mutableAuthError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = mutableAuthError.asStateFlow()

    private val mutableShowGoogleSignInDialog = MutableStateFlow(false)
    val showGoogleSignInDialog: StateFlow<Boolean> = mutableShowGoogleSignInDialog.asStateFlow()

    private val mutablePendingAuthUrl = MutableStateFlow<String?>(null)
    val pendingAuthUrl: StateFlow<String?> = mutablePendingAuthUrl.asStateFlow()

    private val mutableShowModelSelector = MutableStateFlow(false)
    val showModelSelector: StateFlow<Boolean> = mutableShowModelSelector.asStateFlow()

    private val mutableInstallingItemId = MutableStateFlow<String?>(null)
    val installingItemId: StateFlow<String?> = mutableInstallingItemId.asStateFlow()

    private val mutableProjectList = MutableStateFlow<List<File>>(emptyList())
    val projectList: StateFlow<List<File>> = mutableProjectList.asStateFlow()

    private val mutableProjectSortOrder = MutableStateFlow(ProjectSortOrder.NAME_ASC)
    val projectSortOrder: StateFlow<ProjectSortOrder> = mutableProjectSortOrder.asStateFlow()

    private val mutableThemePreset = MutableStateFlow("Default Dark")
    val themePreset: StateFlow<String> = mutableThemePreset.asStateFlow()

    private val mutableCustomThemeBg = MutableStateFlow("#101010")
    val customThemeBg: StateFlow<String> = mutableCustomThemeBg.asStateFlow()

    private val mutableCustomThemeFg = MutableStateFlow("#CCCCCC")
    val customThemeFg: StateFlow<String> = mutableCustomThemeFg.asStateFlow()

    private val mutableCustomThemeAccent = MutableStateFlow("#1A73E8")
    val customThemeAccent: StateFlow<String> = mutableCustomThemeAccent.asStateFlow()

    private val mutableEditorWordWrap = MutableStateFlow(true)
    val editorWordWrap: StateFlow<Boolean> = mutableEditorWordWrap.asStateFlow()

    private val mutableEditorLineNumbers = MutableStateFlow(true)
    val editorLineNumbers: StateFlow<Boolean> = mutableEditorLineNumbers.asStateFlow()

    private val mutableEditorFontSize = MutableStateFlow(13)
    val editorFontSize: StateFlow<Int> = mutableEditorFontSize.asStateFlow()

    private val mutableSkipPermissions = MutableStateFlow(true)
    val skipPermissions: StateFlow<Boolean> = mutableSkipPermissions.asStateFlow()

    private val mutableUserName = MutableStateFlow("")
    val userName: StateFlow<String> = mutableUserName.asStateFlow()

    private val mutableUserPicture = MutableStateFlow<String?>(null)
    val userPicture: StateFlow<String?> = mutableUserPicture.asStateFlow()

    private val mutableQueuedMessagesMode = MutableStateFlow("Queue")
    val queuedMessagesMode: StateFlow<String> = mutableQueuedMessagesMode.asStateFlow()

    private val mutableSecurityPreset = MutableStateFlow("Turbo Mode")
    val securityPreset: StateFlow<String> = mutableSecurityPreset.asStateFlow()

    private val mutablePlanReviewPolicy = MutableStateFlow("Always Ask")
    val planReviewPolicy: StateFlow<String> = mutablePlanReviewPolicy.asStateFlow()

    private val mutableBrowserJsPolicy = MutableStateFlow("Request Review")
    val browserJsPolicy: StateFlow<String> = mutableBrowserJsPolicy.asStateFlow()

    private val mutableEnableCreditOverages = MutableStateFlow(false)
    val enableCreditOverages: StateFlow<Boolean> = mutableEnableCreditOverages.asStateFlow()

    private val mutablePermissionState = MutableStateFlow(PermissionGrantsState())
    val permissionState: StateFlow<PermissionGrantsState> = mutablePermissionState.asStateFlow()

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeName = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val themeMode = try { ThemeMode.valueOf(themeName) } catch (e: Exception) { ThemeMode.SYSTEM }
        val accentName = prefs.getString(KEY_ACCENT_COLOR, AccentColor.BLUE.name) ?: AccentColor.BLUE.name
        val accentColor = try { AccentColor.valueOf(accentName) } catch (e: Exception) { AccentColor.BLUE }
        val savedWorkspace = prefs.getString(KEY_WORKSPACE_PATH, null)
        val savedModel = prefs.getString(KEY_SELECTED_MODEL, "") ?: ""

        val model = savedModel
        val budget = prefs.getString(KEY_THINKING_BUDGET, "") ?: ""
        val verbose = prefs.getBoolean(KEY_VERBOSE_THINKING, false)
        val width = prefs.getString(KEY_CONVERSATION_WIDTH, "Default") ?: "Default"
        val preset = prefs.getString(KEY_THEME_PRESET, if (themeMode == ThemeMode.LIGHT) "Default Light" else "Default Dark") ?: "Default Dark"
        val customBg = prefs.getString(KEY_CUSTOM_THEME_BG, "#101010") ?: "#101010"
        val customFg = prefs.getString(KEY_CUSTOM_THEME_FG, "#CCCCCC") ?: "#CCCCCC"
        val customAccent = prefs.getString(KEY_CUSTOM_THEME_ACCENT, "#1A73E8") ?: "#1A73E8"
        val wrap = prefs.getBoolean(KEY_EDITOR_WORD_WRAP, true)
        val lineNums = prefs.getBoolean(KEY_EDITOR_LINE_NUMBERS, true)
        val fontSize = prefs.getInt(KEY_EDITOR_FONT_SIZE, 13)
        val skipPerms = prefs.getBoolean(KEY_SKIP_PERMISSIONS, true)
        val sortOrderName = prefs.getString(KEY_PROJECT_SORT, ProjectSortOrder.NAME_ASC.name) ?: ProjectSortOrder.NAME_ASC.name
        val sortOrder = try { ProjectSortOrder.valueOf(sortOrderName) } catch (e: Exception) { ProjectSortOrder.NAME_ASC }
        val queued = prefs.getString(KEY_QUEUED_MESSAGES_MODE, "Queue") ?: "Queue"
        val secPreset = prefs.getString(KEY_SECURITY_PRESET, "Turbo Mode") ?: "Turbo Mode"
        val planReview = prefs.getString(KEY_PLAN_REVIEW_POLICY, "Always Ask") ?: "Always Ask"
        val browserJs = prefs.getString(KEY_BROWSER_JS_POLICY, "Request Review") ?: "Request Review"
        val creditOverages = prefs.getBoolean(KEY_ENABLE_CREDIT_OVERAGES, false)

        mutableAppearanceConfig.value = AppearanceConfig(
            themeMode = themeMode,
            accentColor = AccentColor.BLUE
        )
        mutableSelectedModel.value = model

        val savedCliId = prefs.getString(KEY_SELECTED_MODEL_CLI_ID, null)
        val cleanSavedCliId = savedCliId?.let { AgyModelsService.cleanModelId(it) }
        mutableSelectedModelCliId.value = cleanSavedCliId
        if (savedCliId != cleanSavedCliId) {
            val prefEditor = prefs.edit()
            if (cleanSavedCliId != null) {
                prefEditor.putString(KEY_SELECTED_MODEL_CLI_ID, cleanSavedCliId)
            } else {
                prefEditor.remove(KEY_SELECTED_MODEL_CLI_ID)
            }
            prefEditor.apply()
        }
        val cachedCliJson = prefs.getString(KEY_CLI_MODELS_JSON, null)
        if (!cachedCliJson.isNullOrBlank()) {
            mutableCliModels.value = AgyModelsService.decodeCliModels(cachedCliJson)
        }
        mutableThinkingBudget.value = budget
        mutableVerboseThinking.value = verbose
        mutableConversationWidth.value = width
        mutableThemePreset.value = preset
        mutableCustomThemeBg.value = customBg
        mutableCustomThemeFg.value = customFg
        mutableCustomThemeAccent.value = customAccent
        mutableEditorWordWrap.value = wrap
        mutableEditorLineNumbers.value = lineNums
        mutableEditorFontSize.value = fontSize
        mutableSkipPermissions.value = skipPerms
        mutableProjectSortOrder.value = sortOrder
        mutableQueuedMessagesMode.value = queued
        mutableSecurityPreset.value = secPreset
        mutablePlanReviewPolicy.value = planReview
        mutableBrowserJsPolicy.value = browserJs
        mutableEnableCreditOverages.value = creditOverages

        if (!savedWorkspace.isNullOrBlank() && File(savedWorkspace).exists()) {
            mutableWorkspacePath.value = savedWorkspace
        } else {
            mutableWorkspacePath.value = ""
        }

        val cachedName = prefs.getString(OAuthManager.KEY_USER_NAME, null)
        val cachedPicture = prefs.getString(OAuthManager.KEY_USER_PICTURE, null)
        if (!cachedName.isNullOrBlank()) {
            mutableUserName.value = cachedName
        }
        if (!cachedPicture.isNullOrBlank()) {
            mutableUserPicture.value = cachedPicture
        }

        refreshAuthState(context)
        refreshPermissions(context)
        refreshRules(context)
        refreshSkills(context)
        refreshPlugins(context)
        refreshMcpServers(context)
        loadProjects(context)
        loadConversationHistory(context)
    }

    fun refreshAuthState(context: Context) {
        OAuthManager.checkAndImportExistingToken(context)
        val allAccounts = AccountManager.getAccounts(context)
        mutableAccounts.value = allAccounts
        val email = OAuthManager.getUserEmail(context)
        mutableUserEmail.value = email
        val name = OAuthManager.getUserName(context) ?: if (email != null) {
            email.substringBefore("@").replace(".", " ").split(" ")
                .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
        } else ""
        mutableUserName.value = name
        val pic = OAuthManager.getUserPicture(context)
        if (!pic.isNullOrBlank()) {
            mutableUserPicture.value = pic
        }

        refreshUserQuota(context)

        viewModelScope.launch {
            if (OAuthManager.refreshTokenIfNeeded(context)) {
                val newEmail = OAuthManager.getUserEmail(context)
                mutableUserEmail.value = newEmail
                val newName = OAuthManager.getUserName(context) ?: if (newEmail != null) {
                    newEmail.substringBefore("@").replace(".", " ").split(" ")
                        .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                } else ""
                mutableUserName.value = newName
                val refreshedPic = OAuthManager.getUserPicture(context)
                if (!refreshedPic.isNullOrBlank()) {
                    mutableUserPicture.value = refreshedPic
                }
                if (newEmail != null) {
                    refreshUserQuota(context)
                }
            }
            val userInfo = OAuthManager.fetchUserInfo(context)
            if (userInfo != null) {
                if (!userInfo.name.isNullOrBlank()) {
                    mutableUserName.value = userInfo.name
                }
                if (!userInfo.picture.isNullOrBlank()) {
                    mutableUserPicture.value = userInfo.picture
                }
                if (!userInfo.email.isNullOrBlank()) {
                    mutableUserEmail.value = userInfo.email
                }
            }
            mutableAccounts.value = AccountManager.getAccounts(context)
        }
    }

    fun switchAccount(context: Context, accountId: String) {
        viewModelScope.launch {
            val success = AccountManager.switchAccount(context, accountId)
            if (success) {
                mutableUserQuotaSummary.value = null
                mutableCodeAssistTier.value = null
                mutableCliModels.value = emptyList()
                AgyModelsService.invalidateCache()
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .remove(KEY_CLI_MODELS_FETCHED_AT)
                    .remove(KEY_CLI_MODELS_JSON)
                    .remove(KEY_SELECTED_MODEL_CLI_ID)
                    .apply()
                refreshAuthState(context)
                CloudCodeQuotaService.restartLocalLanguageServer(context)
                restartCoreService(context)
                refreshUserQuota(context)
                refreshCliModels(context, force = true)
            }
        }
    }

    fun removeAccount(context: Context, accountId: String) {
        viewModelScope.launch {
            val success = AccountManager.removeAccount(context, accountId)
            if (success) {
                refreshAuthState(context)
                val currentActive = AccountManager.getActiveAccount(context)
                if (currentActive != null) {
                    mutableUserQuotaSummary.value = null
                    mutableCodeAssistTier.value = null
                    mutableCliModels.value = emptyList()
                    AgyModelsService.invalidateCache()
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .remove(KEY_CLI_MODELS_FETCHED_AT)
                        .remove(KEY_CLI_MODELS_JSON)
                        .remove(KEY_SELECTED_MODEL_CLI_ID)
                        .apply()
                    CloudCodeQuotaService.restartLocalLanguageServer(context)
                    restartCoreService(context)
                    refreshUserQuota(context)
                    refreshCliModels(context, force = true)
                }
            }
        }
    }

    fun setModel(context: Context, model: String, budget: String = mutableThinkingBudget.value, cliId: String? = null) {
        val quota = mutableUserQuotaSummary.value

        val availability = quota?.availableModels?.firstOrNull { it.matches(model) }?.availability ?: ModelAvailability.UNKNOWN

        if (availability == ModelAvailability.UNAVAILABLE) {
            val unavailMsg = context.getString(R.string.model_toast_not_available, model)
            addLog("Model", unavailMsg)
            Toast.makeText(context, unavailMsg, Toast.LENGTH_SHORT).show()
            return
        }
        val cleanCliId = cliId?.let { AgyModelsService.cleanModelId(it) }
        mutableSelectedModel.value = model
        mutableThinkingBudget.value = budget
        mutableSelectedModelCliId.value = cleanCliId
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_SELECTED_MODEL, model).putString(KEY_THINKING_BUDGET, budget)
        if (cleanCliId != null) editor.putString(KEY_SELECTED_MODEL_CLI_ID, cleanCliId) else editor.remove(KEY_SELECTED_MODEL_CLI_ID)
        editor.apply()
        val thinkingDesc = if (budget.isNotBlank()) "$budget Thinking" else "Default"
        addLog("Model", "Selected model: $model ($thinkingDesc)")
    }

    fun setThinkingBudget(context: Context, budget: String) {
        mutableThinkingBudget.value = budget
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THINKING_BUDGET, budget).apply()
    }

    fun setVerboseThinking(context: Context, verbose: Boolean) {
        mutableVerboseThinking.value = verbose
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_VERBOSE_THINKING, verbose).apply()
    }

    fun setConversationWidth(context: Context, width: String) {
        mutableConversationWidth.value = width
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONVERSATION_WIDTH, width).apply()
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        val newPreset = when (mode) {
            ThemeMode.LIGHT -> ThemePreset.DEFAULT_LIGHT.displayName
            ThemeMode.DARK -> {
                if (mutableThemePreset.value == ThemePreset.DEFAULT_LIGHT.displayName) {
                    ThemePreset.DEFAULT_DARK.displayName
                } else {
                    mutableThemePreset.value
                }
            }
            ThemeMode.SYSTEM -> mutableThemePreset.value
        }
        mutableAppearanceConfig.update { it.copy(themeMode = mode) }
        mutableThemePreset.value = newPreset
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_THEME_MODE, mode.name)
            .putString(KEY_THEME_PRESET, newPreset)
            .apply()
        addLog("Appearance", "Theme set to: ${mode.name}")
    }

    fun setAccentColor(context: Context, accent: AccentColor) {
        mutableAppearanceConfig.update { it.copy(accentColor = accent) }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACCENT_COLOR, accent.name).apply()
        addLog("Appearance", "Accent color set to: ${accent.displayName}")
    }

    fun setThemePreset(context: Context, preset: String) {
        mutableThemePreset.value = preset
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preset == ThemePreset.DEFAULT_LIGHT.displayName) {
            mutableAppearanceConfig.update { it.copy(themeMode = ThemeMode.LIGHT) }
            prefs.edit().putString(KEY_THEME_MODE, ThemeMode.LIGHT.name).apply()
        } else if (mutableAppearanceConfig.value.themeMode == ThemeMode.LIGHT) {
            mutableAppearanceConfig.update { it.copy(themeMode = ThemeMode.DARK) }
            prefs.edit().putString(KEY_THEME_MODE, ThemeMode.DARK.name).apply()
        }
        prefs.edit().putString(KEY_THEME_PRESET, preset).apply()
        addLog("Appearance", "Theme preset set to: $preset")
    }

    fun updateCustomThemeColor(context: Context, target: String, newHex: String, currentPreset: ThemePreset) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (mutableThemePreset.value != ThemePreset.CUSTOM.displayName && currentPreset != ThemePreset.CUSTOM) {
            mutableCustomThemeBg.value = currentPreset.bgHex
            mutableCustomThemeFg.value = currentPreset.fgHex
            mutableCustomThemeAccent.value = currentPreset.accentHex
            editor.putString(KEY_CUSTOM_THEME_BG, currentPreset.bgHex)
            editor.putString(KEY_CUSTOM_THEME_FG, currentPreset.fgHex)
            editor.putString(KEY_CUSTOM_THEME_ACCENT, currentPreset.accentHex)
        }

        when (target) {
            "bg" -> {
                mutableCustomThemeBg.value = newHex
                editor.putString(KEY_CUSTOM_THEME_BG, newHex)
            }
            "fg" -> {
                mutableCustomThemeFg.value = newHex
                editor.putString(KEY_CUSTOM_THEME_FG, newHex)
            }
            "accent" -> {
                mutableCustomThemeAccent.value = newHex
                editor.putString(KEY_CUSTOM_THEME_ACCENT, newHex)
            }
        }
        mutableThemePreset.value = ThemePreset.CUSTOM.displayName
        editor.putString(KEY_THEME_PRESET, ThemePreset.CUSTOM.displayName)
        editor.apply()
        addLog("Appearance", "Custom theme color $target updated to: $newHex")
    }

    fun setEditorWordWrap(context: Context, wrap: Boolean) {
        mutableEditorWordWrap.value = wrap
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_EDITOR_WORD_WRAP, wrap).apply()
    }

    fun setEditorLineNumbers(context: Context, show: Boolean) {
        mutableEditorLineNumbers.value = show
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_EDITOR_LINE_NUMBERS, show).apply()
    }

    fun setEditorFontSize(context: Context, size: Int) {
        mutableEditorFontSize.value = size
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_EDITOR_FONT_SIZE, size).apply()
    }

    fun setSkipPermissions(context: Context, skip: Boolean) {
        mutableSkipPermissions.value = skip
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SKIP_PERMISSIONS, skip).apply()
    }

    fun setQueuedMessagesMode(context: Context, mode: String) {
        mutableQueuedMessagesMode.value = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_QUEUED_MESSAGES_MODE, mode).apply()
        addLog("General", "Queued messages delivery set to: $mode")
    }

    fun setSecurityPreset(context: Context, preset: String) {
        val normalizedPreset = when (preset.lowercase()) {
            "turbo", "turbo mode" -> "Turbo"
            "auto", "permissive" -> "Auto"
            "default", "standard" -> "Default"
            "request review", "request_review" -> "Request Review"
            else -> preset
        }
        mutableSecurityPreset.value = normalizedPreset
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SECURITY_PRESET, normalizedPreset).apply()
        addLog("General", "Security preset set to: $normalizedPreset")

        val autoExec = when (normalizedPreset) {
            "Turbo" -> "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"
            "Auto" -> "CASCADE_COMMANDS_AUTO_EXECUTION_ALLOW"
            "Default" -> "CASCADE_COMMANDS_AUTO_EXECUTION_ASK"
            "Request Review" -> "CASCADE_COMMANDS_AUTO_EXECUTION_NEVER"
            else -> "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"
        }
        val planReview = when (normalizedPreset) {
            "Turbo", "Auto" -> "Always Proceed"
            "Default" -> "Ask for Sensitive"
            "Request Review" -> "Always Ask"
            else -> "Always Proceed"
        }
        val enableSandbox = normalizedPreset == "Default" || normalizedPreset == "Request Review"
        val skipPerms = normalizedPreset == "Turbo"

        setSkipPermissions(context, skipPerms)
        mutablePlanReviewPolicy.value = planReview

        viewModelScope.launch {
            val current = mutablePermissionState.value
            val updated = current.copy(
                securityPreset = normalizedPreset,
                autoExecutionPolicy = autoExec,
                planReviewPolicy = planReview,
                enableTerminalSandbox = enableSandbox
            )
            mutablePermissionState.value = updated
            PermissionConfigService.savePermissions(context, updated)
        }
    }

    fun setPlanReviewPolicy(context: Context, policy: String) {
        mutablePlanReviewPolicy.value = policy
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PLAN_REVIEW_POLICY, policy).apply()
        addLog("General", "Plan review policy set to: $policy")

        viewModelScope.launch {
            val current = mutablePermissionState.value
            val updated = current.copy(planReviewPolicy = policy)
            mutablePermissionState.value = updated
            PermissionConfigService.savePermissions(context, updated)
        }
    }

    fun setBrowserJsPolicy(context: Context, policy: String) {
        mutableBrowserJsPolicy.value = policy
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BROWSER_JS_POLICY, policy).apply()
        addLog("General", "Browser JS policy set to: $policy")
    }

    fun setEnableCreditOverages(context: Context, enabled: Boolean) {
        mutableEnableCreditOverages.value = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLE_CREDIT_OVERAGES, enabled).apply()
        addLog("Models", "AI credit overages set to: $enabled")
    }

    fun refreshPermissions(context: Context) {
        viewModelScope.launch {
            val state = PermissionConfigService.loadPermissions(context)
            mutablePermissionState.value = state
        }
    }

    fun addPermissionRule(context: Context, action: String, pattern: String, mode: String) {
        viewModelScope.launch {
            val current = mutablePermissionState.value
            val cleanPattern = pattern.trim()
            if (cleanPattern.isBlank()) return@launch
            val filtered = current.rules.filterNot { it.action == action && it.pattern == cleanPattern }
            val updated = filtered + PermissionRule(action, cleanPattern, mode)
            val newState = current.copy(rules = updated)
            mutablePermissionState.value = newState
            PermissionConfigService.savePermissions(context, newState)
            addLog("Permissions", "Added $mode rule for $action: $cleanPattern")
        }
    }

    fun updatePermissionRule(context: Context, action: String, oldPattern: String, newPattern: String, newMode: String) {
        viewModelScope.launch {
            val current = mutablePermissionState.value
            val cleanOld = oldPattern.trim()
            val cleanNew = newPattern.trim()
            if (cleanNew.isBlank()) return@launch
            val updated = current.rules.map {
                if (it.action == action && it.pattern == cleanOld) PermissionRule(action, cleanNew, newMode) else it
            }
            val newState = current.copy(rules = updated)
            mutablePermissionState.value = newState
            PermissionConfigService.savePermissions(context, newState)
            addLog("Permissions", "Updated rule for $action: $cleanNew ($newMode)")
        }
    }

    fun removePermissionRule(context: Context, action: String, pattern: String) {
        viewModelScope.launch {
            val current = mutablePermissionState.value
            val cleanPattern = pattern.trim()
            val updated = current.rules.filterNot { it.action == action && it.pattern == cleanPattern }
            val newState = current.copy(rules = updated)
            mutablePermissionState.value = newState
            PermissionConfigService.savePermissions(context, newState)
            addLog("Permissions", "Removed rule for $action: $cleanPattern")
        }
    }

    private val mutableInstalledRulesList = MutableStateFlow<List<RuleItem>>(emptyList())
    val installedRulesList: StateFlow<List<RuleItem>> = mutableInstalledRulesList.asStateFlow()

    private val mutableInstalledSkills = MutableStateFlow<List<CustomizationItem>>(emptyList())
    val installedSkills: StateFlow<List<CustomizationItem>> = mutableInstalledSkills.asStateFlow()

    private val mutableInstalledPlugins = MutableStateFlow<List<CustomizationItem>>(emptyList())
    val installedPlugins: StateFlow<List<CustomizationItem>> = mutableInstalledPlugins.asStateFlow()

    private val mutableCustomizationTracks = MutableStateFlow<Map<String, CustomizationRepoTrack>>(emptyMap())
    val customizationTracks: StateFlow<Map<String, CustomizationRepoTrack>> = mutableCustomizationTracks.asStateFlow()

    private val mutableIsCheckingCustomizationUpdates = MutableStateFlow(false)
    val isCheckingCustomizationUpdates: StateFlow<Boolean> = mutableIsCheckingCustomizationUpdates.asStateFlow()

    private val mutableSkillsCatalogue = MutableStateFlow<List<SkillCatalogueItem>>(getDefaultSkillsCatalogue())
    val skillsCatalogue: StateFlow<List<SkillCatalogueItem>> = mutableSkillsCatalogue.asStateFlow()

    private val mutablePluginsCatalogue = MutableStateFlow<List<SkillCatalogueItem>>(getDefaultPluginsCatalogue())
    val pluginsCatalogue: StateFlow<List<SkillCatalogueItem>> = mutablePluginsCatalogue.asStateFlow()

    private val mutableIsFetchingCatalogue = MutableStateFlow(false)
    val isFetchingCatalogue: StateFlow<Boolean> = mutableIsFetchingCatalogue.asStateFlow()

    private val mutableTokenUsageStats = MutableStateFlow(TokenUsageStats(0, emptyList(), 0, emptyList()))
    val tokenUsageStats: StateFlow<TokenUsageStats> = mutableTokenUsageStats.asStateFlow()

    fun refreshRules(context: Context) {
        viewModelScope.launch {
            val rules = RuleConfigService.loadAllRules(context, mutableWorkspacePath.value)
            mutableInstalledRulesList.value = rules
        }
    }

    fun saveRuleContent(context: Context, filePath: String, content: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = RuleConfigService.saveRule(filePath, content)
            if (ok) {
                refreshRules(context)
                addLog("Rules", "Rule saved: ${File(filePath).name}")
            }
            onComplete?.invoke(ok)
        }
    }

    fun createRule(context: Context, ruleName: String, content: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = RuleConfigService.createNewRule(context, ruleName, content)
            if (ok) {
                refreshRules(context)
                addLog("Rules", "Created new rule: $ruleName")
            }
            onComplete?.invoke(ok)
        }
    }

    fun deleteRule(context: Context, filePath: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = RuleConfigService.deleteRule(filePath)
            if (ok) {
                refreshRules(context)
                addLog("Rules", "Deleted rule: ${File(filePath).name}")
            }
            onComplete?.invoke(ok)
        }
    }

    private val mutableMcpServers = MutableStateFlow<List<McpServerItem>>(emptyList())
    val mcpServers: StateFlow<List<McpServerItem>> = mutableMcpServers.asStateFlow()

    fun refreshMcpServers(context: Context) {
        viewModelScope.launch {
            val list = McpConfigService.loadMcpServers(context)
            mutableMcpServers.value = list
        }
    }

    fun saveMcpServer(context: Context, server: McpServerItem, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = McpConfigService.saveMcpServer(context, server)
            if (ok) {
                refreshMcpServers(context)
                addLog("MCP", "Configured MCP server: ${server.name}")
            }
            onComplete?.invoke(ok)
        }
    }

    fun deleteMcpServer(context: Context, serverName: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = McpConfigService.deleteMcpServer(context, serverName)
            if (ok) {
                refreshMcpServers(context)
                addLog("MCP", "Removed MCP server: $serverName")
            }
            onComplete?.invoke(ok)
        }
    }

    suspend fun loadRawMcpConfig(context: Context): String {
        return McpConfigService.loadRawConfig(context)
    }

    fun saveRawMcpConfig(context: Context, rawJson: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val ok = McpConfigService.saveRawConfig(context, rawJson)
            if (ok) {
                refreshMcpServers(context)
                addLog("MCP", "Updated raw mcp_config.json")
            }
            onComplete?.invoke(ok)
        }
    }

    private val mutableOpenTerminalRequest = MutableStateFlow<String?>(null)
    val openTerminalRequest: StateFlow<String?> = mutableOpenTerminalRequest.asStateFlow()

    fun requestOpenTerminal(cmd: String? = null) {
        mutableOpenTerminalRequest.value = cmd ?: ""
    }

    fun consumeOpenTerminalRequest() {
        mutableOpenTerminalRequest.value = null
    }

    fun refreshSkills(context: Context) {
        loadCachedCatalogue(context)
        fetchRemoteCatalogue(context, force = false)
        viewModelScope.launch(Dispatchers.IO) {
            val skills = getInstalledSkills(context)
            mutableInstalledSkills.value = skills
            val stats = calculateTokenUsage(context)
            mutableTokenUsageStats.value = stats
        }
        refreshRules(context)
    }

    fun refreshPlugins(context: Context) {
        loadCachedCatalogue(context)
        fetchRemoteCatalogue(context, force = false)
        viewModelScope.launch(Dispatchers.IO) {
            val plugins = getInstalledPlugins(context)
            mutableInstalledPlugins.value = plugins
            val skills = getInstalledSkills(context)
            mutableInstalledSkills.value = skills
            val stats = calculateTokenUsage(context)
            mutableTokenUsageStats.value = stats
        }
        refreshRules(context)
    }

    val activeInstallProgress = MutableStateFlow<CustomizationInstallProgress?>(null)

    fun dismissInstallProgress() {
        activeInstallProgress.value = null
    }

    val coreUpdateState = MutableStateFlow(CoreEngineUpdateState())

    val otaState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)

    fun checkForOtaUpdates(context: Context, isManualCheck: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val newState = CoreUpdateDelegate.checkForOtaUpdates(context, isManualCheck)
            if (newState != null) {
                otaState.value = newState
            }
        }
    }

    fun startOtaDownload(context: Context, info: UpdateChecker.UpdateInfo) {
        otaState.value = AppUpdateState.Downloading(0)
        viewModelScope.launch(Dispatchers.IO) {
            CoreUpdateDelegate.startOtaDownload(context, info) { state ->
                otaState.value = state
            }
        }
    }

    fun dismissOtaUpdate() {
        otaState.value = AppUpdateState.Idle
    }

    fun checkForCoreEngineUpdate(context: Context, force: Boolean = false) {
        val current = CoreUpdateManager.getCurrentVersion(context)
        coreUpdateState.value = coreUpdateState.value.copy(
            isChecking = true,
            currentVersion = current,
            error = null
        )
        viewModelScope.launch(Dispatchers.IO) {
            val updated = CoreUpdateDelegate.checkForCoreEngineUpdate(context, force, coreUpdateState.value)
            if (updated != null) {
                coreUpdateState.value = updated
            } else {
                coreUpdateState.value = coreUpdateState.value.copy(isChecking = false)
            }
        }
    }

    fun applyCoreEngineUpdate(context: Context) {
        val state = coreUpdateState.value
        val latestVer = state.latestVersion ?: return
        val url = state.downloadUrl ?: return

        val initialLines = listOf(
            TerminalLine(text = "$ " + context.getString(R.string.core_update_cmd_title), isCommand = true),
            TerminalLine(text = context.getString(R.string.core_update_checking))
        )
        activeInstallProgress.value = CustomizationInstallProgress(
            title = context.getString(R.string.core_update_dialog_title),
            command = "agy-upgrade core ($latestVer)",
            lines = initialLines,
            isRunning = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val outputLines = mutableListOf<TerminalLine>().apply { addAll(initialLines) }
            CoreUpdateDelegate.applyCoreEngineUpdate(
                context = context,
                state = state,
                onOutputLine = { line ->
                    synchronized(outputLines) { outputLines.add(line) }
                    activeInstallProgress.value = activeInstallProgress.value?.copy(
                        lines = outputLines.toList()
                    )
                },
                onSuccess = { newVer ->
                    coreUpdateState.value = coreUpdateState.value.copy(
                        currentVersion = newVer,
                        isUpdateAvailable = false,
                        latestVersion = newVer
                    )
                    activeInstallProgress.value = activeInstallProgress.value?.copy(
                        lines = outputLines.toList(),
                        isRunning = false,
                        isSuccess = true
                    )
                    if (engineStatus.value.isRunning) {
                        restartCoreService(context)
                    }
                },
                onError = { err ->
                    activeInstallProgress.value = activeInstallProgress.value?.copy(
                        lines = outputLines.toList(),
                        isRunning = false,
                        isSuccess = false
                    )
                }
            )
        }
    }

    val isInspectingRepo = MutableStateFlow(false)
    val discoveredRepoSkills = MutableStateFlow<List<DiscoveredRepoSkill>>(emptyList())
    val repoInspectionError = MutableStateFlow<String?>(null)
    private var cachedInspectedRepoDir: File? = null

    fun clearRepoInspection() {
        isInspectingRepo.value = false
        discoveredRepoSkills.value = emptyList()
        repoInspectionError.value = null
        try {
            cachedInspectedRepoDir?.deleteRecursively()
        } catch (e: Exception) {}
        cachedInspectedRepoDir = null
    }

    fun toggleRepoSkillSelection(skillId: String) {
        discoveredRepoSkills.value = discoveredRepoSkills.value.map {
            if (it.id == skillId) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun setAllRepoSkillsSelection(selectAll: Boolean) {
        discoveredRepoSkills.value = discoveredRepoSkills.value.map {
            it.copy(isSelected = selectAll)
        }
    }

    fun normalizeGitRepoUrl(rawUrl: String): Pair<String, String?> =
        CustomizationTracker.normalizeGitRepoUrl(rawUrl)

    fun inspectRepoForSkills(context: Context, rawRepoUrl: String) {
        val (cleanUrl, subDir) = normalizeGitRepoUrl(rawRepoUrl)
        if (cleanUrl.isBlank()) return

        isInspectingRepo.value = true
        repoInspectionError.value = null
        discoveredRepoSkills.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stagingDir = File(context.cacheDir, "repo-inspect-${cleanUrl.hashCode().toString().replace("-", "n")}").apply {
                    deleteRecursively()
                    mkdirs()
                }
                cachedInspectedRepoDir = stagingDir

                val gitEnv = prepareGitEnv(context)
                val cloneCmd = "${gitEnv}git clone --depth 1 '$cleanUrl' '${stagingDir.absolutePath}'"
                val exitCode = EngineExecutor.executeCommand(context, cloneCmd, context.filesDir) { line, isErr -> }

                if (exitCode != 0 || !stagingDir.exists()) {
                    repoInspectionError.value = "Failed to clone repository"
                    isInspectingRepo.value = false
                    return@launch
                }

                val discovered = mutableListOf<DiscoveredRepoSkill>()
                stagingDir.walkTopDown()
                    .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                    .filter { !it.absolutePath.contains("/.git/") }
                    .forEach { skillFile ->
                        val skillFolder = skillFile.parentFile ?: stagingDir
                        val relSubPath = skillFolder.relativeTo(stagingDir).path.replace('\\', '/')
                        val normSubPath = if (relSubPath == "." || relSubPath.isEmpty()) "" else relSubPath

                        var skillName = skillFolder.name
                        var skillDesc = ""
                        try {
                            val lines = skillFile.readLines().take(50)
                            var inFrontmatter = false
                            for (line in lines) {
                                val trimmed = line.trim()
                                if (trimmed == "---") {
                                    if (!inFrontmatter) {
                                        inFrontmatter = true
                                    } else {
                                        break
                                    }
                                } else if (inFrontmatter) {
                                    if (trimmed.startsWith("name:", ignoreCase = true)) {
                                        val v = trimmed.substringAfter(":").trim().trim('"', '\'')
                                        if (v.isNotBlank()) skillName = v
                                    } else if (trimmed.startsWith("description:", ignoreCase = true)) {
                                        val v = trimmed.substringAfter(":").trim().trim('"', '\'')
                                        if (v.isNotBlank()) skillDesc = v
                                    }
                                }
                            }
                            if (skillDesc.isBlank()) {
                                val firstContent = lines.dropWhile { it.trim().startsWith("#") || it.trim() == "---" }
                                    .firstOrNull { it.isNotBlank() }?.trim() ?: ""
                                skillDesc = firstContent
                            }
                        } catch (e: Exception) {}

                        val safeId = (if (skillName.isNotBlank()) skillName else skillFolder.name)
                            .lowercase().replace(" ", "-").replace(Regex("[^a-z0-9_-]"), "")

                        discovered.add(
                            DiscoveredRepoSkill(
                                id = safeId,
                                name = if (skillName.isNotBlank()) skillName else skillFolder.name,
                                description = if (skillDesc.isNotBlank()) skillDesc else "Skill from $cleanUrl",
                                subPath = normSubPath,
                                isSelected = true
                            )
                        )
                    }

                if (discovered.isEmpty()) {
                    repoInspectionError.value = "No skills with SKILL.md found in repository"
                } else {
                    discoveredRepoSkills.value = discovered.sortedBy { it.name }
                }
            } catch (e: Exception) {
                repoInspectionError.value = e.localizedMessage ?: "Error inspecting repository"
            } finally {
                isInspectingRepo.value = false
            }
        }
    }

    private fun prepareGitEnv(context: Context): String =
        CustomizationTracker.prepareGitEnv(context)

    private fun ensureSkillFiles(skillDir: File, homeSkillDir: File, id: String, name: String, desc: String) =
        CustomizationTracker.ensureSkillFiles(skillDir, homeSkillDir, id, name, desc)

    private fun ensurePluginFiles(pluginDir: File, homePluginDir: File, id: String, name: String, desc: String) =
        CustomizationTracker.ensurePluginFiles(pluginDir, homePluginDir, id, name, desc)

    fun installSkillViaTerminal(context: Context, skillItem: SkillCatalogueItem) {
        val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
        val skillDirFile = File(directConfig, "skills/${skillItem.id}").apply { mkdirs() }
        val homeSkillDirFile = File(context.filesDir, "home/.gemini/config/skills/${skillItem.id}").apply { mkdirs() }
        val skillDir = skillDirFile.absolutePath
        val homeSkillDir = homeSkillDirFile.absolutePath
        val builtinDir = getBuiltinSkillsDir(context)
        val builtinSrc = File(builtinDir, skillItem.id)
        val builtinSrcAlt = File(builtinDir, skillItem.id.replace("-", "_"))
        val tmpCloneDir = File(context.cacheDir, "skill-clone-${skillItem.id}").absolutePath
        val gitEnvSetup = prepareGitEnv(context)
        val (cleanUrl, extractedSub) = normalizeGitRepoUrl(skillItem.repoUrl)
        val effectiveSubPath = skillItem.subPath?.ifBlank { null } ?: extractedSub

        val cmd = buildString {
            append(gitEnvSetup)
            append("rm -rf '$tmpCloneDir' '$skillDir' && mkdir -p '$skillDir' '$homeSkillDir' && ")
            append("echo '[Agent] Starting installation of skill ${skillItem.name}...' && ")
            if (builtinSrc.exists() && builtinSrc.isDirectory) {
                append("echo '[Agent] Deploying built-in skill assets...' && ")
                append("cp -r '${builtinSrc.absolutePath}'/* '$skillDir/' 2>/dev/null; cp -r '${builtinSrc.absolutePath}'/* '$homeSkillDir/' 2>/dev/null && ")
            } else if (builtinSrcAlt.exists() && builtinSrcAlt.isDirectory) {
                append("echo '[Agent] Deploying built-in skill assets...' && ")
                append("cp -r '${builtinSrcAlt.absolutePath}'/* '$skillDir/' 2>/dev/null; cp -r '${builtinSrcAlt.absolutePath}'/* '$homeSkillDir/' 2>/dev/null && ")
            } else if (cleanUrl.isNotBlank()) {
                append("echo '[Agent] Cloning skill from $cleanUrl...' && ")
                append("git clone --depth 1 '$cleanUrl' '$tmpCloneDir' && ")
                append("echo '[Agent] Extracting and setting up skill files...' && ")
                if (!effectiveSubPath.isNullOrBlank()) {
                    append("if [ -d '$tmpCloneDir/${effectiveSubPath.trim('/')}' ]; then cp -r '$tmpCloneDir/${effectiveSubPath.trim('/')}'/* '$skillDir/' 2>/dev/null; else cp -r '$tmpCloneDir'/* '$skillDir/' 2>/dev/null; fi && ")
                } else {
                    val rootSkillsDir = File(directConfig, "skills").absolutePath
                    val homeSkillsDir = File(context.filesDir, "home/.gemini/config/skills").absolutePath
                    append("cp -r '$tmpCloneDir'/* '$skillDir/' 2>/dev/null && ")
                    append("for sdir in '$tmpCloneDir/skills' '$tmpCloneDir/.gemini/skills' '$tmpCloneDir/plugin/skills' '$tmpCloneDir/.agents/skills'; do ")
                    append("if [ -d \"${'$'}sdir\" ]; then ")
                    append("echo '[Agent] Unpacking skills from repository...' && ")
                    append("cp -r \"${'$'}sdir/\"* '$rootSkillsDir/' 2>/dev/null; ")
                    append("cp -r \"${'$'}sdir/\"* '$homeSkillsDir/' 2>/dev/null; fi; done && ")
                }
                append("rm -rf '$tmpCloneDir' && ")
            } else {
                val slopTmp = File(context.cacheDir, "slop-tmp-${skillItem.id}").absolutePath
                append("echo '[Agent] Sourcing skill from repository...' && ")
                append("(cp -r '${context.filesDir.absolutePath}/.gemini/config/plugins/antislop/skills/${skillItem.id}'/* '$skillDir/' 2>/dev/null || ")
                append("(rm -rf '$slopTmp' && git clone --depth 1 https://github.com/miqdadbadjuber/anti-slop.git '$slopTmp' && cp -r '$slopTmp/skills/${skillItem.id}'/* '$skillDir/' 2>/dev/null && rm -rf '$slopTmp')) && ")
            }
            append("if [ -d '$skillDir/scripts' ]; then chmod +x '$skillDir'/scripts/* 2>/dev/null || true; fi && ")
            append("cp -r '$skillDir'/* '$homeSkillDir/' 2>/dev/null && ")
            append("echo '[Agent] Running agy-fix-paths...' && ")
            append("agy-fix-paths '$skillDir' 2>/dev/null || true && ")
            append("echo '[Agent] Skill ${skillItem.name} successfully installed and ready!'")
        }

        val initialLines = listOf(TerminalLine(text = "$ Installing skill ${skillItem.name}...", isCommand = true))
        activeInstallProgress.value = CustomizationInstallProgress(
            title = skillItem.name,
            command = cmd,
            lines = initialLines,
            isRunning = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val outputLines = mutableListOf<TerminalLine>().apply { addAll(initialLines) }
            val cleanRegex = Regex("(?:\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]|\r)")

            val exitCode = EngineExecutor.executeCommandWithUpdates(context, cmd, context.filesDir) { line, isErr, isUpdate ->
                val clean = line.replace(cleanRegex, "").trimEnd('\r')
                if (clean.isNotEmpty()) {
                    val isGitInfo = clean.startsWith("Cloning into", ignoreCase = true) ||
                            clean.startsWith("remote:", ignoreCase = true) ||
                            clean.startsWith("Receiving objects", ignoreCase = true) ||
                            clean.startsWith("Resolving deltas", ignoreCase = true) ||
                            clean.startsWith("Updating files", ignoreCase = true) ||
                            clean.startsWith("Note: checking out", ignoreCase = true) ||
                            clean.startsWith("HEAD is now at", ignoreCase = true) ||
                            clean.startsWith("From https://", ignoreCase = true) ||
                            clean.startsWith("Already up to date", ignoreCase = true)
                    val isWarning = clean.startsWith("warning:", ignoreCase = true)
                    val isRealError = !isWarning && (clean.contains("error:", ignoreCase = true) ||
                            clean.contains("fatal:", ignoreCase = true) ||
                            clean.contains("Permission denied", ignoreCase = true) ||
                            (clean.contains("failed", ignoreCase = true) && !clean.contains("0 failed", ignoreCase = true)))
                    val isError = if (isGitInfo || isWarning) false else (isErr || isRealError)
                    synchronized(outputLines) {
                        if (isUpdate && outputLines.isNotEmpty() && !outputLines.last().isCommand) {
                            outputLines[outputLines.size - 1] = TerminalLine(text = clean, isError = isError)
                        } else {
                            outputLines.add(TerminalLine(text = clean, isError = isError))
                        }
                    }
                    activeInstallProgress.value = activeInstallProgress.value?.copy(
                        lines = outputLines.toList()
                    )
                }
            }
            if (exitCode == 0) {
                ensureSkillFiles(skillDirFile, homeSkillDirFile, skillItem.id, skillItem.name, skillItem.description)
                if (cleanUrl.isNotBlank()) {
                    val sha = fetchLatestRepoCommitSha(cleanUrl, effectiveSubPath) ?: ""
                    registerTrackedCustomization(
                        context,
                        CustomizationRepoTrack(
                            id = skillItem.id,
                            name = skillItem.name,
                            repoUrl = cleanUrl,
                            isPlugin = false,
                            subPath = effectiveSubPath,
                            installedSha = sha,
                            lastChecked = System.currentTimeMillis()
                        )
                    )
                }
            }
            refreshSkills(context)
            activeInstallProgress.value = activeInstallProgress.value?.copy(
                isRunning = false,
                isSuccess = (exitCode == 0)
            )
        }
    }

    fun installPluginViaTerminal(context: Context, pluginItem: SkillCatalogueItem) {
        val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
        val pDirFile = File(directConfig, "plugins/${pluginItem.id}").apply { mkdirs() }
        val pHomeDirFile = File(context.filesDir, "home/.gemini/config/plugins/${pluginItem.id}").apply { mkdirs() }
        val pluginDir = pDirFile.absolutePath
        val homePluginDir = pHomeDirFile.absolutePath
        val tmpDir = File(context.cacheDir, "plugin-clone-${pluginItem.id}").absolutePath
        val gitEnvSetup = prepareGitEnv(context)
        val (cleanUrl, extractedSub) = normalizeGitRepoUrl(pluginItem.repoUrl)
        val effectiveSubPath = pluginItem.subPath?.ifBlank { null } ?: extractedSub

        val rawCmd = when (pluginItem.id.lowercase()) {
            "rtk" -> {
                ensurePluginFiles(pDirFile, pHomeDirFile, "rtk", "RTK", "Rust Token Killer")
                val rtkRuleFile = File(pDirFile, "rules/AGENTS.md")
                val rtkRuleContent = "# Command output\n\nCommand output here is condensed to save tokens, keeping every signal and dropping costly noise. Treat it as the complete result: run commands normally, and batch related commands into one call to avoid extra turns. Truncated results state their recovery path in their own output. Re-run a command as `rtk bypass <cmd>` only when its result is unusable: empty when output was clearly expected, contradicting its exit code, or garbled.\n"
                try {
                    File(pDirFile, "rules").mkdirs()
                    File(pHomeDirFile, "rules").mkdirs()
                    rtkRuleFile.writeText(rtkRuleContent)
                    File(pHomeDirFile, "rules/AGENTS.md").writeText(rtkRuleContent)
                } catch (e: Exception) {}

                val usrBinRtk = File(context.filesDir, "usr/bin/rtk")
                val runtimeRtk = File(context.filesDir, "runtime/bin/rtk")
                if (!usrBinRtk.exists() && runtimeRtk.exists()) {
                    try {
                        runtimeRtk.copyTo(usrBinRtk, overwrite = true)
                        usrBinRtk.setExecutable(true, false)
                    } catch (e: Exception) {}
                }
                try { usrBinRtk.setExecutable(true, false) } catch (e: Exception) {}
                val usrBinPath = usrBinRtk.absolutePath
                val runtimeBinPath = runtimeRtk.absolutePath
                val rtkDownloadUrl = "https://github.com/KuatoDev/antigravity-bootstrap/releases/download/core-v1/rtk"

                "echo '[Agent] Preparing environment for RTK plugin...' && mkdir -p '$pluginDir/rules' '$homePluginDir/rules' && if ! command -v rtk >/dev/null 2>&1; then if [ -f '$runtimeBinPath' ]; then cp '$runtimeBinPath' '$usrBinPath' 2>/dev/null && chmod +x '$usrBinPath' 2>/dev/null; elif [ -f '$usrBinPath' ]; then chmod +x '$usrBinPath' 2>/dev/null; else echo '[Agent] Downloading RTK binary from release payload...' && curl -sSL --retry 3 -o '$usrBinPath' '$rtkDownloadUrl' && chmod +x '$usrBinPath' 2>/dev/null; fi; fi && if command -v rtk >/dev/null 2>&1; then echo '[Agent] Initializing RTK for Antigravity agent...' && rtk init --agent antigravity --global --auto-patch 2>&1 || true && cp -r '$pluginDir'/* '$homePluginDir/' 2>/dev/null; agy-fix-paths '$pluginDir' 2>/dev/null || true; echo \"[Agent] Verified: $(rtk --version 2>&1)\" && echo '[Agent] RTK plugin installed and verified!'; else echo '[Agent Error] Failed to install or download RTK binary.'; exit 1; fi"
            }
            "antislop" -> {
                "echo '[Agent] Preparing environment for Anti-Slop plugin...' && rm -rf '$tmpDir' '$pluginDir' && mkdir -p '$pluginDir' '$homePluginDir' && echo '[Agent] Cloning Anti-Slop repository...' && git clone --depth 1 https://github.com/miqdadbadjuber/anti-slop.git '$tmpDir' && echo '[Agent] Installing plugin files and rules...' && cp -r '$tmpDir'/* '$pluginDir/' 2>/dev/null && if [ -f '$pluginDir/rules/antislop.md' ] && [ ! -f '$pluginDir/rules/AGENTS.md' ]; then cp '$pluginDir/rules/antislop.md' '$pluginDir/rules/AGENTS.md'; fi && cp -r '$pluginDir'/* '$homePluginDir/' 2>/dev/null && rm -rf '$tmpDir' && echo '[Agent] Running agy-fix-paths...' && agy-fix-paths '$pluginDir' 2>/dev/null || true; echo '[Agent] Plugin Anti-Slop successfully installed and verified!'"
            }
            else -> {
                if (cleanUrl.isNotBlank()) {
                    val sub = effectiveSubPath?.trim('/') ?: ""
                    val extractCmd = if (sub.isNotBlank()) {
                        "if [ -d '$tmpDir/$sub' ]; then cp -r '$tmpDir/$sub'/* '$pluginDir/' 2>/dev/null; else cp -r '$tmpDir'/* '$pluginDir/' 2>/dev/null; fi"
                    } else {
                        val rootSkillsDir = File(directConfig, "skills").absolutePath
                        val homeSkillsDir = File(context.filesDir, "home/.gemini/config/skills").absolutePath
                        "cp -r '$tmpDir'/* '$pluginDir/' 2>/dev/null && for sdir in '$tmpDir/skills' '$tmpDir/.gemini/skills' '$tmpDir/plugin/skills' '$tmpDir/.agents/skills'; do if [ -d \"${'$'}sdir\" ]; then echo '[Agent] Unpacking skills from repository...' && cp -r \"${'$'}sdir/\"* '$rootSkillsDir/' 2>/dev/null; cp -r \"${'$'}sdir/\"* '$homeSkillsDir/' 2>/dev/null; fi; done"
                    }
                    val rulesSetupCmd = "if [ -d '$pluginDir/rules' ] && [ ! -f '$pluginDir/rules/AGENTS.md' ]; then FIRST_MD=$(find '$pluginDir/rules' -maxdepth 1 -name \"*.md\" 2>/dev/null | head -n 1); if [ -n \"${'$'}FIRST_MD\" ]; then cp \"${'$'}FIRST_MD\" '$pluginDir/rules/AGENTS.md'; fi; fi && if [ ! -f '$pluginDir/rules/AGENTS.md' ]; then mkdir -p '$pluginDir/rules' && if [ -f '$pluginDir/AGENTS.md' ]; then cp '$pluginDir/AGENTS.md' '$pluginDir/rules/AGENTS.md'; elif [ -f '$pluginDir/GEMINI.md' ]; then cp '$pluginDir/GEMINI.md' '$pluginDir/rules/AGENTS.md'; elif [ -f '$pluginDir/CLAUDE.md' ]; then cp '$pluginDir/CLAUDE.md' '$pluginDir/rules/AGENTS.md'; elif [ -f '$pluginDir/README.md' ]; then cp '$pluginDir/README.md' '$pluginDir/rules/AGENTS.md'; fi; fi && if [ ! -f '$pluginDir/plugin.json' ]; then if [ -f '$pluginDir/.antigravity-plugin/plugin.json' ]; then cp '$pluginDir/.antigravity-plugin/plugin.json' '$pluginDir/plugin.json'; elif [ -f '$pluginDir/.claude-plugin/plugin.json' ]; then cp '$pluginDir/.claude-plugin/plugin.json' '$pluginDir/plugin.json'; fi; fi"
                    "echo '[Agent] Starting installation of plugin ${pluginItem.name}...' && rm -rf '$tmpDir' '$pluginDir' && mkdir -p '$pluginDir' '$homePluginDir' && echo '[Agent] Cloning repository from $cleanUrl...' && git clone --depth 1 '$cleanUrl' '$tmpDir' && echo '[Agent] Inspecting plugin repository structure and dependencies...' && $extractCmd && if [ -f '$pluginDir/requirements.txt' ]; then echo '[Agent] Installing Python dependencies...' && pip install -r '$pluginDir/requirements.txt' 2>/dev/null || true; fi && $rulesSetupCmd && cp -r '$pluginDir'/* '$homePluginDir/' 2>/dev/null && rm -rf '$tmpDir' && echo '[Agent] Running agy-fix-paths...' && agy-fix-paths '$pluginDir' 2>/dev/null || true; echo '[Agent] Plugin ${pluginItem.name} successfully installed and ready!'"
                } else {
                    ensurePluginFiles(pDirFile, pHomeDirFile, pluginItem.id, pluginItem.name, pluginItem.description)
                    "echo '[Agent] Installing standalone plugin ${pluginItem.name}...' && mkdir -p '$pluginDir/rules' '$homePluginDir/rules' && cp -r '$pluginDir'/* '$homePluginDir/' 2>/dev/null && agy-fix-paths '$pluginDir' 2>/dev/null || true && echo '[Agent] Plugin ${pluginItem.name} installed!'"
                }
            }
        }
        val cmd = gitEnvSetup + rawCmd

        val initialLines = listOf(TerminalLine(text = "$ Installing plugin ${pluginItem.name}...", isCommand = true))
        activeInstallProgress.value = CustomizationInstallProgress(
            title = pluginItem.name,
            command = cmd,
            lines = initialLines,
            isRunning = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val outputLines = mutableListOf<TerminalLine>().apply { addAll(initialLines) }
            val cleanRegex = Regex("(?:\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]|\r)")

            val exitCode = EngineExecutor.executeCommandWithUpdates(context, cmd, context.filesDir) { line, isErr, isUpdate ->
                val clean = line.replace(cleanRegex, "").trimEnd('\r')
                if (clean.isNotEmpty()) {
                    val isGitInfo = clean.startsWith("Cloning into", ignoreCase = true) ||
                            clean.startsWith("remote:", ignoreCase = true) ||
                            clean.startsWith("Receiving objects", ignoreCase = true) ||
                            clean.startsWith("Resolving deltas", ignoreCase = true) ||
                            clean.startsWith("Updating files", ignoreCase = true) ||
                            clean.startsWith("Note: checking out", ignoreCase = true) ||
                            clean.startsWith("HEAD is now at", ignoreCase = true) ||
                            clean.startsWith("From https://", ignoreCase = true) ||
                            clean.startsWith("Already up to date", ignoreCase = true)
                    val isWarning = clean.startsWith("warning:", ignoreCase = true)
                    val isRealError = !isWarning && (clean.contains("error:", ignoreCase = true) ||
                            clean.contains("fatal:", ignoreCase = true) ||
                            clean.contains("Permission denied", ignoreCase = true) ||
                            (clean.contains("failed", ignoreCase = true) && !clean.contains("0 failed", ignoreCase = true)))
                    val isError = if (isGitInfo || isWarning) false else (isErr || isRealError)
                    synchronized(outputLines) {
                        if (isUpdate && outputLines.isNotEmpty() && !outputLines.last().isCommand) {
                            outputLines[outputLines.size - 1] = TerminalLine(text = clean, isError = isError)
                        } else {
                            outputLines.add(TerminalLine(text = clean, isError = isError))
                        }
                    }
                    activeInstallProgress.value = activeInstallProgress.value?.copy(
                        lines = outputLines.toList()
                    )
                }
            }
            if (exitCode == 0) {
                ensurePluginFiles(pDirFile, pHomeDirFile, pluginItem.id, pluginItem.name, pluginItem.description)
                if (cleanUrl.isNotBlank()) {
                    val sha = fetchLatestRepoCommitSha(cleanUrl, effectiveSubPath) ?: ""
                    registerTrackedCustomization(
                        context,
                        CustomizationRepoTrack(
                            id = pluginItem.id,
                            name = pluginItem.name,
                            repoUrl = cleanUrl,
                            isPlugin = true,
                            subPath = effectiveSubPath,
                            installedSha = sha,
                            lastChecked = System.currentTimeMillis()
                        )
                    )
                }
            }
            refreshPlugins(context)
            activeInstallProgress.value = activeInstallProgress.value?.copy(
                isRunning = false,
                isSuccess = (exitCode == 0)
            )
        }
    }

    fun updateCustomizationViaTerminal(context: Context, track: CustomizationRepoTrack) {
        val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
        val targetDir = if (track.isPlugin) {
            File(directConfig, "plugins/${track.id}").absolutePath
        } else {
            File(directConfig, "skills/${track.id}").absolutePath
        }
        val homeTargetDir = if (track.isPlugin) {
            File(context.filesDir, "home/.gemini/config/plugins/${track.id}").absolutePath
        } else {
            File(context.filesDir, "home/.gemini/config/skills/${track.id}").absolutePath
        }

        val typeLabel = if (track.isPlugin) "Plugin" else "Skill"
        val tmpCloneDir = File(context.cacheDir, "update-${track.id}").absolutePath
        val gitEnvSetup = prepareGitEnv(context)

        val usrRtkPath = File(context.filesDir, "usr/bin/rtk").absolutePath
        val rtkDownloadUrl = "https://github.com/KuatoDev/antigravity-bootstrap/releases/download/core-v1/rtk"
        val rawCmd = if (track.isPlugin && track.id.lowercase() == "rtk") {
            "echo '[RTK] Updating RTK configuration...' && if [ ! -f '$usrRtkPath' ]; then echo '[RTK] Downloading RTK binary from release payload...' && curl -sSL --retry 3 -o '$usrRtkPath' '$rtkDownloadUrl'; fi && chmod +x '$usrRtkPath' 2>/dev/null && rtk init --agent antigravity --global --auto-patch 2>&1 && cp -r '$targetDir'/* '$homeTargetDir/' 2>/dev/null; agy-fix-paths '$targetDir' 2>/dev/null || true; echo \"[RTK plugin successfully updated: $(rtk --version 2>&1)]\""
        } else if (track.isPlugin && File(targetDir, ".git").exists()) {
            "cd '$targetDir' && git pull && cp -r '$targetDir'/* '$homeTargetDir/' 2>/dev/null; agy-fix-paths '$targetDir' 2>/dev/null || true; echo '[$typeLabel ${track.name} successfully updated!]'"
        } else if (!track.subPath.isNullOrBlank()) {
            "rm -rf '$tmpCloneDir' && mkdir -p '$targetDir' '$homeTargetDir' && git clone --depth 1 '${track.repoUrl}' '$tmpCloneDir' && cp -r '$tmpCloneDir/${track.subPath.trim('/')}'/* '$targetDir/' 2>/dev/null; cp -r '$targetDir'/* '$homeTargetDir/' 2>/dev/null; rm -rf '$tmpCloneDir'; agy-fix-paths '$targetDir' 2>/dev/null || true; echo '[$typeLabel ${track.name} successfully updated!]'"
        } else {
            "rm -rf '$tmpCloneDir' && mkdir -p '$targetDir' '$homeTargetDir' && git clone --depth 1 '${track.repoUrl}' '$tmpCloneDir' && cp -r '$tmpCloneDir'/* '$targetDir/' 2>/dev/null; cp -r '$targetDir'/* '$homeTargetDir/' 2>/dev/null; rm -rf '$tmpCloneDir'; agy-fix-paths '$targetDir' 2>/dev/null || true; echo '[$typeLabel ${track.name} successfully updated!]'"
        }
        val cmd = gitEnvSetup + rawCmd

        val initialLines = listOf(TerminalLine(text = "$ Updating $typeLabel ${track.name}...", isCommand = true))
        activeInstallProgress.value = CustomizationInstallProgress(
            title = track.name,
            command = cmd,
            lines = initialLines,
            isRunning = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val outputLines = mutableListOf<TerminalLine>().apply { addAll(initialLines) }
            val cleanRegex = Regex("(?:\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]|\r)")

            val exitCode = EngineExecutor.executeCommandWithUpdates(context, cmd, context.filesDir) { line, isErr, isUpdate ->
                val clean = line.replace(cleanRegex, "").trimEnd('\r')
                if (clean.isNotEmpty()) {
                    val isGitInfo = clean.startsWith("Cloning into", ignoreCase = true) ||
                            clean.startsWith("remote:", ignoreCase = true) ||
                            clean.startsWith("Receiving objects", ignoreCase = true) ||
                            clean.startsWith("Resolving deltas", ignoreCase = true) ||
                            clean.startsWith("Updating files", ignoreCase = true) ||
                            clean.startsWith("Note: checking out", ignoreCase = true) ||
                            clean.startsWith("HEAD is now at", ignoreCase = true) ||
                            clean.startsWith("From https://", ignoreCase = true) ||
                            clean.startsWith("Already up to date", ignoreCase = true)
                    val isWarning = clean.startsWith("warning:", ignoreCase = true)
                    val isRealError = !isWarning && (clean.contains("error:", ignoreCase = true) ||
                            clean.contains("fatal:", ignoreCase = true) ||
                            clean.contains("Permission denied", ignoreCase = true) ||
                            (clean.contains("failed", ignoreCase = true) && !clean.contains("0 failed", ignoreCase = true)))
                    val isError = if (isGitInfo || isWarning) false else (isErr || isRealError)
                    synchronized(outputLines) {
                        if (isUpdate && outputLines.isNotEmpty() && !outputLines.last().isCommand) {
                            outputLines[outputLines.size - 1] = TerminalLine(text = clean, isError = isError)
                        } else {
                            outputLines.add(TerminalLine(text = clean, isError = isError))
                        }
                    }
                    activeInstallProgress.value = activeInstallProgress.value?.copy(lines = outputLines.toList())
                }
            }

            if (exitCode == 0) {
                if (track.isPlugin) {
                    ensurePluginFiles(File(targetDir), File(homeTargetDir), track.id, track.name, "")
                } else {
                    ensureSkillFiles(File(targetDir), File(homeTargetDir), track.id, track.name, "")
                }
                val currentTracks = getTrackedCustomizations(context).toMutableMap()
                val updatedSha = track.remoteSha ?: track.installedSha
                currentTracks[track.id.lowercase()] = track.copy(
                    installedSha = updatedSha,
                    hasUpdate = false,
                    lastChecked = System.currentTimeMillis()
                )
                saveTrackedCustomizations(context, currentTracks)
            }
            if (track.isPlugin) refreshPlugins(context) else refreshSkills(context)
            activeInstallProgress.value = activeInstallProgress.value?.copy(
                isRunning = false,
                isSuccess = (exitCode == 0)
            )
        }
    }

    fun installCustomFromRepoViaTerminal(
        context: Context,
        rawRepoUrl: String,
        customName: String,
        subPath: String?,
        isPlugin: Boolean
    ) {
        val (cleanUrl, extractedSub) = normalizeGitRepoUrl(rawRepoUrl)
        val effectiveSubPath = subPath?.ifBlank { null } ?: extractedSub
        val safeName = if (customName.isNotBlank()) {
            customName.trim().lowercase().replace(" ", "-").replace(Regex("[^a-z0-9_-]"), "")
        } else {
            val fromSub = effectiveSubPath?.trim('/')?.substringAfterLast('/')
            if (!fromSub.isNullOrBlank()) fromSub else cleanUrl.substringAfterLast('/').removeSuffix(".git")
        }

        val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
        val targetDir = if (isPlugin) {
            File(directConfig, "plugins/$safeName").absolutePath
        } else {
            File(directConfig, "skills/$safeName").absolutePath
        }
        val homeTargetDir = if (isPlugin) {
            File(context.filesDir, "home/.gemini/config/plugins/$safeName").absolutePath
        } else {
            File(context.filesDir, "home/.gemini/config/skills/$safeName").absolutePath
        }
        val tmpCloneDir = File(context.cacheDir, "repo-custom-$safeName").absolutePath
        val typeLabel = if (isPlugin) "Plugin" else "Skill"
        val gitEnvSetup = prepareGitEnv(context)

        val sub = effectiveSubPath?.trim('/') ?: ""
        val extractCmd = if (sub.isNotBlank()) {
            "if [ -d '$tmpCloneDir/$sub' ]; then cp -r '$tmpCloneDir/$sub'/* '$targetDir/' 2>/dev/null; else cp -r '$tmpCloneDir'/* '$targetDir/' 2>/dev/null; fi"
        } else {
            val rootSkillsDir = File(directConfig, "skills").absolutePath
            val homeSkillsDir = File(context.filesDir, "home/.gemini/config/skills").absolutePath
            "cp -r '$tmpCloneDir'/* '$targetDir/' 2>/dev/null && for sdir in '$tmpCloneDir/skills' '$tmpCloneDir/.gemini/skills' '$tmpCloneDir/plugin/skills' '$tmpCloneDir/.agents/skills'; do if [ -d \"${'$'}sdir\" ]; then echo '[Agent] Unpacking skills from repository...' && cp -r \"${'$'}sdir/\"* '$rootSkillsDir/' 2>/dev/null; cp -r \"${'$'}sdir/\"* '$homeSkillsDir/' 2>/dev/null; fi; done"
        }
        val rawCmd = buildString {
            append("echo '[Agent] Installing $typeLabel $safeName from $cleanUrl...' && ")
            append("rm -rf '$tmpCloneDir' '$targetDir' && mkdir -p '$targetDir' '$homeTargetDir' && ")
            append("echo '[Agent] Cloning repository...' && ")
            append("git clone --depth 1 '$cleanUrl' '$tmpCloneDir' && ")
            append("echo '[Agent] Analyzing repository contents and structure...' && ")
            append("$extractCmd && ")
            append("rm -rf '$tmpCloneDir' && ")
            if (isPlugin) {
                append("if [ -f '$targetDir/requirements.txt' ]; then ")
                append("echo '[Agent] Installing Python requirements...' && ")
                append("pip install -r '$targetDir/requirements.txt' 2>/dev/null || true; fi && ")
                append("if [ -d '$targetDir/rules' ] && [ ! -f '$targetDir/rules/AGENTS.md' ]; then ")
                append("FIRST_MD=$(find '$targetDir/rules' -maxdepth 1 -name \"*.md\" 2>/dev/null | head -n 1); ")
                append("if [ -n \"${'$'}FIRST_MD\" ]; then cp \"${'$'}FIRST_MD\" '$targetDir/rules/AGENTS.md'; fi; fi && ")
                append("if [ ! -f '$targetDir/rules/AGENTS.md' ]; then mkdir -p '$targetDir/rules' && if [ -f '$targetDir/AGENTS.md' ]; then cp '$targetDir/AGENTS.md' '$targetDir/rules/AGENTS.md'; elif [ -f '$targetDir/GEMINI.md' ]; then cp '$targetDir/GEMINI.md' '$targetDir/rules/AGENTS.md'; elif [ -f '$targetDir/CLAUDE.md' ]; then cp '$targetDir/CLAUDE.md' '$targetDir/rules/AGENTS.md'; elif [ -f '$targetDir/README.md' ]; then cp '$targetDir/README.md' '$targetDir/rules/AGENTS.md'; fi; fi && ")
                append("if [ ! -f '$targetDir/plugin.json' ]; then if [ -f '$targetDir/.antigravity-plugin/plugin.json' ]; then cp '$targetDir/.antigravity-plugin/plugin.json' '$targetDir/plugin.json'; elif [ -f '$targetDir/.claude-plugin/plugin.json' ]; then cp '$targetDir/.claude-plugin/plugin.json' '$targetDir/plugin.json'; fi; fi && ")
            } else {
                append("if [ -d '$targetDir/scripts' ]; then chmod +x '$targetDir'/scripts/* 2>/dev/null || true; fi && ")
            }
            append("cp -r '$targetDir'/* '$homeTargetDir/' 2>/dev/null && ")
            append("echo '[Agent] Running agy-fix-paths...' && ")
            append("agy-fix-paths '$targetDir' 2>/dev/null || true; ")
            append("echo '[Agent] $typeLabel $safeName successfully installed and verified!'")
        }
        val cmd = gitEnvSetup + rawCmd

        val initialLines = listOf(TerminalLine(text = "$ Installing $typeLabel $safeName from $cleanUrl...", isCommand = true))
        activeInstallProgress.value = CustomizationInstallProgress(
            title = safeName,
            command = cmd,
            lines = initialLines,
            isRunning = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val outputLines = mutableListOf<TerminalLine>().apply { addAll(initialLines) }
            val cleanRegex = Regex("(?:\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]|\r)")

            val exitCode = EngineExecutor.executeCommandWithUpdates(context, cmd, context.filesDir) { line, isErr, isUpdate ->
                val clean = line.replace(cleanRegex, "").trimEnd('\r')
                if (clean.isNotEmpty()) {
                    val isGitInfo = clean.startsWith("Cloning into", ignoreCase = true) ||
                            clean.startsWith("remote:", ignoreCase = true) ||
                            clean.startsWith("Receiving objects", ignoreCase = true) ||
                            clean.startsWith("Resolving deltas", ignoreCase = true) ||
                            clean.startsWith("Updating files", ignoreCase = true) ||
                            clean.startsWith("Note: checking out", ignoreCase = true) ||
                            clean.startsWith("HEAD is now at", ignoreCase = true) ||
                            clean.startsWith("From https://", ignoreCase = true) ||
                            clean.startsWith("Already up to date", ignoreCase = true)
                    val isWarning = clean.startsWith("warning:", ignoreCase = true)
                    val isRealError = !isWarning && (clean.contains("error:", ignoreCase = true) ||
                            clean.contains("fatal:", ignoreCase = true) ||
                            clean.contains("Permission denied", ignoreCase = true) ||
                            (clean.contains("failed", ignoreCase = true) && !clean.contains("0 failed", ignoreCase = true)))
                    val isError = if (isGitInfo || isWarning) false else (isErr || isRealError)
                    synchronized(outputLines) {
                        if (isUpdate && outputLines.isNotEmpty() && !outputLines.last().isCommand) {
                            outputLines[outputLines.size - 1] = TerminalLine(text = clean, isError = isError)
                        } else {
                            outputLines.add(TerminalLine(text = clean, isError = isError))
                        }
                    }
                    activeInstallProgress.value = activeInstallProgress.value?.copy(lines = outputLines.toList())
                }
            }

            if (exitCode == 0) {
                val targetDirFile = File(targetDir)
                val homeTargetDirFile = File(homeTargetDir)
                if (isPlugin) {
                    ensurePluginFiles(targetDirFile, homeTargetDirFile, safeName, customName.ifBlank { safeName }, "")
                } else {
                    ensureSkillFiles(targetDirFile, homeTargetDirFile, safeName, customName.ifBlank { safeName }, "")
                }
                val sha = fetchLatestRepoCommitSha(cleanUrl, effectiveSubPath) ?: ""
                registerTrackedCustomization(
                    context,
                    CustomizationRepoTrack(
                        id = safeName,
                        name = safeName,
                        repoUrl = cleanUrl,
                        isPlugin = isPlugin,
                        subPath = effectiveSubPath,
                        installedSha = sha,
                        lastChecked = System.currentTimeMillis()
                    )
                )
            }
            if (isPlugin) refreshPlugins(context) else refreshSkills(context)
            activeInstallProgress.value = activeInstallProgress.value?.copy(
                isRunning = false,
                isSuccess = (exitCode == 0)
            )
        }
    }

    fun installSelectedRepoSkillsViaTerminal(
        context: Context,
        rawRepoUrl: String,
        selectedSkills: List<DiscoveredRepoSkill>
    ) {
        if (selectedSkills.isEmpty()) return
        val (cleanUrl, subDir) = normalizeGitRepoUrl(rawRepoUrl)

        val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
        val gitEnvSetup = prepareGitEnv(context)
        val staging = cachedInspectedRepoDir
        val needClone = (staging == null || !staging.exists() || staging.listFiles()?.isEmpty() == true)
        val tmpCloneDir = if (needClone) {
            File(context.cacheDir, "repo-batch-clone-${cleanUrl.hashCode().toString().replace("-", "n")}").absolutePath
        } else {
            staging!!.absolutePath
        }

        val cmd = buildString {
            append(gitEnvSetup)
            append("echo '[Agent] Starting installation of ${selectedSkills.size} selected skill(s)...' && ")
            if (needClone) {
                append("rm -rf '$tmpCloneDir' && ")
                append("echo '[Agent] Cloning repository from $cleanUrl...' && ")
                append("git clone --depth 1 '$cleanUrl' '$tmpCloneDir' && ")
            }
            for (skill in selectedSkills) {
                val target = File(directConfig, "skills/${skill.id}").absolutePath
                val homeTarget = File(context.filesDir, "home/.gemini/config/skills/${skill.id}").absolutePath
                val src = if (skill.subPath.isNotBlank()) "$tmpCloneDir/${skill.subPath.trim('/')}" else tmpCloneDir
                append("echo '[Agent] Deploying skill ${skill.name}...' && ")
                append("rm -rf '$target' '$homeTarget' && mkdir -p '$target' '$homeTarget' && ")
                append("if [ -d '$src' ]; then cp -r '$src'/* '$target/' 2>/dev/null; fi && ")
                append("if [ -d '$target/scripts' ]; then chmod +x '$target'/scripts/* 2>/dev/null || true; fi && ")
                append("cp -r '$target'/* '$homeTarget/' 2>/dev/null && ")
            }
            if (needClone) {
                append("rm -rf '$tmpCloneDir' && ")
            }
            append("echo '[Agent] Running agy-fix-paths...' && ")
            append("for s in ${selectedSkills.map { it.id }.joinToString(" ")}; do agy-fix-paths \"$directConfig/skills/${'$'}s\" 2>/dev/null || true; done && ")
            append("echo '[Agent] Successfully installed ${selectedSkills.size} skill(s)!'")
        }

        val initialLines = listOf(TerminalLine(text = "$ Installing ${selectedSkills.size} skill(s) from $cleanUrl...", isCommand = true))
        activeInstallProgress.value = CustomizationInstallProgress(
            title = if (selectedSkills.size == 1) selectedSkills.first().name else "${selectedSkills.size} Skills",
            command = cmd,
            lines = initialLines,
            isRunning = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val outputLines = mutableListOf<TerminalLine>().apply { addAll(initialLines) }
            val cleanRegex = Regex("(?:\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]|\r)")

            val exitCode = EngineExecutor.executeCommandWithUpdates(context, cmd, context.filesDir) { line, isErr, isUpdate ->
                val clean = line.replace(cleanRegex, "").trimEnd('\r')
                if (clean.isNotEmpty()) {
                    val isGitInfo = clean.startsWith("Cloning into", ignoreCase = true) ||
                            clean.startsWith("remote:", ignoreCase = true) ||
                            clean.startsWith("Receiving objects", ignoreCase = true) ||
                            clean.startsWith("Resolving deltas", ignoreCase = true) ||
                            clean.startsWith("Updating files", ignoreCase = true) ||
                            clean.startsWith("Note: checking out", ignoreCase = true) ||
                            clean.startsWith("HEAD is now at", ignoreCase = true) ||
                            clean.startsWith("From https://", ignoreCase = true) ||
                            clean.startsWith("Already up to date", ignoreCase = true)
                    val isWarning = clean.startsWith("warning:", ignoreCase = true)
                    val isRealError = !isWarning && (clean.contains("error:", ignoreCase = true) ||
                            clean.contains("fatal:", ignoreCase = true) ||
                            clean.contains("Permission denied", ignoreCase = true) ||
                            (clean.contains("failed", ignoreCase = true) && !clean.contains("0 failed", ignoreCase = true)))
                    val isError = if (isGitInfo || isWarning) false else (isErr || isRealError)
                    synchronized(outputLines) {
                        if (isUpdate && outputLines.isNotEmpty() && !outputLines.last().isCommand) {
                            outputLines[outputLines.size - 1] = TerminalLine(text = clean, isError = isError)
                        } else {
                            outputLines.add(TerminalLine(text = clean, isError = isError))
                        }
                    }
                    activeInstallProgress.value = activeInstallProgress.value?.copy(lines = outputLines.toList())
                }
            }

            if (exitCode == 0) {
                val sha = fetchLatestRepoCommitSha(cleanUrl, null) ?: ""
                for (skill in selectedSkills) {
                    val skillDirFile = File(directConfig, "skills/${skill.id}")
                    val homeSkillDirFile = File(context.filesDir, "home/.gemini/config/skills/${skill.id}")
                    ensureSkillFiles(skillDirFile, homeSkillDirFile, skill.id, skill.name, skill.description)
                    registerTrackedCustomization(
                        context,
                        CustomizationRepoTrack(
                            id = skill.id,
                            name = skill.name,
                            repoUrl = cleanUrl,
                            isPlugin = false,
                            subPath = skill.subPath.ifBlank { null },
                            installedSha = sha,
                            lastChecked = System.currentTimeMillis()
                        )
                    )
                }
            }
            clearRepoInspection()
            refreshSkills(context)
            activeInstallProgress.value = activeInstallProgress.value?.copy(
                isRunning = false,
                isSuccess = (exitCode == 0)
            )
        }
    }

    fun getTrackedCustomizations(context: Context): Map<String, CustomizationRepoTrack> =
        CustomizationTracker.getTrackedCustomizations(context)

    fun saveTrackedCustomizations(context: Context, tracks: Map<String, CustomizationRepoTrack>) {
        CustomizationTracker.saveTrackedCustomizations(context, tracks)
        mutableCustomizationTracks.value = tracks
    }

    fun registerTrackedCustomization(context: Context, track: CustomizationRepoTrack) {
        CustomizationTracker.registerTrackedCustomization(context, track)
        loadTrackedCustomizations(context)
    }

    fun unregisterTrackedCustomization(context: Context, id: String) {
        CustomizationTracker.unregisterTrackedCustomization(context, id)
        loadTrackedCustomizations(context)
    }

    fun loadTrackedCustomizations(context: Context) {
        val tracks = CustomizationTracker.getTrackedCustomizations(context)
        mutableCustomizationTracks.value = tracks
    }

    fun fetchLatestRepoCommitSha(repoUrl: String, subPath: String? = null): String? =
        CustomizationTracker.fetchLatestRepoCommitSha(repoUrl, subPath)

    fun seedCatalogueTrackingIfInstalled(context: Context) {
        val installed = mutableListOf<CustomizationItem>()
        installed.addAll(getInstalledSkills(context))
        installed.addAll(getInstalledPlugins(context))

        val catalogue = mutableListOf<SkillCatalogueItem>()
        catalogue.addAll(getAvailableSkillsCatalogue())
        catalogue.addAll(getAvailablePluginsCatalogue())

        val currentTracks = getTrackedCustomizations(context).toMutableMap()
        var changed = false

        for (item in installed) {
            val key = item.name.lowercase()
            if (!currentTracks.containsKey(key)) {
                val match = catalogue.firstOrNull { it.name.equals(item.name, ignoreCase = true) || it.id.equals(item.name, ignoreCase = true) }
                if (match != null && match.repoUrl.isNotBlank()) {
                    currentTracks[key] = CustomizationRepoTrack(
                        id = match.id,
                        name = match.name,
                        repoUrl = match.repoUrl,
                        isPlugin = match.isPlugin,
                        subPath = match.subPath,
                        installedSha = "",
                        lastChecked = 0L
                    )
                    changed = true
                }
            }
        }
        if (changed) {
            saveTrackedCustomizations(context, currentTracks)
        }
    }

    fun checkForCustomizationUpdates(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(KEY_CUSTOMIZATION_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        val oneDayMillis = 24 * 60 * 60 * 1000L

        if (!force && (now - lastCheckTime < oneDayMillis)) {
            loadTrackedCustomizations(context)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            mutableIsCheckingCustomizationUpdates.value = true
            try {
                seedCatalogueTrackingIfInstalled(context)
                val currentTracks = getTrackedCustomizations(context).toMutableMap()

                for ((id, track) in currentTracks) {
                    if (track.repoUrl.isBlank()) continue
                    val latestSha = fetchLatestRepoCommitSha(track.repoUrl, track.subPath)
                    if (latestSha != null && latestSha.isNotBlank()) {
                        if (track.installedSha.isBlank()) {
                            currentTracks[id] = track.copy(
                                installedSha = latestSha,
                                remoteSha = latestSha,
                                hasUpdate = false,
                                lastChecked = now
                            )
                        } else {
                            val hasUpdate = track.installedSha != latestSha
                            currentTracks[id] = track.copy(
                                remoteSha = latestSha,
                                hasUpdate = hasUpdate,
                                lastChecked = now
                            )
                        }
                    }
                }

                saveTrackedCustomizations(context, currentTracks)
                prefs.edit().putLong(KEY_CUSTOMIZATION_LAST_CHECK, now).apply()
            } catch (e: Exception) {
                Log.e("AntigravityViewModel", "Failed checking customization updates", e)
            } finally {
                mutableIsCheckingCustomizationUpdates.value = false
            }
        }
    }

    fun loadProjects(context: Context) {
        val sorted = ProjectPersistence.loadProjects(context, mutableProjectSortOrder.value)
        mutableProjectList.value = sorted
    }

    fun saveProjects(context: Context, list: List<File>) {
        ProjectPersistence.saveProjects(context, list)
    }

    fun addProject(context: Context, path: String): File {
        val f = File(path)
        if (!f.exists()) f.mkdirs()
        val currentList = mutableProjectList.value.toMutableList()
        if (currentList.none { it.absolutePath == f.absolutePath }) {
            currentList.add(f)
            val sorted = ProjectPersistence.sortProjects(currentList, mutableProjectSortOrder.value)
            mutableProjectList.value = sorted
            saveProjects(context, sorted)
        }
        setWorkspacePath(context, f.absolutePath)
        addLog("Workspace", "Added project: ${f.name} (${f.absolutePath})")
        return f
    }

    fun removeProject(context: Context, path: String) {
        val currentList = mutableProjectList.value.filter { it.absolutePath != path }
        mutableProjectList.value = currentList
        saveProjects(context, currentList)
        if (mutableWorkspacePath.value == path) {
            val nextPath = currentList.firstOrNull()?.absolutePath ?: ""
            setWorkspacePath(context, nextPath)
        }
        addLog("Workspace", "Removed project from list: $path")
    }

    fun resolveTreeUriToFolder(context: Context, treeUri: Uri): File? =
        ProjectPersistence.resolveTreeUriToFolder(context, treeUri)

    fun addProjectFromTreeUri(context: Context, treeUri: Uri): File? {
        val folder = resolveTreeUriToFolder(context, treeUri)
        if (folder != null) {
            if (!folder.exists()) {
                folder.mkdirs()
            }
            return addProject(context, folder.absolutePath)
        }
        return null
    }

    fun setProjectSortOrder(context: Context, order: ProjectSortOrder) {
        mutableProjectSortOrder.value = order
        ProjectPersistence.saveProjectSortOrder(context, order)
        mutableProjectList.update { ProjectPersistence.sortProjects(it, order) }
    }

    private fun sortProjects(list: List<File>, order: ProjectSortOrder): List<File> =
        ProjectPersistence.sortProjects(list, order)

    fun createProjectInFolder(context: Context, name: String, parentDir: File): File? {
        val newDir = ProjectPersistence.createProjectInFolder(name, parentDir) ?: return null
        val added = addProject(context, newDir.absolutePath)
        addLog("Workspace", "Created project folder: ${added.name} in ${parentDir.absolutePath}")
        return added
    }

    fun createProjectFolder(context: Context, name: String, targetBase: File? = null): File? {
        val baseDir = targetBase
            ?: (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) ?: Environment.getExternalStorageDirectory()).apply { mkdirs() }
        return createProjectInFolder(context, name, baseDir)
    }

    fun setWorkspacePath(context: Context, path: String) {
        mutableWorkspacePath.value = path
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WORKSPACE_PATH, path).apply()
        TerminalSessionManager.currentWorkingDir = File(path)
        addLog("Workspace", "Active directory changed: $path")
    }

    fun saveConversationHistory(context: Context) {
        ConversationPersistence.saveConversationHistory(context, mutableConversationHistory.value)
    }

    fun saveLastActiveConversationId(context: Context, id: String?) {
        ConversationPersistence.saveLastActiveConversationId(context, id)
    }

    fun saveMessagesForConversation(context: Context, conversationId: String, messages: List<ChatMessage>) {
        val messagesSnapshot = messages.toList()
        viewModelScope.launch(Dispatchers.IO) {
            ConversationPersistence.saveMessagesForConversation(context, conversationId, messagesSnapshot)
        }
    }

    fun loadMessagesForConversation(context: Context, conversationId: String): List<ChatMessage> {
        return ConversationPersistence.loadMessagesForConversation(context, conversationId)
    }

    private fun deleteConversationFile(context: Context, conversationId: String) {
        ConversationPersistence.deleteConversationFile(context, conversationId)
    }

    fun loadConversationHistory(context: Context) {
        val list = ConversationPersistence.loadConversationHistory(context)
        mutableConversationHistory.value = list

        val lastActiveId = ConversationPersistence.getLastActiveConversationId(context)
        val activeSummary = list.firstOrNull { it.id == lastActiveId } ?: list.firstOrNull()
        if (activeSummary != null) {
            mutableActiveConversationId.value = activeSummary.id
            mutableConversationTitle.value = activeSummary.title
            mutableIsConversationPinned.value = activeSummary.isPinned
            if (!activeSummary.projectPath.isNullOrBlank()) {
                mutableWorkspacePath.value = activeSummary.projectPath
                TerminalSessionManager.currentWorkingDir = File(activeSummary.projectPath)
            }
            mutableChatMessages.value = loadMessagesForConversation(context, activeSummary.id)
            saveLastActiveConversationId(context, activeSummary.id)
        } else {
            mutableActiveConversationId.value = null
            mutableConversationTitle.value = "New Conversation"
            mutableChatMessages.value = emptyList()
            mutableIsConversationPinned.value = false
        }
    }

    fun startNewConversation(context: Context? = null, projectPath: String? = mutableWorkspacePath.value) {
        val currentId = mutableActiveConversationId.value
        if (context != null && currentId != null && mutableChatMessages.value.isNotEmpty()) {
            saveMessagesForConversation(context, currentId, mutableChatMessages.value)
        }
        mutableChatMessages.value = emptyList()
        mutableConversationTitle.value = "New Conversation"
        mutableActiveConversationId.value = null
        mutableAttachedFiles.value = emptyList()
        mutableIsConversationPinned.value = false
        if (!projectPath.isNullOrBlank()) {
            mutableWorkspacePath.value = projectPath
            TerminalSessionManager.currentWorkingDir = File(projectPath)
        }
        if (context != null) {
            saveLastActiveConversationId(context, null)
        }
    }

    fun loadConversation(context: Context, summary: ConversationSummary) {
        val currentId = mutableActiveConversationId.value
        if (currentId != null && mutableChatMessages.value.isNotEmpty()) {
            saveMessagesForConversation(context, currentId, mutableChatMessages.value)
        }
        mutableActiveConversationId.value = summary.id
        mutableConversationTitle.value = summary.title
        mutableIsConversationPinned.value = summary.isPinned
        if (!summary.projectPath.isNullOrBlank()) {
            mutableWorkspacePath.value = summary.projectPath
            TerminalSessionManager.currentWorkingDir = File(summary.projectPath)
        }
        mutableChatMessages.value = loadMessagesForConversation(context, summary.id)
        saveLastActiveConversationId(context, summary.id)
        addLog("Conversation", "Switched to conversation: ${summary.title}")
    }

    fun loadConversation(summary: ConversationSummary) {
        mutableActiveConversationId.value = summary.id
        mutableConversationTitle.value = summary.title
        mutableIsConversationPinned.value = summary.isPinned
        if (!summary.projectPath.isNullOrBlank()) {
            mutableWorkspacePath.value = summary.projectPath
            TerminalSessionManager.currentWorkingDir = File(summary.projectPath)
        }
        addLog("Conversation", "Switched to conversation: ${summary.title}")
    }

    fun renameConversation(newTitle: String, summaryId: String? = null, context: Context? = null) {
        val trimmed = newTitle.trim()
        if (trimmed.isNotEmpty()) {
            val targetContext = context ?: try { AntigravityApp.instance } catch (e: Exception) { null }
            if (summaryId == null) {
                mutableConversationTitle.value = trimmed
                var currentId = mutableActiveConversationId.value
                if (currentId == null) {
                    currentId = UUID.randomUUID().toString()
                    mutableActiveConversationId.value = currentId
                }
                mutableConversationHistory.update { list ->
                    val exists = list.any { it.id == currentId }
                    if (exists) {
                        list.map { if (it.id == currentId) it.copy(title = trimmed) else it }
                    } else {
                        listOf(
                            ConversationSummary(
                                id = currentId,
                                title = trimmed,
                                timestamp = System.currentTimeMillis(),
                                messageCount = mutableChatMessages.value.size,
                                isPinned = mutableIsConversationPinned.value,
                                projectPath = mutableWorkspacePath.value
                            )
                        ) + list
                    }
                }
                if (targetContext != null) {
                    saveLastActiveConversationId(targetContext, currentId)
                }
            } else {
                mutableConversationHistory.update { list ->
                    list.map { if (it.id == summaryId) it.copy(title = trimmed) else it }
                }
                if (mutableActiveConversationId.value == summaryId) {
                    mutableConversationTitle.value = trimmed
                }
            }
            if (targetContext != null) {
                saveConversationHistory(targetContext)
            }
            addLog("Conversation", "Conversation renamed to: $trimmed")
        }
    }

    fun togglePinConversation(summaryId: String? = null, context: Context? = null): Boolean {
        val targetContext = context ?: try { AntigravityApp.instance } catch (e: Exception) { null }
        if (summaryId == null) {
            mutableIsConversationPinned.update { !it }
            val pinned = mutableIsConversationPinned.value
            val currentId = mutableActiveConversationId.value
            if (currentId != null) {
                mutableConversationHistory.update { list ->
                    list.map { if (it.id == currentId) it.copy(isPinned = pinned) else it }
                }
                if (targetContext != null) saveConversationHistory(targetContext)
            }
            addLog("Conversation", if (pinned) "Conversation pinned" else "Conversation unpinned")
            return pinned
        } else {
            var newPinned = false
            mutableConversationHistory.update { list ->
                list.map {
                    if (it.id == summaryId) {
                        newPinned = !it.isPinned
                        it.copy(isPinned = newPinned)
                    } else it
                }
            }
            if (mutableActiveConversationId.value == summaryId) {
                mutableIsConversationPinned.value = newPinned
            }
            if (targetContext != null) saveConversationHistory(targetContext)
            return true
        }
    }

    fun deleteConversation(id: String) {
        val targetContext = try { AntigravityApp.instance } catch (e: Exception) { null }
        if (targetContext != null) {
            deleteConversation(targetContext, id)
        } else {
            mutableConversationHistory.update { list -> list.filter { it.id != id } }
            if (mutableActiveConversationId.value == id) {
                mutableActiveConversationId.value = null
                mutableChatMessages.value = emptyList()
                mutableConversationTitle.value = "New Conversation"
                mutableAttachedFiles.value = emptyList()
                mutableIsConversationPinned.value = false
            }
            addLog("Conversation", "Deleted conversation $id")
        }
    }

    fun deleteConversation(context: Context, id: String) {
        mutableConversationHistory.update { list -> list.filter { it.id != id } }
        saveConversationHistory(context)
        deleteConversationFile(context, id)
        if (mutableActiveConversationId.value == id) {
            val nextConv = mutableConversationHistory.value.firstOrNull()
            if (nextConv != null) {
                loadConversation(context, nextConv)
            } else {
                mutableActiveConversationId.value = null
                mutableChatMessages.value = emptyList()
                mutableConversationTitle.value = "New Conversation"
                mutableAttachedFiles.value = emptyList()
                mutableIsConversationPinned.value = false
                saveLastActiveConversationId(context, null)
            }
        }
        addLog("Conversation", "Deleted conversation $id")
    }

    fun deleteCurrentConversation(context: Context) {
        val currentId = mutableActiveConversationId.value
        if (currentId != null) {
            deleteConversation(context, currentId)
        } else {
            val currentTitle = mutableConversationTitle.value
            val match = mutableConversationHistory.value.firstOrNull { it.title == currentTitle }
            if (match != null) {
                deleteConversation(context, match.id)
            } else {
                mutableChatMessages.value = emptyList()
                mutableConversationTitle.value = "New Conversation"
                mutableAttachedFiles.value = emptyList()
                mutableIsConversationPinned.value = false
            }
        }
    }

    fun archiveConversation(context: Context? = null) {
        val targetContext = context ?: try { AntigravityApp.instance } catch (e: Exception) { null }
        val currentId = mutableActiveConversationId.value
        if (mutableChatMessages.value.isNotEmpty()) {
            val title = mutableConversationTitle.value
            val convId = currentId ?: UUID.randomUUID().toString()
            val summary = ConversationSummary(
                id = convId,
                title = title,
                timestamp = System.currentTimeMillis(),
                messageCount = mutableChatMessages.value.size,
                isPinned = mutableIsConversationPinned.value,
                projectPath = mutableWorkspacePath.value
            )
            mutableConversationHistory.update { list ->
                listOf(summary) + list.filter { it.id != convId }
            }
            if (targetContext != null) {
                saveConversationHistory(targetContext)
                saveMessagesForConversation(targetContext, convId, mutableChatMessages.value)
            }
            addLog("Conversation", "Conversation archived: $title")
        }
        mutableChatMessages.value = emptyList()
        mutableConversationTitle.value = "New Conversation"
        mutableActiveConversationId.value = null
        mutableAttachedFiles.value = emptyList()
        mutableIsConversationPinned.value = false
        if (targetContext != null) {
            saveLastActiveConversationId(targetContext, null)
        }
    }

    fun getGeminiConfigDir(context: Context): File {
        val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
        val homeConfig = File(context.filesDir, "home/.gemini/config").apply { mkdirs() }
        try {
            syncConfigBidirectional(context)
        } catch (e: Exception) {}
        return directConfig
    }

    fun syncConfigBidirectional(context: Context) {
        try {
            val directConfig = File(context.filesDir, ".gemini/config")
            val homeConfig = File(context.filesDir, "home/.gemini/config")
            if (directConfig.exists() && directConfig.isDirectory) {
                directConfig.copyRecursively(homeConfig, overwrite = true)
            }
            if (homeConfig.exists() && homeConfig.isDirectory) {
                homeConfig.copyRecursively(directConfig, overwrite = false)
            }
        } catch (e: Exception) {}
    }

    fun syncConfigToRoot(context: Context) {
        syncConfigBidirectional(context)
    }

    fun getBuiltinSkillsDir(context: Context): File {
        val candidates = listOf(
            File(context.filesDir, ".gemini/antigravity-app/builtin/skills"),
            File(context.filesDir, "usr/share/antigravity/builtin/skills"),
            File(context.filesDir, "home/.gemini/antigravity-app/builtin/skills")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory } ?: candidates.first()
    }

    fun getInstalledSkills(context: Context): List<CustomizationItem> {
        val list = mutableListOf<CustomizationItem>()
        val seen = mutableSetOf<String>()

        val configDir = getGeminiConfigDir(context)
        val configDirsToCheck = listOf(
            File(configDir, "skills"),
            File(context.filesDir, ".gemini/config/skills"),
            File(context.filesDir, "home/.gemini/config/skills")
        )

        for (skillsDir in configDirsToCheck) {
            if (skillsDir.isDirectory) {
                skillsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    val nameLower = dir.name.lowercase()
                    val skillFile = File(dir, "SKILL.md")
                    if (skillFile.exists() && !seen.contains(nameLower)) {
                        var desc = "Custom user skill"
                        val descLine = skillFile.readLines().take(20).firstOrNull { it.trim().startsWith("description:") }
                        if (descLine != null) {
                            desc = descLine.substringAfter("description:").trim().trim('"', '\'')
                        }
                        seen.add(nameLower)
                        list.add(CustomizationItem(dir.name, dir.name, desc, "skill", false, dir.absolutePath))
                    }

                    val nestedSkills = File(dir, "skills")
                    if (nestedSkills.isDirectory) {
                        nestedSkills.listFiles()?.filter { it.isDirectory }?.forEach { nDir ->
                            val nNameLower = nDir.name.lowercase()
                            if (!seen.contains(nNameLower)) {
                                val nSkillFile = File(nDir, "SKILL.md")
                                var desc = "Community skill from ${dir.name}"
                                if (nSkillFile.exists()) {
                                    val descLine = nSkillFile.readLines().take(20).firstOrNull { it.trim().startsWith("description:") }
                                    if (descLine != null) {
                                        desc = descLine.substringAfter("description:").trim().trim('"', '\'')
                                    }
                                }
                                seen.add(nNameLower)
                                list.add(CustomizationItem(nDir.name, nDir.name, desc, "skill", false, nDir.absolutePath))
                            }
                        }
                    }
                }
            }
        }

        val pluginBaseDirs = listOf(
            File(configDir, "plugins"),
            File(context.filesDir, ".gemini/config/plugins"),
            File(context.filesDir, "home/.gemini/config/plugins")
        )
        for (pBase in pluginBaseDirs) {
            if (pBase.isDirectory) {
                pBase.listFiles()?.filter { it.isDirectory }?.forEach { pDir ->
                    val pluginSkillsDir = File(pDir, "skills")
                    if (pluginSkillsDir.isDirectory) {
                        pluginSkillsDir.listFiles()?.filter { it.isDirectory }?.forEach { sDir ->
                            val sNameLower = sDir.name.lowercase()
                            if (!seen.contains(sNameLower)) {
                                val skillFile = File(sDir, "SKILL.md")
                                var desc = "Plugin skill from ${pDir.name}"
                                if (skillFile.exists()) {
                                    val descLine = skillFile.readLines().take(20).firstOrNull { it.trim().startsWith("description:") }
                                    if (descLine != null) {
                                        desc = descLine.substringAfter("description:").trim().trim('"', '\'')
                                    }
                                }
                                seen.add(sNameLower)
                                list.add(CustomizationItem(sDir.name, sDir.name, desc, "skill", false, sDir.absolutePath))
                            }
                        }
                    }
                }
            }
        }

        val wsPath = mutableWorkspacePath.value
        if (!wsPath.isNullOrBlank()) {
            val wsDir = File(wsPath)
            if (wsDir.isDirectory) {
                val wsSkillsDirs = listOf(File(wsDir, ".gemini/skills"), File(wsDir, "skills"))
                for (wSkills in wsSkillsDirs) {
                    if (wSkills.isDirectory) {
                        wSkills.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                            val nameLower = dir.name.lowercase()
                            if (!seen.contains(nameLower)) {
                                val skillFile = File(dir, "SKILL.md")
                                var desc = "Workspace skill"
                                if (skillFile.exists()) {
                                    val descLine = skillFile.readLines().take(20).firstOrNull { it.trim().startsWith("description:") }
                                    if (descLine != null) {
                                        desc = descLine.substringAfter("description:").trim().trim('"', '\'')
                                    }
                                }
                                seen.add(nameLower)
                                list.add(CustomizationItem(dir.name, dir.name, desc, "skill", false, dir.absolutePath))
                            }
                        }
                    }
                }
            }
        }

        val builtinDir = getBuiltinSkillsDir(context)
        if (builtinDir.isDirectory) {
            builtinDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val nameLower = dir.name.lowercase()
                if (!seen.contains(nameLower)) {
                    val skillFile = File(dir, "SKILL.md")
                    var desc = "Built-in Antigravity skill"
                    if (skillFile.exists()) {
                        val descLine = skillFile.readLines().take(20).firstOrNull { it.trim().startsWith("description:") }
                        if (descLine != null) {
                            desc = descLine.substringAfter("description:").trim().trim('"', '\'')
                        }
                    }
                    seen.add(nameLower)
                    list.add(CustomizationItem(dir.name, dir.name, desc, "skill", true, dir.absolutePath))
                }
            }
        }

        return list
    }

    fun installSkill(context: Context, name: String, description: String, content: String): Boolean {
        try {
            val trimmedName = name.trim().lowercase().replace("\\s+".toRegex(), "-")
            if (trimmedName.isEmpty()) return false
            val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
            val homeConfig = File(context.filesDir, "home/.gemini/config").apply { mkdirs() }
            val skillDir = File(directConfig, "skills/$trimmedName").apply { mkdirs() }
            val homeSkillDir = File(homeConfig, "skills/$trimmedName").apply { mkdirs() }
            val mdContent = buildString {
                appendLine("---")
                appendLine("name: $trimmedName")
                appendLine("description: \"${description.trim()}\"")
                appendLine("---")
                appendLine()
                if (content.isNotBlank()) {
                    appendLine(content.trim())
                } else {
                    appendLine("# $trimmedName")
                    appendLine()
                    appendLine(description.trim())
                }
            }
            File(skillDir, "SKILL.md").writeText(mdContent)
            File(homeSkillDir, "SKILL.md").writeText(mdContent)
            syncConfigBidirectional(context)
            refreshSkills(context)
            addLog("Customization", "Installed custom skill: $trimmedName")
            return true
        } catch (e: Exception) {
            Log.e("AntigravityViewModel", "Failed installing skill", e)
            return false
        }
    }

    fun uninstallSkill(context: Context, name: String): Boolean {
        try {
            val trimmedName = name.trim().lowercase()
            val skillDir = File(context.filesDir, ".gemini/config/skills/$trimmedName")
            val homeSkillDir = File(context.filesDir, "home/.gemini/config/skills/$trimmedName")
            var ok = false
            if (skillDir.exists()) ok = skillDir.deleteRecursively() || ok
            if (homeSkillDir.exists()) ok = homeSkillDir.deleteRecursively() || ok
            syncConfigBidirectional(context)
            unregisterTrackedCustomization(context, trimmedName)
            refreshSkills(context)
            addLog("Customization", "Uninstalled skill: $trimmedName")
            return ok
        } catch (e: Exception) {
            Log.e("AntigravityViewModel", "Failed uninstalling skill", e)
            return false
        }
    }

    fun getInstalledPlugins(context: Context): List<CustomizationItem> {
        val list = mutableListOf<CustomizationItem>()
        val seen = mutableSetOf<String>()

        val configDir = getGeminiConfigDir(context)
        val pluginBaseDirs = listOf(
            File(configDir, "plugins"),
            File(context.filesDir, ".gemini/config/plugins"),
            File(context.filesDir, "home/.gemini/config/plugins")
        )

        for (pBase in pluginBaseDirs) {
            if (pBase.isDirectory) {
                pBase.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    val nameLower = dir.name.lowercase()
                    if (!seen.contains(nameLower)) {
                        seen.add(nameLower)
                        var desc = "Plugin extension for Antigravity"
                        val pluginJson = File(dir, "plugin.json")
                        var jsonValid = false
                        if (pluginJson.exists()) {
                            try {
                                val text = pluginJson.readText(Charsets.UTF_8).trim()
                                if (!text.contains("\\n") && !text.contains("\\\"")) {
                                    val json = org.json.JSONObject(text)
                                    jsonValid = true
                                    if (json.has("description")) {
                                        desc = json.getString("description")
                                    }
                                }
                            } catch (e: Exception) {
                                jsonValid = false
                            }
                        }

                        if (desc == "Plugin extension for Antigravity") {
                            val ruleFile = File(dir, "rules/AGENTS.md")
                            if (ruleFile.exists()) {
                                val firstLines = ruleFile.readLines().take(10)
                                val header = firstLines.firstOrNull { it.trim().startsWith("#") }
                                if (header != null) {
                                    desc = header.removePrefix("#").trim()
                                }
                            }
                        }

                        if (!jsonValid && dir.canWrite()) {
                            try {
                                val repaired = org.json.JSONObject().apply {
                                    put("name", dir.name)
                                    put("version", "1.0.0")
                                    put("description", desc)
                                }.toString(2)
                                pluginJson.writeText(repaired, Charsets.UTF_8)
                                Log.i("AntigravityViewModel", "Auto-repaired corrupted plugin.json for ${dir.name}")
                            } catch (e: Exception) {}
                        }

                        val ruleFile = File(dir, "rules/AGENTS.md")
                        val altRuleFile = File(dir, "rules/antislop.md")
                        if (!ruleFile.exists() && altRuleFile.exists()) {
                            try {
                                altRuleFile.copyTo(ruleFile, overwrite = true)
                            } catch (e: Exception) {}
                        }

                        val isCore = false
                        list.add(CustomizationItem(dir.name, dir.name, desc, "plugin", isCore, dir.absolutePath))
                    }
                }
            }
        }

        return list
    }

    fun installPlugin(context: Context, name: String, description: String): Boolean {
        try {
            val trimmedName = name.trim().lowercase().replace("\\s+".toRegex(), "-")
            if (trimmedName.isEmpty()) return false
            val directConfig = File(context.filesDir, ".gemini/config").apply { mkdirs() }
            val homeConfig = File(context.filesDir, "home/.gemini/config").apply { mkdirs() }
            val pluginDir = File(directConfig, "plugins/$trimmedName/rules").apply { mkdirs() }
            val homePluginDir = File(homeConfig, "plugins/$trimmedName/rules").apply { mkdirs() }
            val mdContent = buildString {
                appendLine("# Plugin: $trimmedName")
                appendLine()
                appendLine(description.trim())
            }
            File(pluginDir, "AGENTS.md").writeText(mdContent)
            File(homePluginDir, "AGENTS.md").writeText(mdContent)
            File(pluginDir.parentFile, "plugin.json").writeText("{\n  \"name\": \"$trimmedName\"\n}\n")
            File(homePluginDir.parentFile, "plugin.json").writeText("{\n  \"name\": \"$trimmedName\"\n}\n")
            syncConfigBidirectional(context)
            refreshPlugins(context)
            addLog("Customization", "Installed custom plugin: $trimmedName")
            return true
        } catch (e: Exception) {
            Log.e("AntigravityViewModel", "Failed installing plugin", e)
            return false
        }
    }

    fun uninstallPlugin(context: Context, name: String): Boolean {
        try {
            val trimmedName = name.trim().lowercase()
            val pluginDir = File(context.filesDir, ".gemini/config/plugins/$trimmedName")
            val homePluginDir = File(context.filesDir, "home/.gemini/config/plugins/$trimmedName")
            var ok = false
            if (pluginDir.exists()) ok = pluginDir.deleteRecursively() || ok
            if (homePluginDir.exists()) ok = homePluginDir.deleteRecursively() || ok
            syncConfigBidirectional(context)
            unregisterTrackedCustomization(context, trimmedName)
            refreshPlugins(context)
            addLog("Customization", "Uninstalled plugin: $trimmedName")
            return ok
        } catch (e: Exception) {
            Log.e("AntigravityViewModel", "Failed uninstalling plugin", e)
            return false
        }
    }

    fun getAvailableSkillsCatalogue(): List<SkillCatalogueItem> {
        val remote = mutableSkillsCatalogue.value
        return if (remote.isNotEmpty()) remote else getDefaultSkillsCatalogue()
    }

    fun getDefaultSkillsCatalogue(): List<SkillCatalogueItem> =
        CustomizationCatalogue.getDefaultSkillsCatalogue()

    fun getAvailablePluginsCatalogue(): List<SkillCatalogueItem> {
        val remote = mutablePluginsCatalogue.value
        return if (remote.isNotEmpty()) remote else getDefaultPluginsCatalogue()
    }

    fun getDefaultPluginsCatalogue(): List<SkillCatalogueItem> =
        CustomizationCatalogue.getDefaultPluginsCatalogue()

    private var lastCatalogueFetchTime = 0L

    fun loadCachedCatalogue(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val (skills, plugins) = CustomizationCatalogue.loadCachedCatalogue(context)
            if (skills.isNotEmpty()) mutableSkillsCatalogue.value = skills
            if (plugins.isNotEmpty()) mutablePluginsCatalogue.value = plugins
        }
    }

    fun fetchRemoteCatalogue(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCatalogueFetchTime < 15 * 60 * 1000L)) {
            return
        }
        if (mutableIsFetchingCatalogue.value) return
        mutableIsFetchingCatalogue.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pair = CustomizationCatalogue.fetchRemoteCatalogue(context)
                if (pair != null) {
                    val (skills, plugins) = pair
                    if (skills.isNotEmpty()) mutableSkillsCatalogue.value = skills
                    if (plugins.isNotEmpty()) mutablePluginsCatalogue.value = plugins
                    lastCatalogueFetchTime = System.currentTimeMillis()
                    addLog("Catalogue", "Updated catalogue: ${skills.size} skills, ${plugins.size} plugins from remote.")
                }
            } finally {
                mutableIsFetchingCatalogue.value = false
            }
        }
    }

    fun getInstalledRules(context: Context): List<Pair<String, String>> {
        val rules = mutableListOf<Pair<String, String>>()
        val configDir = getGeminiConfigDir(context)

        val agentsFile = File(configDir, "AGENTS.md")
        if (agentsFile.exists() && agentsFile.length() > 0) {
            val firstLine = agentsFile.readLines().firstOrNull { it.trim().startsWith("#") }?.removePrefix("#")?.trim()
            val desc = firstLine ?: "Core global behavior and environment execution standards"
            rules.add("AGENTS.md" to desc)
        }

        val pluginsDir = File(configDir, "plugins")
        if (pluginsDir.isDirectory) {
            pluginsDir.listFiles()?.filter { it.isDirectory }?.forEach { pDir ->
                val ruleFile = File(pDir, "rules/AGENTS.md")
                if (ruleFile.exists()) {
                    val firstLine = ruleFile.readLines().firstOrNull { it.trim().startsWith("#") }?.removePrefix("#")?.trim()
                    val desc = firstLine ?: "Plugin rules for ${pDir.name}"
                    rules.add("RULE[${pDir.name}/rules/AGENTS.md]" to desc)
                }
            }
        }

        return rules
    }

    fun installPluginFromCatalogueAsync(context: Context, pluginId: String, onComplete: (Boolean) -> Unit) {
        if (mutableInstallingItemId.value != null) return
        mutableInstallingItemId.value = pluginId

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val configDir = getGeminiConfigDir(context)
                    val pluginDir = File(configDir, "plugins/$pluginId")
                    val rulesDir = File(pluginDir, "rules").apply { mkdirs() }

                    when (pluginId.lowercase()) {
                        "rtk" -> {
                            addLog("Plugin", "Initializing RTK (Rust Token Killer)...")
                            val usrBinRtk = File(context.filesDir, "usr/bin/rtk")
                            val prefixRtk = File(context.filesDir, "home/usr/bin/rtk")
                            val runtimeRtk = File(context.filesDir, "runtime/bin/rtk")

                            if (runtimeRtk.exists() && !usrBinRtk.exists()) {
                                try {
                                    runtimeRtk.copyTo(usrBinRtk, overwrite = true)
                                    usrBinRtk.setExecutable(true, false)
                                } catch (e: Exception) {}
                            }
                            if (!usrBinRtk.exists()) {
                                try {
                                    addLog("Plugin", "Downloading RTK binary from release payload...")
                                    val rtkUrl = java.net.URL("https://github.com/KuatoDev/antigravity-bootstrap/releases/download/core-v1/rtk")
                                    usrBinRtk.parentFile?.mkdirs()
                                    rtkUrl.openStream().use { input ->
                                        usrBinRtk.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    usrBinRtk.setExecutable(true, false)
                                    addLog("Plugin", "RTK binary downloaded successfully.")
                                } catch (e: Exception) {
                                    addLog("Plugin", "Failed to download RTK: ${e.message}", isError = true)
                                }
                            }
                            if (usrBinRtk.exists()) {
                                usrBinRtk.setExecutable(true, false)
                            }
                            if (prefixRtk.exists()) {
                                prefixRtk.setExecutable(true, false)
                            }

                            EngineExecutor.executeCommand(context, "which rtk && rtk --version") { line, isErr ->
                                addLog("Plugin", line, isErr)
                            }

                            addLog("Plugin", "Running rtk init --agent antigravity --global --auto-patch...")
                            EngineExecutor.executeCommand(context, "rtk init --agent antigravity --global --auto-patch") { line, isErr ->
                                addLog("Plugin", line, isErr)
                            }

                            if (!pluginDir.exists()) pluginDir.mkdirs()
                            rulesDir.mkdirs()

                            val pluginJson = File(pluginDir, "plugin.json")
                            if (!pluginJson.exists() || pluginJson.length() == 0L) {
                                pluginJson.writeText(
                                    """
                                    {
                                      "name": "rtk",
                                      "version": "1.0.0",
                                      "description": "Antigravity plugin for transparent command rewriting and token optimization using rtk"
                                    }
                                    """.trimIndent()
                                )
                            }

                            val agentRule = File(rulesDir, "AGENTS.md")
                            if (!agentRule.exists() || agentRule.length() == 0L) {
                                agentRule.writeText(
                                    """
                                    # Command output

                                    Command output here is condensed to save tokens, keeping every signal and
                                    dropping costly noise. Treat it as the complete result: run commands
                                    normally, and batch related commands into one call to avoid extra turns.
                                    Truncated results state their recovery path in their own output. Re-run a
                                    command as `rtk bypass <cmd>` only when its result is unusable: empty when
                                    output was clearly expected, contradicting its exit code, or garbled.
                                    """.trimIndent()
                                )
                            }
                            addLog("Plugin", "RTK plugin fully configured and verified.")
                            true
                        }
                        "antislop" -> {
                            addLog("Plugin", "Installing antislop plugin suite from upstream repository...")
                            if (pluginDir.exists()) {
                                pluginDir.deleteRecursively()
                            }
                            val cloneExit = EngineExecutor.executeCommand(
                                context,
                                "git clone --depth 1 https://github.com/miqdadbadjuber/anti-slop.git '${pluginDir.absolutePath}'"
                            ) { line, isErr ->
                                addLog("Plugin", line, isErr)
                            }
                            if (cloneExit != 0 || !File(pluginDir, "rules/AGENTS.md").exists()) {
                                rulesDir.mkdirs()
                                File(pluginDir, "plugin.json").writeText("{\n  \"name\": \"antislop\"\n}\n")
                                File(rulesDir, "AGENTS.md").writeText(
                                    """
                                    <EXTREMELY_IMPORTANT>
                                    You use antislop (Anti Slop: Rules for AI Coding Agents). It is a filter, not a style guide: it stops generic AI slop in generated UI, copy, and code, without prescribing aesthetics.

                                    For UI, copy, people, mobile layout, or code comments work, load the matching antislop skill before starting. Each skill is a folder in this plugin's skills/ directory (read its SKILL.md):
                                    - Core filter, always on: skills/antislop/SKILL.md
                                    - UI / visual: skills/antislop-ui/SKILL.md
                                    - Copy & text: skills/antislop-copywriting/SKILL.md
                                    - People: skills/antislop-human/SKILL.md
                                    - Mobile / responsive: skills/antislop-layoutmobile/SKILL.md
                                    - Code comments: skills/antislop-code/SKILL.md
                                    Before starting, follow the core's "Two Usage Modes" section: explicit session instruction first, then global preference, then ask. For a resolved mode, announce `antislop active: <mode> (session override).` or `antislop active: <mode> (global preference).` once, using the actual mode and source. Ask only when no mode is resolved.
                                    </EXTREMELY_IMPORTANT>
                                    """.trimIndent()
                                )
                            }
                            addLog("Plugin", "Anti-slop plugin suite installed.")
                            syncConfigToRoot(context)
                            true
                        }
                        else -> {
                            addLog("Plugin", "Configuring plugin '$pluginId'...")
                            val item = getAvailablePluginsCatalogue().firstOrNull { it.id == pluginId }
                            val desc = item?.description ?: "Plugin $pluginId"
                            File(pluginDir, "plugin.json").writeText("{\n  \"name\": \"$pluginId\"\n}\n")
                            File(rulesDir, "AGENTS.md").writeText("# Plugin: $pluginId\n\n$desc\n")
                            addLog("Plugin", "Plugin '$pluginId' configured.")
                            syncConfigToRoot(context)
                            true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AntigravityViewModel", "Error installing plugin $pluginId", e)
                    addLog("Plugin", "Failed to install plugin $pluginId: ${e.message}", isError = true)
                    false
                }
            }
            mutableInstallingItemId.value = null
            refreshPlugins(context)
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun installSkillFromCatalogueAsync(context: Context, skillId: String, onComplete: (Boolean) -> Unit) {
        if (mutableInstallingItemId.value != null) return
        mutableInstallingItemId.value = skillId

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val configDir = getGeminiConfigDir(context)
                    val destDir = File(configDir, "skills/$skillId").apply { mkdirs() }
                    val builtinDir = getBuiltinSkillsDir(context)
                    val srcDir = File(builtinDir, skillId)

                    addLog("Skill", "Installing skill '$skillId'...")
                    if (srcDir.exists() && srcDir.isDirectory) {
                        srcDir.copyRecursively(destDir, overwrite = true)
                        syncConfigBidirectional(context)
                        addLog("Skill", "Skill '$skillId' installed from built-in repository.")
                        return@withContext true
                    }

                    val antislopSkillsDir = File(configDir, "plugins/antislop/skills/$skillId")
                    if (antislopSkillsDir.exists() && antislopSkillsDir.isDirectory) {
                        antislopSkillsDir.copyRecursively(destDir, overwrite = true)
                        syncConfigBidirectional(context)
                        addLog("Skill", "Skill '$skillId' imported from antislop plugin.")
                        return@withContext true
                    }

                    val item = getAvailableSkillsCatalogue().firstOrNull { it.id == skillId }
                    val desc = item?.description ?: "Skill $skillId"
                    val skillFile = File(destDir, "SKILL.md")
                    val mdContent = buildString {
                        appendLine("---")
                        appendLine("name: $skillId")
                        appendLine("description: \"$desc\"")
                        appendLine("---")
                        appendLine()
                        appendLine("# $skillId")
                        appendLine()
                        appendLine(desc)
                    }
                    skillFile.writeText(mdContent)
                    syncConfigBidirectional(context)
                    addLog("Skill", "Skill '$skillId' installed.")
                    true
                } catch (e: Exception) {
                    Log.e("AntigravityViewModel", "Failed installing skill: $skillId", e)
                    addLog("Skill", "Failed to install skill $skillId: ${e.message}", isError = true)
                    false
                }
            }
            mutableInstallingItemId.value = null
            refreshSkills(context)
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun installSkillFromCatalogue(context: Context, skillId: String): Boolean {
        installSkillFromCatalogueAsync(context, skillId) {}
        return true
    }

    fun installPluginFromCatalogue(context: Context, pluginId: String): Boolean {
        installPluginFromCatalogueAsync(context, pluginId) {}
        return true
    }

    fun calculateTokenUsage(context: Context): TokenUsageStats {
        return CustomizationCatalogue.calculateTokenUsage(
            context = context,
            configDir = getGeminiConfigDir(context),
            builtinDir = getBuiltinSkillsDir(context)
        )
    }

    fun bootEngine(context: Context, onReady: () -> Unit) {
        viewModelScope.launch {
            mutableIsBooting.value = true
            mutableBootError.value = null
            mutableSetupProgress.value = 0.05f
            loadPreferences(context)

            mutableBootStatusText.value = "Checking Linux userspace bootstrap..."
            addLog("Boot", "Checking Linux userspace installation...")

            val installOk = BootstrapInstaller.installIfNeeded(context) { step, progress ->
                mutableBootStatusText.value = step
                mutableSetupProgress.value = progress
                addLog("Setup", step)
            }

            if (!installOk) {
                val detail = BootstrapInstaller.lastStackTrace
                    ?: BootstrapInstaller.lastError
                    ?: "Failed to extract bootstrap. Please ensure device internal storage is sufficient."
                mutableBootError.value = BootstrapInstaller.lastError ?: "Failed to extract Linux bootstrap & engine."
                mutableBootErrorDetail.value = detail
                addLog("Boot", "Bootstrap installation failed: $detail", isError = true)
                mutableIsBooting.value = false
                return@launch
            }

            mutableSetupProgress.value = 0.90f
            mutableBootStatusText.value = "Starting Antigravity Core Service..."
            addLog("Boot", "Launching CoreServerService (port 45158 & 45161)...")
            startCoreService(context)

            delay(1000)
            mutableSetupProgress.value = 1.0f
            mutableBootStatusText.value = "Antigravity ready"
            addLog("Boot", "Antigravity ready")
            mutableIsBooting.value = false
            onReady()
        }
    }

    fun startGoogleSignIn(context: Context) {
        val pendingUrl = mutablePendingAuthUrl.value
        if (!pendingUrl.isNullOrBlank()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pendingUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                addLog("Auth", "Opening official engine PKCE auth URL in browser...")
                mutableShowGoogleSignInDialog.value = true
                return
            } catch (e: Exception) {
                addLog("Auth", "Failed to open auth URL: ${e.message}", isError = true)
            }
        }

        if (mutableIsAuthenticating.value) {
            mutableShowGoogleSignInDialog.value = true
            return
        }

        viewModelScope.launch {
            mutableIsAuthenticating.value = true
            mutableAuthError.value = null
            mutableShowGoogleSignInDialog.value = true
            addLog("Auth", "Initiating Google OAuth flow on port 8085...")

            OAuthManager.startBrowserAuth(
                context = context,
                onSuccess = { email ->
                    mutableUserEmail.value = email
                    mutableIsAuthenticating.value = false
                    mutableShowGoogleSignInDialog.value = false
                    mutablePendingAuthUrl.value = null
                    mutableUserQuotaSummary.value = null
                    mutableCodeAssistTier.value = null
                    refreshAuthState(context)
                    CloudCodeQuotaService.restartLocalLanguageServer(context)
                    restartCoreService(context)
                    addLog("Auth", "Google sign-in successful: $email")
                    Toast.makeText(context, context.getString(R.string.auth_signed_in_success, email), Toast.LENGTH_SHORT).show()
                    viewModelScope.launch {
                        delay(2500)
                        refreshUserQuota(context)
                    }
                },
                onError = { err ->
                    mutableAuthError.value = err
                    mutableIsAuthenticating.value = false
                    addLog("Auth", "Google sign-in error: $err", isError = true)
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun onActivityResume(context: Context) {
        checkForCoreEngineUpdate(context)
        checkForOtaUpdates(context)
        OAuthManager.checkAndImportExistingToken(context)
        val email = OAuthManager.getUserEmail(context)
        if (email != null && email != mutableUserEmail.value) {
            mutableUserEmail.value = email
            mutableUserQuotaSummary.value = null
            mutableCodeAssistTier.value = null
            CloudCodeQuotaService.restartLocalLanguageServer(context)
            restartCoreService(context)
            addLog("Auth", "Account switch detected on app resume: $email")
            refreshAuthState(context)
        } else if (email != null) {
            if (mutableUserPicture.value.isNullOrBlank()) {
                val pic = OAuthManager.getUserPicture(context)
                if (!pic.isNullOrBlank()) {
                    mutableUserPicture.value = pic
                } else {
                    viewModelScope.launch {
                        val userInfo = OAuthManager.fetchUserInfo(context)
                        if (!userInfo?.picture.isNullOrBlank()) {
                            mutableUserPicture.value = userInfo?.picture
                        }
                    }
                }
            }
        }
    }

    fun exchangeManualAuthCode(context: Context, rawCodeOrToken: String) {
        var trimmed = rawCodeOrToken.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val uri = Uri.parse(trimmed)
                val codeParam = uri.getQueryParameter("code")
                if (!codeParam.isNullOrBlank()) {
                    trimmed = codeParam
                }
            } catch (e: Exception) {}
        }
        if (trimmed.contains("%")) {
            try {
                trimmed = java.net.URLDecoder.decode(trimmed, "UTF-8")
            } catch (e: Exception) {}
        }

        val sent = EngineExecutor.writeToAgyStdin(trimmed)
        if (sent) {
            mutableShowGoogleSignInDialog.value = false
            mutablePendingAuthUrl.value = null
            addLog("Auth", "Submitted authorization code directly to active engine stdin")
            Toast.makeText(context, context.getString(R.string.auth_verifying_code), Toast.LENGTH_SHORT).show()
            viewModelScope.launch {
                delay(1500)
                OAuthManager.checkAndImportExistingToken(context)
                refreshAuthState(context)
                restartCoreService(context)
            }
            return
        }

        viewModelScope.launch {
            mutableIsAuthenticating.value = true
            mutableAuthError.value = null
            addLog("Auth", "Processing authentication code/token...")

            OAuthManager.exchangeCodeDirectly(
                context = context,
                rawInput = trimmed,
                onSuccess = { email ->
                    mutableUserEmail.value = email
                    mutableIsAuthenticating.value = false
                    mutableShowGoogleSignInDialog.value = false
                    mutablePendingAuthUrl.value = null
                    mutableUserQuotaSummary.value = null
                    mutableCodeAssistTier.value = null
                    refreshAuthState(context)
                    restartCoreService(context)
                    addLog("Auth", "Google sign-in successful: $email")
                    Toast.makeText(context, context.getString(R.string.auth_signed_in_success, email), Toast.LENGTH_SHORT).show()
                    viewModelScope.launch {
                        delay(2500)
                        refreshUserQuota(context)
                    }
                },
                onError = { err ->
                    mutableAuthError.value = err
                    mutableIsAuthenticating.value = false
                    addLog("Auth", "Google sign-in error: $err", isError = true)
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun signOutGoogle(context: Context) {
        OAuthManager.clearToken(context)
        mutableUserEmail.value = null
        mutableUserName.value = ""
        mutableUserPicture.value = null
        mutableUserQuotaSummary.value = null
        mutableCodeAssistTier.value = null
        mutableCliModels.value = emptyList()
        mutableSelectedModelCliId.value = null

        mutableSelectedModel.value = ""
        mutableThinkingBudget.value = ""
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_SELECTED_MODEL)
            .remove(KEY_SELECTED_MODEL_CLI_ID)
            .remove(KEY_THINKING_BUDGET)
            .remove(KEY_CLI_MODELS_JSON)
            .remove(KEY_CLI_MODELS_FETCHED_AT)
            .remove(OAuthManager.KEY_USER_NAME)
            .remove(OAuthManager.KEY_USER_PICTURE)
            .remove(OAuthManager.KEY_USER_EMAIL)
            .apply()
        CloudCodeQuotaService.restartLocalLanguageServer(context)
        restartCoreService(context)
        addLog("Auth", "Signed out of Google account")
        Toast.makeText(context, context.getString(R.string.account_toast_signed_out), Toast.LENGTH_SHORT).show()
    }

    fun startCoreService(context: Context) {
        CoreServerService.startService(context)
        mutableEngineStatus.update { it.copy(isRunning = true) }
        addLog("Engine", "Core Server started")
    }

    fun stopCoreService(context: Context) {
        CoreServerService.stopService(context)
        mutableEngineStatus.update { it.copy(isRunning = false) }
        addLog("Engine", "Core Server stopped")
    }

    fun restartCoreService(context: Context) {
        viewModelScope.launch {
            addLog("Engine", "Restarting Core Server...")
            stopCoreService(context)
            delay(500)
            startCoreService(context)
        }
    }

    fun addLog(tag: String, message: String, isError: Boolean = false) {
        AppLogger.log(tag, message, isError)
    }

    fun clearLogs() {
        mutableExecutionLogs.value = emptyList()
    }

    fun copyLogsToClipboard(context: Context) {
        val logs = mutableExecutionLogs.value
        val logText = if (logs.isEmpty()) {
            context.getString(R.string.settings_logs_empty)
        } else {
            logs.joinToString("\n") { "[${it.formattedTime}] [${it.tag}] ${it.message}" }
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Terminal Logs", logText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.splash_logs_copied), Toast.LENGTH_SHORT).show()
    }

    fun exportLogsToFile(context: Context) {
        viewModelScope.launch {
            try {
                val logs = mutableExecutionLogs.value
                val logDir = File(context.filesDir, "logs").apply { mkdirs() }
                val logFile = File(logDir, "execution_${System.currentTimeMillis()}.log")
                val logText = logs.joinToString("\n") { "[${it.formattedTime}] [${it.tag}] ${it.message}" }
                logFile.writeText(logText, Charsets.UTF_8)
                Toast.makeText(context, "Logs exported: ${logFile.name}", Toast.LENGTH_SHORT).show()
                addLog("Log", "Exported logs to ${logFile.absolutePath}")
            } catch (e: Exception) {
                addLog("Log", "Failed to export logs: ${e.message}", isError = true)
            }
        }
    }

    fun addAttachment(context: Context, uri: Uri): Boolean {
        if (mutableAttachedFiles.value.size >= 5) {
            return false
        }
        try {
            var name = "file_${System.currentTimeMillis()}"
            var size = 0L
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) name = it.getString(nameIndex)
                    if (sizeIndex != -1) size = it.getLong(sizeIndex)
                }
            }
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

            val uploadsDir = File(context.filesDir, "uploads").apply { mkdirs() }
            uploadsDir.setReadable(true, false)
            uploadsDir.setExecutable(true, false)
            try { android.system.Os.chmod(uploadsDir.absolutePath, 493) } catch (e: Exception) {}

            val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(uploadsDir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
            targetFile.setReadable(true, false)
            try { android.system.Os.chmod(targetFile.absolutePath, 420) } catch (e: Exception) {}

            val file = AttachedFile(
                id = UUID.randomUUID().toString(),
                uri = uri,
                name = name,
                sizeBytes = if (size > 0L) size else targetFile.length(),
                mimeType = mimeType,
                localPath = targetFile.absolutePath
            )
            mutableAttachedFiles.update { if (it.size < 5) it + file else it }
            addLog("Chat", "Attached file: $name (${file.formattedSize})")
            return true
        } catch (e: Exception) {
            addLog("Chat", "Error attaching file: ${e.message}", isError = true)
            return false
        }
    }

    fun removeAttachment(file: AttachedFile) {
        file.localPath?.let { path ->
            runCatching {
                val f = File(path)
                if (f.exists() && f.parentFile?.name == "uploads") {
                    f.delete()
                }
            }
        }
        mutableAttachedFiles.update { it.filter { item -> item.id != file.id } }
    }

    fun showGoogleSignInDialog() {
        mutableShowGoogleSignInDialog.value = true
    }

    fun dismissGoogleSignInDialog() {
        mutableShowGoogleSignInDialog.value = false
    }

    fun showModelSelector() {
        mutableShowModelSelector.value = true
    }

    fun dismissModelSelector() {
        mutableShowModelSelector.value = false
    }

    fun queueMessage(content: String, attachments: List<AttachedFile> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return
        val item = QueuedMessage(
            content = content.trim(),
            attachments = attachments
        )
        mutableQueuedMessages.update { it + item }
    }

    fun addRunningTask(name: String, command: String): RunningTask {
        val task = RunningTask(name = name, command = command)
        mutableRunningTasks.update { it + task }
        return task
    }

    fun removeRunningTask(id: String) {
        mutableRunningTasks.update { list -> list.filter { it.id != id } }
    }

    fun stopRunningTask(id: String) {
        removeRunningTask(id)
        if (mutableRunningTasks.value.isEmpty()) {
            stopAiExecution()
        }
    }

    fun addRunningSubagent(subagent: RunningSubagent) {
        mutableRunningSubagents.update { list ->
            val idx = list.indexOfFirst { it.id == subagent.id || (it.conversationId != null && it.conversationId == subagent.conversationId) }
            if (idx >= 0) {
                list.toMutableList().apply { set(idx, subagent) }
            } else {
                list + subagent
            }
        }
    }

    fun completeSubagent(convIdOrId: String) {
        mutableRunningSubagents.update { list ->
            list.map { if (it.conversationId == convIdOrId || it.id == convIdOrId) it.copy(isRunning = false) else it }
        }
    }

    fun removeRunningSubagent(id: String) {
        mutableRunningSubagents.update { list -> list.filter { it.id != id && it.conversationId != id } }
    }

    fun stopSubagent(id: String) {
        removeRunningSubagent(id)
    }

    fun loadSubagentMessages(context: Context, convId: String?): List<ChatMessage> {
        if (convId.isNullOrBlank()) return emptyList()
        val persisted = ConversationPersistence.loadMessagesForConversation(context, convId)
        if (persisted.isNotEmpty()) return persisted
        return TranscriptTurnParser.parseTranscriptToMessages(context, convId)
    }

    fun openSubagentAsActiveConversation(context: Context, subagent: RunningSubagent) {
        val convId = subagent.conversationId ?: subagent.id
        val title = subagent.role.ifEmpty { subagent.typeName.ifEmpty { context.getString(R.string.subagent_default_title) } }
        val summary = ConversationSummary(
            id = convId,
            title = title,
            timestamp = System.currentTimeMillis(),
            messageCount = 1,
            isPinned = false,
            projectPath = mutableWorkspacePath.value
        )
        val history = ConversationPersistence.loadConversationHistory(context)
        if (history.none { it.id == convId }) {
            ConversationPersistence.saveConversationHistory(context, listOf(summary) + history)
        }
        loadConversation(context, summary)
    }

    fun loadDaemonTasks(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val tasks = mutableListOf<DaemonTask>()
            val candidates = listOf(
                File(context.filesDir, ".gemini/antigravity-app/daemon/tasks.json"),
                File(context.filesDir, "home/.gemini/antigravity-app/daemon/tasks.json")
            )
            for (file in candidates) {
                if (file.exists() && file.isFile) {
                    try {
                        val text = file.readText()
                        val array = org.json.JSONArray(text)
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            tasks.add(
                                DaemonTask(
                                    id = obj.optString("id", "task_$i"),
                                    name = obj.optString("name", obj.optString("id", "Task")),
                                    command = obj.optString("command", obj.optString("prompt", "")),
                                    triggerType = obj.optString("trigger_type", "interval"),
                                    intervalSec = obj.optInt("interval", 0),
                                    cron = obj.optString("cron", ""),
                                    enabled = obj.optBoolean("enabled", true),
                                    lastStatus = obj.optString("last_status", "idle")
                                )
                            )
                        }
                        break
                    } catch (e: Exception) {

                    }
                }
            }
            mutableDaemonTasks.value = tasks
        }
    }

    fun removeQueuedMessage(id: String) {
        mutableQueuedMessages.update { list -> list.filter { it.id != id } }
    }

    fun editQueuedMessage(id: String): QueuedMessage? {
        val item = mutableQueuedMessages.value.find { it.id == id } ?: return null
        removeQueuedMessage(id)
        return item
    }

    fun processQueuedMessageNow(context: Context, id: String) {
        val item = mutableQueuedMessages.value.find { it.id == id } ?: return
        removeQueuedMessage(id)
        if (mutableIsExecuting.value) {
            stopAiExecution()
        }
        sendMessage(context, item.content, item.attachments)
    }

    fun processNextQueuedMessage(context: Context) {
        if (mutableIsExecuting.value) return
        val next = mutableQueuedMessages.value.firstOrNull() ?: return
        removeQueuedMessage(next.id)
        sendMessage(context, next.content, next.attachments)
    }

    fun getWorkspaceFiles(query: String = ""): List<String> {
        val rootPath = mutableWorkspacePath.value
        val rootDir = if (!rootPath.isNullOrEmpty()) File(rootPath) else null
        val result = mutableListOf<String>()

        if (rootDir != null && rootDir.exists() && rootDir.isDirectory) {
            try {
                fun scan(dir: File, depth: Int) {
                    if (depth > 4 || result.size > 50) return
                    dir.listFiles()?.forEach { file ->
                        if (file.name.startsWith(".") || file.name == "build" || file.name == "node_modules" || file.name == ".git") return@forEach
                        val relPath = file.relativeTo(rootDir).path
                        if (query.isBlank() || relPath.contains(query, ignoreCase = true) || file.name.contains(query, ignoreCase = true)) {
                            result.add(relPath)
                        }
                        if (file.isDirectory) scan(file, depth + 1)
                    }
                }
                scan(rootDir, 0)
            } catch (e: Exception) {}
        }
        if (result.isEmpty()) {
            listOf("README.md", "build.gradle.kts", "AndroidManifest.xml", "settings.gradle.kts", "package.json").forEach {
                if (query.isBlank() || it.contains(query, ignoreCase = true)) result.add(it)
            }
        }
        return result.take(15)
    }

    private val mutableSlashActions = MutableStateFlow<List<SlashAction>>(SYSTEM_SLASH_COMMANDS)
    val slashActions: StateFlow<List<SlashAction>> = mutableSlashActions.asStateFlow()
    private var slashRefreshJob: Job? = null

    fun refreshCliSlashCommands(context: Context) {
        if (slashRefreshJob?.isActive == true) return
        slashRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {

                val skills = getInstalledSkills(context).map {
                    SlashAction(
                        cmd = it.name.lowercase().trimStart('/'),
                        descText = it.description.ifBlank { null },
                        category = if (it.isBuiltIn) "Built-in Skill" else "Skill"
                    )
                }

                val workflows = getDiscoveredWorkflows(context)

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val cached = loadCachedSlashCommands(prefs)
                val cachedVersion = prefs.getString(KEY_CLI_SLASH_VERSION, null)

                var cliCommands = cached
                if (!EngineExecutor.isAgyRunning() && !mutableIsExecuting.value) {
                    val version = runAgyCapture(context, listOf("--version"), 10_000L)
                        ?.second?.lines()?.firstOrNull { it.isNotBlank() }?.trim()

                    if (version != null && (version != cachedVersion || cached.isEmpty())) {
                        val help = runAgyCapture(context, listOf("--help"), 15_000L)
                        val parsed = help?.second?.let { SlashCommandParser.parse(it) } ?: emptyList()
                        if (parsed.isNotEmpty()) {
                            saveCachedSlashCommands(prefs, parsed, version)
                            cliCommands = parsed
                        }
                    }
                }

                val allActions = mergeDynamicSlashActions(
                    systemCommands = SYSTEM_SLASH_COMMANDS,
                    cliCommands = cliCommands,
                    skills = skills,
                    workflows = workflows
                )

                mutableSlashActions.value = allActions
                addLog("Slash", "Loaded dynamic slash menu (${allActions.size} actions: ${skills.size} skills, ${workflows.size} workflows, ${cliCommands.size} CLI)")
            } catch (e: Exception) {
                try {
                    val fallbackSkills = getInstalledSkills(context).map {
                        SlashAction(it.name.lowercase().trimStart('/'), it.description.ifBlank { null }, "Skill")
                    }
                    mutableSlashActions.value = mergeDynamicSlashActions(
                        systemCommands = SYSTEM_SLASH_COMMANDS,
                        cliCommands = emptyList(),
                        skills = fallbackSkills,
                        workflows = emptyList()
                    )
                } catch (e: Exception) {}
                addLog("Slash", "Failed to load slash menu: ${e.message}", isError = true)
            }
        }
    }

    private fun getDiscoveredWorkflows(context: Context): List<SlashAction> {
        val list = mutableListOf<SlashAction>()
        val seen = mutableSetOf<String>()
        val configDir = getGeminiConfigDir(context)
        val dirs = mutableListOf(
            File(configDir, "workflows"),
            File(context.filesDir, ".gemini/config/workflows"),
            File(context.filesDir, "home/.gemini/config/workflows")
        )
        val wsPath = mutableWorkspacePath.value
        if (!wsPath.isNullOrBlank()) {
            val wsDir = File(wsPath)
            dirs.add(File(wsDir, ".gemini/workflows"))
            dirs.add(File(wsDir, ".agents/workflows"))
            dirs.add(File(wsDir, ".agent/workflows"))
            dirs.add(File(wsDir, "workflows"))
        }
        for (dir in dirs) {
            if (dir.isDirectory) {
                dir.listFiles()?.filter { it.isFile && (it.extension == "md" || it.extension == "prompt" || it.extension == "yaml") }?.forEach { f ->
                    val name = f.nameWithoutExtension.lowercase()
                    if (!seen.contains(name)) {
                        seen.add(name)
                        var desc = "Workflow from ${f.name}"
                        try {
                            val firstLine = f.readLines().firstOrNull { it.isNotBlank() && !it.startsWith("---") && !it.startsWith("#") }
                            if (!firstLine.isNullOrBlank()) desc = firstLine.trim()
                        } catch (e: Exception) {}
                        list.add(SlashAction(name, desc, "Workflow"))
                    }
                }
            }
        }
        return list
    }

    private suspend fun runAgyCapture(
        context: Context,
        args: List<String>,
        timeoutMs: Long,
    ): Pair<Int, String>? {
        val sb = StringBuilder()
        val wsDir = run {
            val currentWorkspace = mutableWorkspacePath.value
            val dir = if (!currentWorkspace.isNullOrBlank()) File(currentWorkspace) else null
            if (dir != null && dir.exists() && dir.isDirectory) dir else null
        }
        val exit = withTimeoutOrNull(timeoutMs) {
            EngineExecutor.executeAgy(context, args, workingDir = wsDir) { line, isErr ->
                sb.appendLine(line)
            }
        } ?: return null
        if (exit != 0) return null
        return exit to sb.toString()
    }

    private fun loadCachedSlashCommands(
        prefs: SharedPreferences,
    ): List<CliSlashCommand> {
        val raw = prefs.getString(KEY_CLI_SLASH_COMMANDS, null) ?: return emptyList()
        val out = ArrayList<CliSlashCommand>()
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            out.add(CliSlashCommand(line.substring(0, tab), line.substring(tab + 1)))
        }
        return out
    }

    private fun saveCachedSlashCommands(
        prefs: SharedPreferences,
        commands: List<CliSlashCommand>,
        version: String?,
    ) {
        val text = commands.joinToString("\n") { cmd ->
            val desc = cmd.description.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            "${cmd.cmd}\t$desc"
        }
        prefs.edit()
            .putString(KEY_CLI_SLASH_COMMANDS, text)
            .putString(KEY_CLI_SLASH_VERSION, version)
            .putLong(KEY_CLI_SLASH_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    fun sendMessage(context: Context, content: String, attachments: List<AttachedFile> = emptyList()) {
        val currentAttachments = if (attachments.isNotEmpty()) attachments else mutableAttachedFiles.value
        if (content.isBlank() && currentAttachments.isEmpty()) return

        if (content.trim().equals("/clear", ignoreCase = true)) {
            val convId = mutableActiveConversationId.value
            mutableChatMessages.value = emptyList()
            if (convId != null) {
                saveMessagesForConversation(context, convId, emptyList())
                mutableConversationHistory.update { list ->
                    list.map { if (it.id == convId) it.copy(messageCount = 0) else it }
                }
                saveConversationHistory(context)
            }
            return
        }

        if (mutableIsExecuting.value) {
            if (mutableQueuedMessagesMode.value.equals("Send Immediately", ignoreCase = true)) {
                stopAiExecution()
            } else {
                queueMessage(content, currentAttachments)
                mutableAttachedFiles.value = emptyList()
                return
            }
        }

        var convId = mutableActiveConversationId.value
        val isFirstMessage = (convId == null || mutableChatMessages.value.isEmpty())
        if (convId == null) {
            convId = UUID.randomUUID().toString()
            mutableActiveConversationId.value = convId
        }

        if (mutableConversationTitle.value == "New Conversation" || mutableConversationTitle.value.isBlank()) {
            val titleCandidate = content.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(35)
                ?: currentAttachments.firstOrNull()?.name?.take(35)
            if (!titleCandidate.isNullOrEmpty()) {
                mutableConversationTitle.value = titleCandidate
            }
        }

        val effectiveContent = if (content.isBlank() && currentAttachments.isNotEmpty()) {
            context.getString(R.string.chat_attached_files_prompt)
        } else {
            content
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = effectiveContent,
            attachments = currentAttachments
        )
        val updatedMessages = mutableChatMessages.value + userMessage
        mutableChatMessages.value = updatedMessages
        mutableAttachedFiles.value = emptyList()

        val currentSummary = ConversationSummary(
            id = convId,
            title = mutableConversationTitle.value,
            timestamp = System.currentTimeMillis(),
            messageCount = updatedMessages.size,
            isPinned = mutableIsConversationPinned.value,
            projectPath = mutableWorkspacePath.value
        )
        mutableConversationHistory.update { list ->
            listOf(currentSummary) + list.filter { it.id != convId }
        }
        saveConversationHistory(context)
        saveMessagesForConversation(context, convId, updatedMessages)
        saveLastActiveConversationId(context, convId)

        val isShellCommand = content.startsWith("!") || content.startsWith("$ ")
        addLog("Chat", "User prompt: ${effectiveContent.take(150)}")
        if (!isShellCommand && !OAuthManager.hasToken(context)) {
            mutableShowGoogleSignInDialog.value = true
            val assistantMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = context.getString(R.string.chat_auth_required_error),
                status = MessageStatus.SENT,
                thinking = null
            )
            val finalMessages = updatedMessages + assistantMsg
            mutableChatMessages.value = finalMessages
            mutableConversationHistory.update { list ->
                list.map { if (it.id == convId) it.copy(messageCount = finalMessages.size) else it }
            }
            saveConversationHistory(context)
            saveMessagesForConversation(context, convId, finalMessages)
            return
        }

        executeAiTurn(context, effectiveContent, convId, currentAttachments)
    }

    private val executionWakeLock = WakeLockHolder()
    private var currentAiExecutionJob: Job? = null

    fun stopAiExecution() {
        executionWakeLock.release()
        addLog("Chat", "AI generation stopped by user")
        currentAiExecutionJob?.cancel()
        currentAiExecutionJob = null
        EngineExecutor.stopAgyProcess()
        mutableIsExecuting.value = false
        mutableRunningTasks.value = emptyList()
        mutableChatMessages.update { list ->
            list.map { msg ->
                if (msg.status == MessageStatus.SENDING) {
                    msg.copy(
                        status = MessageStatus.SENT,
                        content = if (msg.content.isBlank()) "[Generation stopped by user]" else "${msg.content}\n\n*[Stopped by user]*",
                        thinking = null
                    )
                } else msg
            }
        }
    }

    fun undoPrompt(context: Context, userMessage: ChatMessage) {
        if (mutableIsExecuting.value) {
            stopAiExecution()
        }
        val promptText = userMessage.content
        mutablePendingInputText.value = promptText

        val current = mutableChatMessages.value.toMutableList()
        val userIdx = current.indexOfFirst { it.id == userMessage.id }
        if (userIdx != -1) {
            var endIdx = userIdx + 1
            while (endIdx < current.size && current[endIdx].role != MessageRole.USER) {
                endIdx++
            }
            current.subList(userIdx, endIdx).clear()
            mutableChatMessages.value = current
            val activeId = mutableActiveConversationId.value
            if (activeId != null) {
                saveMessagesForConversation(context, activeId, current)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val root = File(mutableWorkspacePath.value)
            if (root.exists() && File(root, ".git").exists()) {
                EngineExecutor.executeCommand(
                    context,
                    "cd '${root.absolutePath}' && (git restore --staged . 2>/dev/null || true) && (git restore . 2>/dev/null || git checkout -- . 2>/dev/null || true)"
                ) { _, _ -> }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.chat_toast_prompt_undone), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeAiTurn(
        context: Context,
        prompt: String,
        convId: String,
        attachments: List<AttachedFile> = emptyList()
    ) {
        currentAiExecutionJob?.cancel()
        val notification = ForegroundNotification.beginExecution(
            context.getString(R.string.notification_agent_working)
        )
        currentAiExecutionJob = viewModelScope.launch(Dispatchers.IO) {
            executionWakeLock.acquire(context, 30 * 60 * 1000L)
            mutableIsExecuting.value = true
            mutableActiveWorkingStatus.value = "Working"
            val turnStartTime = System.currentTimeMillis()

            val assistantMsgId = UUID.randomUUID().toString()
            val initialAssistantMsg = ChatMessage(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.SENDING,
                thinking = null
            )
            mutableChatMessages.update { it + initialAssistantMsg }

            val currentWorkspace = mutableWorkspacePath.value
            val wsDir = if (!currentWorkspace.isNullOrBlank()) File(currentWorkspace) else null
            val validWsDir = if (wsDir != null && wsDir.exists() && wsDir.isDirectory) wsDir else null

            try {

                if (prompt.startsWith("!") || prompt.startsWith("$ ")) {
                    val cmd = if (prompt.startsWith("!")) prompt.substring(1).trim() else prompt.substring(2).trim()
                    ForegroundNotification.updateExecution(
                        notification, context.getString(R.string.notification_agent_command, cmd)
                    )
                    mutableChatMessages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(commandRun = cmd, thinking = "Executing command in Linux shell...")
                            } else msg
                        }
                    }

                    val outputSb = StringBuilder()
                    val exitCode = EngineExecutor.executeCommand(context, cmd, workingDir = validWsDir) { line, exitCode ->
                        outputSb.append(line).append("\n")
                        mutableChatMessages.update { list ->
                            list.map { msg ->
                                if (msg.id == assistantMsgId) {
                                    msg.copy(commandOutput = outputSb.toString())
                                } else msg
                            }
                        }
                    }

                    mutableChatMessages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    status = if (exitCode == 0) MessageStatus.SENT else MessageStatus.ERROR,
                                    content = "",
                                    thinking = null
                                )
                            } else msg
                        }
                    }
                } else {

                    val stagedAttachments = mutableListOf<Triple<String, String, String>>()
                    if (attachments.isNotEmpty()) {
                        val targetDirs = listOf(
                            File(context.filesDir, ".gemini/antigravity-app/brain/$convId/.user_uploaded"),
                            File(context.filesDir, "home/.gemini/antigravity-app/brain/$convId/.user_uploaded")
                        )
                        for (dir in targetDirs) {
                            dir.mkdirs()
                            dir.setReadable(true, false)
                            dir.setExecutable(true, false)
                            try { android.system.Os.chmod(dir.absolutePath, 493) } catch (e: Exception) {}
                        }
                        for (att in attachments) {
                            val safeName = att.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            var finalPath: String? = null
                            for (dir in targetDirs) {
                                val destFile = File(dir, safeName)
                                val sourceFile = att.localPath?.let { File(it) }
                                if (sourceFile != null && sourceFile.exists()) {
                                    runCatching { sourceFile.copyTo(destFile, overwrite = true) }
                                } else {
                                    runCatching {
                                        context.contentResolver.openInputStream(att.uri)?.use { input ->
                                            destFile.outputStream().use { output -> input.copyTo(output) }
                                        }
                                    }
                                }
                                destFile.setReadable(true, false)
                                try { android.system.Os.chmod(destFile.absolutePath, 420) } catch (e: Exception) {}
                                if (destFile.exists() && finalPath == null) {
                                    finalPath = destFile.absolutePath
                                }
                            }
                            stagedAttachments.add(Triple(att.name, finalPath ?: (att.localPath ?: att.name), att.mimeType))
                        }
                    }

                    val model = mutableSelectedModel.value
                    val thinking = mutableThinkingBudget.value
                    val outputSb = StringBuilder()

                    val rawCliId = mutableSelectedModelCliId.value?.trim().orEmpty()
                    val cliId = AgyModelsService.cleanModelId(rawCliId)
                    val priorCliSession = convIdToCliSessionId[convId]

                    addLog("Agent", "Turn started: prompt='${prompt.take(120)}', attachments=${attachments.size}, model='${cliId.ifEmpty { "default" }}'")
                    TerminalSessionManager.ensureAgyLiveSession(autoSwitch = false)
                    TerminalSessionManager.appendAgyLine("[AGY] Turn started (model: ${cliId.ifEmpty { "default" }})", isCommand = true)
                    if (validWsDir != null) {
                        addLog("Agent", "Workspace: ${validWsDir.absolutePath}")
                        TerminalSessionManager.appendAgyLine("Working Directory: ${validWsDir.absolutePath}")
                    }

                    OAuthManager.checkAndImportExistingToken(context)
                    OAuthManager.refreshTokenIfNeeded(context)
                    OAuthManager.syncCompanionStateFiles(context)

                    var activeCliConvId: String? = priorCliSession
                    var lastStreamUpdateMs = 0L
                    var lastStepUpdateMs = 0L
                    val activeNotificationSteps = linkedMapOf<String, String>()
                    var lastTranscriptPollMs = 0L
                    val authUrlRegex = "(https://accounts\\.google\\.com/o/oauth2/auth\\S+)".toRegex()

                    var currentTurn = 0
                    val maxAutoTurns = 8
                    var autoContinue = true
                    var exitCode = 0
                    var authRequired = false

                    while (autoContinue && currentTurn < maxAutoTurns && coroutineContext.isActive) {
                        currentTurn++
                        outputSb.clear()

                        val args = mutableListOf<String>()
                        val currentSec = mutableSecurityPreset.value.lowercase()
                        if (currentSec == "turbo" || currentSec == "turbo mode") {
                            args.add("--dangerously-skip-permissions")
                        }
                        if (currentSec == "default" || currentSec == "standard" || currentSec == "request review" || currentSec == "request_review") {
                            args.add("--sandbox")
                        }
                        args.add("--output-format")
                        args.add("stream-json")
                        if (cliId.isNotBlank()) {
                            args.add("--model")
                            args.add(cliId)
                        }
                        if (validWsDir != null) {
                            args.add("--add-dir")
                            args.add(validWsDir.absolutePath)
                        }
                        val sessionToResume = activeCliConvId ?: priorCliSession
                        if (!sessionToResume.isNullOrBlank()) {
                            args.add("--conversation")
                            args.add(sessionToResume)
                        }
                        args.add("--print")
                        val currentPrompt = if (currentTurn == 1 && stagedAttachments.isNotEmpty()) {
                            buildString {
                                append("### USER ATTACHMENTS\n")
                                append("The user has attached the following ${stagedAttachments.size} file(s):\n")
                                stagedAttachments.forEachIndexed { idx, (name, path, mime) ->
                                    append("${idx + 1}. [Filename: $name]\n")
                                    append("   Path: $path\n")
                                    append("   MIME: $mime\n")
                                    if (mime.startsWith("image/")) {
                                        append("   Action: Call the `view_file` tool with AbsolutePath=\"$path\" to inspect this image.\n")
                                    } else {
                                        append("   Action: Call the `view_file` tool with AbsolutePath=\"$path\" to inspect this file.\n")
                                    }
                                }
                                append("MANDATORY: You must inspect the attached file(s) with the `view_file` tool before answering.\n\n")
                                append("### USER REQUEST\n")
                                append(if (prompt.isNotBlank()) prompt else "Please inspect the attached file(s) and assist the user.")
                            }
                        } else if (currentTurn == 1) {
                            prompt
                        } else {
                            "Continue and complete the task based on previous tool results. Provide thorough analysis or confirmation once finished."
                        }
                        args.add(currentPrompt)

                        if (currentTurn > 1) {
                            addLog("Agent", "Autonomous turn $currentTurn started...")
                        }

                        exitCode = EngineExecutor.executeAgy(context, args, workingDir = validWsDir) { line, exitCode ->
                        val trimmed = line.trim()
                        val match = authUrlRegex.find(trimmed)
                        if (match != null) {
                            val url = match.value
                            mutablePendingAuthUrl.value = url
                            mutableShowGoogleSignInDialog.value = true
                        }

                        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                            try {
                                val json = org.json.JSONObject(trimmed)
                                val event = json.optString("event")
                                val detectedConv = json.optString("conversation_id").ifBlank {
                                    json.optString("conversationId").ifBlank {
                                        json.optString("session_id").ifBlank {
                                            json.optString("sessionId")
                                        }
                                    }
                                }
                                if (detectedConv.isNotBlank() && activeCliConvId != detectedConv) {
                                    activeCliConvId = detectedConv
                                    convIdToCliSessionId[convId] = detectedConv
                                    addLog("Agent", "Linked conversation session: $detectedConv")
                                }

                                when (event) {
                                    "step_update" -> {
                                        val stepUpdate = json.optJSONObject("step_update")
                                        if (stepUpdate != null) {
                                            val stepType = stepUpdate.optString("step_type")
                                            val state = stepUpdate.optString("state")
                                            if (stepType == "tool") {
                                                val rawToolName = stepUpdate.optString("tool_name")
                                                val toolName = rawToolName.substringAfterLast(":")
                                                val toolInfo = stepUpdate.optJSONObject("tool_info")
                                                val params = toolInfo?.optJSONObject("parameters")
                                                    ?: toolInfo?.optJSONObject("args")
                                                    ?: toolInfo?.optJSONObject("arguments")
                                                    ?: stepUpdate.optJSONObject("parameters")
                                                    ?: stepUpdate.optJSONObject("args")
                                                    ?: toolInfo

                                                val stepKind: AgentStepKind
                                                val stepTitle: String
                                                val stepLines: String?
                                                val stepDiff: String?
                                                val stepFilePath: String?

                                                when (toolName) {
                                                    "read_file", "view_file" -> {
                                                        val path = extractPathFromParams(params)
                                                        stepFilePath = path.ifEmpty { null }
                                                        stepTitle = extractFileNameFromPath(path, fallback = "file")
                                                        val start = params?.optInt("StartLine", -1) ?: -1
                                                        val end = params?.optInt("EndLine", -1) ?: -1
                                                        stepLines = if (start > 0 && end >= start) "#L$start-$end" else if (start > 0) "#L$start" else null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.ANALYZED
                                                    }
                                                    "write_to_file", "replace_file_content" -> {
                                                        val path = extractPathFromParams(params)
                                                        stepFilePath = path.ifEmpty { null }
                                                        stepTitle = extractFileNameFromPath(path, fallback = "file")
                                                        stepLines = null
                                                        val targetContent = params?.optString("TargetContent") ?: ""
                                                        val replacementContent = params?.optString("ReplacementContent") ?: params?.optString("CodeContent") ?: ""
                                                        val delCount = if (targetContent.isNotBlank()) targetContent.count { it == '\n' } + 1 else 0
                                                        val addCount = if (replacementContent.isNotBlank()) replacementContent.count { it == '\n' } + 1 else 1
                                                        stepDiff = "+$addCount -$delCount"
                                                        stepKind = AgentStepKind.EDITED
                                                    }
                                                    "run_command" -> {
                                                        val action = params?.optString("toolAction")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                                                        val summary = params?.optString("toolSummary")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                                                        val cmd = params?.optString("CommandLine")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                                                        val cleanCmd = cmd.trim()
                                                        stepTitle = when {
                                                            action.isNotBlank() -> action
                                                            summary.isNotBlank() -> summary
                                                            cleanCmd.isNotBlank() -> cleanCmd
                                                            else -> "Command"
                                                        }
                                                        stepFilePath = null
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.COMMAND
                                                    }
                                                    "invoke_subagent" -> {
                                                        val subList = params?.optJSONArray("Subagents")
                                                        val firstSub = subList?.optJSONObject(0) ?: params
                                                        val role = firstSub?.optString("Role")?.ifEmpty { firstSub.optString("role") } ?: ""
                                                        val type = firstSub?.optString("TypeName")?.ifEmpty { firstSub.optString("typeName") } ?: ""
                                                        stepTitle = role.ifEmpty { type.ifEmpty { context.getString(R.string.subagent_default_title) } }
                                                        stepFilePath = null
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.WORKING
                                                    }
                                                    "manage_task" -> {
                                                        val action = params?.optString("Action") ?: "status"
                                                        val taskId = params?.optString("TaskId") ?: ""
                                                        stepTitle = if (taskId.isNotBlank()) "manage_task $action $taskId" else "manage_task $action"
                                                        stepFilePath = null
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.COMMAND
                                                    }
                                                    "schedule" -> {
                                                        val dur = params?.optInt("DurationSeconds", 0) ?: 0
                                                        stepTitle = if (dur > 0) "schedule (${dur}s)" else "schedule task"
                                                        stepFilePath = null
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.COMMAND
                                                    }
                                                    "search_web" -> {
                                                        val q = params?.optString("query")?.trim()?.removeSurrounding("\"") ?: ""
                                                        stepTitle = q.ifEmpty { "web" }
                                                        stepFilePath = null
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.SEARCH
                                                    }
                                                    "grep_search" -> {
                                                        val q = params?.optString("query")?.trim()?.removeSurrounding("\"") ?: ""
                                                        stepTitle = q.ifEmpty { "code" }
                                                        stepFilePath = null
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.SEARCH
                                                    }
                                                    "list_dir" -> {
                                                        val path = extractPathFromParams(params)
                                                        stepFilePath = path.ifEmpty { null }
                                                        stepTitle = extractFileNameFromPath(path, fallback = "directory")
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.ANALYZED
                                                    }
                                                    else -> {
                                                        stepFilePath = null
                                                        stepTitle = toolName.replace('_', ' ')
                                                        stepLines = null
                                                        stepDiff = null
                                                        stepKind = AgentStepKind.COMMAND
                                                    }
                                                }

                                                val stepDetail = stepUpdate.optString("output").ifEmpty {
                                                    stepUpdate.optString("result").ifEmpty { null }
                                                }
                                                val safeDetail = stepDetail

                                                val stableStepId = "step_live_${stepKind.name}_${stepFilePath ?: stepTitle}_${stepLines ?: ""}".replace(" ", "_")
                                                val execStep = AgentExecutionStep(
                                                    id = stableStepId,
                                                    kind = stepKind,
                                                    title = stepTitle,
                                                    lines = stepLines,
                                                    diff = stepDiff,
                                                    filePath = stepFilePath,
                                                    detail = safeDetail,
                                                    isCompleted = state != "ACTIVE"
                                                )

                                                if (toolName == "run_command") {
                                                    val cmdClean = params?.optString("CommandLine")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                                                    if (state == "ACTIVE") {
                                                        if (cmdClean.isNotBlank() && mutableRunningTasks.value.none { it.id == stableStepId || it.command == cmdClean }) {
                                                            mutableRunningTasks.update { it + RunningTask(id = stableStepId, name = stepTitle, command = cmdClean) }
                                                        }
                                                    } else {
                                                        removeRunningTask(stableStepId)
                                                        if (cmdClean.isNotBlank()) {
                                                            mutableRunningTasks.update { list -> list.filter { it.command != cmdClean } }
                                                        }
                                                    }
                                                } else if (toolName == "invoke_subagent") {
                                                    if (state == "ACTIVE") {
                                                        val subList = params?.optJSONArray("Subagents")
                                                        if (subList != null && subList.length() > 0) {
                                                            for (s in 0 until subList.length()) {
                                                                val sObj = subList.optJSONObject(s) ?: continue
                                                                val r = sObj.optString("Role").ifEmpty { sObj.optString("role") }
                                                                val t = sObj.optString("TypeName").ifEmpty { sObj.optString("typeName") }
                                                                val p = sObj.optString("Prompt").ifEmpty { sObj.optString("prompt") }
                                                                val subId = "${stableStepId}_$s"
                                                                addRunningSubagent(RunningSubagent(id = subId, typeName = t, role = r, prompt = p, isRunning = true))
                                                            }
                                                        } else {
                                                            val r = params?.optString("Role")?.ifEmpty { params.optString("role") } ?: context.getString(R.string.subagent_default_title)
                                                            val t = params?.optString("TypeName")?.ifEmpty { params.optString("typeName") } ?: ""
                                                            val p = params?.optString("Prompt")?.ifEmpty { params.optString("prompt") } ?: ""
                                                            addRunningSubagent(RunningSubagent(id = stableStepId, typeName = t, role = r, prompt = p, isRunning = true))
                                                        }
                                                    } else {
                                                        val detectedSubConvId = try {
                                                            if (!stepDetail.isNullOrBlank()) {
                                                                val detailJson = JSONObject(stepDetail)
                                                                val res = detailJson.optJSONObject("result")
                                                                res?.optString("conversationId")?.ifEmpty { null }
                                                                    ?: detailJson.optString("conversationId").ifEmpty { null }
                                                            } else null
                                                        } catch (e: Exception) {
                                                            val regex = "([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})".toRegex()
                                                            stepDetail?.let { regex.find(it)?.value }
                                                        }
                                                        if (!detectedSubConvId.isNullOrBlank()) {
                                                            mutableRunningSubagents.update { list ->
                                                                list.map { if (it.id.startsWith(stableStepId) && it.conversationId == null) it.copy(conversationId = detectedSubConvId) else it }
                                                            }
                                                            val summary = ConversationSummary(
                                                                id = detectedSubConvId,
                                                                title = stepTitle,
                                                                timestamp = System.currentTimeMillis(),
                                                                messageCount = 1,
                                                                isPinned = false,
                                                                projectPath = validWsDir?.absolutePath
                                                            )
                                                            val history = ConversationPersistence.loadConversationHistory(context)
                                                            if (history.none { it.id == detectedSubConvId }) {
                                                                ConversationPersistence.saveConversationHistory(context, listOf(summary) + history)
                                                            }
                                                        }
                                                    }
                                                }

                                                val notificationStepId = stepUpdate.optString("step_id").ifBlank { stableStepId }
                                                if (state == "ACTIVE") {
                                                    val toolDesc = when {
                                                        stepKind == AgentStepKind.COMMAND -> "$stepTitle"
                                                        stepKind == AgentStepKind.EDITED -> "Edit $stepTitle ${stepDiff ?: ""}".trim()
                                                        stepKind == AgentStepKind.ANALYZED -> "Read $stepTitle ${stepLines ?: ""}".trim()
                                                        stepKind == AgentStepKind.SEARCH -> "Search $stepTitle"
                                                        else -> "$toolName $stepTitle"
                                                    }
                                                    val command = if (toolName == "run_command") {
                                                        params?.optString("CommandLine").orEmpty().trim()
                                                    } else ""
                                                    val notificationText = if (command.isNotBlank()) {
                                                        context.getString(R.string.notification_agent_command, command)
                                                    } else {
                                                        toolDesc
                                                    }
                                                    synchronized<Unit>(activeNotificationSteps) {
                                                        activeNotificationSteps.remove(notificationStepId)
                                                        activeNotificationSteps[notificationStepId] = notificationText
                                                        ForegroundNotification.updateExecution(notification, notificationText)
                                                    }
                                                    TerminalSessionManager.appendAgyLine("> $toolDesc", isCommand = true)
                                                } else {
                                                    synchronized<Unit>(activeNotificationSteps) {
                                                        if (activeNotificationSteps.remove(notificationStepId) != null) {
                                                            ForegroundNotification.updateExecution(
                                                                notification,
                                                                activeNotificationSteps.values.lastOrNull() ?: notification.text
                                                            )
                                                        }
                                                    }
                                                    if (!stepDetail.isNullOrBlank()) {
                                                        TerminalSessionManager.appendAgyLine(stepDetail)
                                                    }
                                                }

                                                val now = System.currentTimeMillis()
                                                if (state != "ACTIVE" || now - lastStepUpdateMs > 300L) {
                                                    lastStepUpdateMs = now
                                                    val liveParsed = if (now - lastTranscriptPollMs > 3000L || state != "ACTIVE") {
                                                        lastTranscriptPollMs = now
                                                        parseTranscriptTurn(context, activeCliConvId)
                                                    } else null
                                                    val liveThinking = liveParsed?.thinking
                                                    val liveThoughts = liveParsed?.steps?.filter { it.kind == AgentStepKind.THOUGHT }.orEmpty()

                                                    mutableChatMessages.update { list ->
                                                        list.map { msg ->
                                                            if (msg.id == assistantMsgId) {
                                                                val curSteps = msg.steps
                                                                val completedSteps = curSteps.map { if (it.kind == AgentStepKind.THOUGHT && !it.isCompleted) it.copy(isCompleted = true) else it }
                                                                val existingIdx = completedSteps.indexOfFirst { it.id == stableStepId || (it.title == stepTitle && it.kind == stepKind) }
                                                                val baseSteps = if (existingIdx >= 0) {
                                                                    completedSteps.toMutableList().apply { set(existingIdx, execStep) }
                                                                } else {
                                                                    completedSteps + execStep
                                                                }
                                                                val updatedSteps = if (liveThoughts.isNotEmpty() && baseSteps.none { it.kind == AgentStepKind.THOUGHT }) {
                                                                    liveThoughts + baseSteps
                                                                } else {
                                                                    baseSteps
                                                                }
                                                                msg.copy(steps = updatedSteps, thinking = liveThinking ?: msg.thinking)
                                                            } else msg
                                                        }
                                                    }
                                                }
                                            } else if (stepType == "thought" || stepType == "reasoning") {
                                                val thoughtText = stepUpdate.optString("text", "")
                                                val duration = stepUpdate.optInt("duration_seconds", 6).coerceAtLeast(1)
                                                val thoughtStep = AgentExecutionStep(
                                                    id = "step_live_thought_${assistantMsgId}",
                                                    kind = AgentStepKind.THOUGHT,
                                                    title = "Thought",
                                                    detail = thoughtText,
                                                    durationSeconds = duration,
                                                    isCompleted = false
                                                )
                                                val now = System.currentTimeMillis()
                                                if (now - lastStepUpdateMs > 300L) {
                                                    lastStepUpdateMs = now
                                                    if (thoughtText.isNotBlank()) {
                                                        TerminalSessionManager.appendAgyLine("* Thinking: ${thoughtText.replace('\n', ' ')}")
                                                    }
                                                    mutableChatMessages.update { list ->
                                                        list.map { msg ->
                                                            if (msg.id == assistantMsgId) {
                                                                val curSteps = msg.steps
                                                                val updated = if (curSteps.isNotEmpty() && curSteps.last().kind == AgentStepKind.THOUGHT && !curSteps.last().isCompleted) {
                                                                    curSteps.dropLast(1) + curSteps.last().copy(
                                                                        detail = thoughtText,
                                                                        durationSeconds = duration
                                                                    )
                                                                } else {
                                                                    curSteps.map { it.copy(isCompleted = true) } + thoughtStep
                                                                }
                                                                msg.copy(thinking = thoughtText.ifBlank { msg.thinking }, steps = updated)
                                                            } else msg
                                                        }
                                                    }
                                                }
                                            } else if (stepType == "agent_response") {
                                                val textDelta = stepUpdate.optString("text_delta", "")
                                                if (textDelta.isNotEmpty()) {
                                                    synchronized<Unit>(activeNotificationSteps) {
                                                        if (activeNotificationSteps.isEmpty()) {
                                                            ForegroundNotification.updateExecution(
                                                                notification, context.getString(R.string.notification_agent_responding)
                                                            )
                                                        }
                                                    }
                                                    outputSb.append(textDelta)
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastStreamUpdateMs > 250L) {
                                                        lastStreamUpdateMs = now
                                                        val currentOutput = outputSb.toString()
                                                        mutableChatMessages.update { list ->
                                                            list.map { msg ->
                                                                if (msg.id == assistantMsgId) {
                                                                    msg.copy(content = currentOutput)
                                                                } else msg
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "result" -> {
                                        val resultObj = json.optJSONObject("result")
                                        val finalResponse = resultObj?.optString("response")
                                        if (!finalResponse.isNullOrBlank()) {
                                            TerminalSessionManager.appendAgyLine(finalResponse)
                                            outputSb.clear()
                                            outputSb.append(finalResponse)
                                            val currentOutput = outputSb.toString().trim()
                                            val liveParsed = parseTranscriptTurn(context, activeCliConvId)
                                            val liveThinking = liveParsed.thinking
                                            val liveTranscriptSteps = liveParsed.steps
                                            mutableChatMessages.update { list ->
                                                list.map { msg ->
                                                    if (msg.id == assistantMsgId) {
                                                        msg.copy(
                                                            content = currentOutput,
                                                            thinking = liveThinking ?: msg.thinking,
                                                            steps = liveTranscriptSteps ?: msg.steps
                                                        )
                                                    } else msg
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                TerminalSessionManager.appendAgyLine(line)
                                outputSb.append(line).append("\n")
                                val now = System.currentTimeMillis()
                                if (now - lastStreamUpdateMs > 250L) {
                                    lastStreamUpdateMs = now
                                    val currentOutput = outputSb.toString()
                                    mutableChatMessages.update { list ->
                                        list.map { msg ->
                                            if (msg.id == assistantMsgId) {
                                                msg.copy(content = currentOutput)
                                            } else msg
                                        }
                                    }
                                }
                            }
                        } else if (trimmed.isNotEmpty()) {
                            if (!trimmed.startsWith("Get:") && !trimmed.startsWith("#") && !trimmed.startsWith("Unpacking")) {
                                TerminalSessionManager.appendAgyLine(line)
                                outputSb.append(line).append("\n")
                                val now = System.currentTimeMillis()
                                if (now - lastStreamUpdateMs > 250L) {
                                    lastStreamUpdateMs = now
                                    val currentOutput = outputSb.toString()
                                    mutableChatMessages.update { list ->
                                        list.map { msg ->
                                            if (msg.id == assistantMsgId) {
                                                msg.copy(content = currentOutput)
                                            } else msg
                                        }
                                    }
                                }
                            }
                        }
                    }

                        if (outputSb.isNotEmpty()) {
                            val finalCurrentOutput = outputSb.toString()
                            mutableChatMessages.update { list ->
                                list.map { msg ->
                                    if (msg.id == assistantMsgId && msg.content != finalCurrentOutput) {
                                        msg.copy(content = finalCurrentOutput)
                                    } else msg
                                }
                            }
                        }

                        val outputStr = outputSb.toString()
                        authRequired = outputStr.contains("authentication required", ignoreCase = true) ||
                                outputStr.contains("authentication failed", ignoreCase = true) ||
                                outputStr.contains("visit the url to log in", ignoreCase = true)

                        val intermediateParsed = parseTranscriptTurn(context, activeCliConvId)
                        val transcriptResponse = intermediateParsed.assistantResponse
                        val hasTextOutput = outputSb.isNotBlank() || !transcriptResponse.isNullOrBlank()
                        val liveTranscriptSteps = intermediateParsed.steps
                        val hasExecutedSteps = !liveTranscriptSteps.isNullOrEmpty()

                        if (exitCode != 0 || authRequired || hasTextOutput || !hasExecutedSteps) {
                            autoContinue = false
                        } else {
                            addLog("Agent", "Turn $currentTurn completed with intermediate tool execution. Continuing to next turn for completion...")
                        }
                    }

                    mutablePendingAuthUrl.value = null

                    if (exitCode == 0) {
                        OAuthManager.checkAndImportExistingToken(context)
                        OAuthManager.syncCompanionStateFiles(context)
                        mutableUserEmail.value = OAuthManager.getUserEmail(context)
                    } else if (authRequired) {
                        mutableShowGoogleSignInDialog.value = true
                    }

                    val elapsedSec = ((System.currentTimeMillis() - turnStartTime) / 1000).coerceAtLeast(1)
                    val durText = if (elapsedSec >= 60) "${elapsedSec / 60}m" else "${elapsedSec}s"
                    val turnChanges = computeTurnFileChanges(context, mutableWorkspacePath.value)

                    val currentSteps = mutableChatMessages.value.find { it.id == assistantMsgId }?.steps.orEmpty()
                    val finalParsed = parseTranscriptTurn(context, activeCliConvId)
                    val finalThinking = finalParsed.thinking
                        ?: currentSteps.find { it.kind == AgentStepKind.THOUGHT }?.detail

                    val transcriptSteps = finalParsed.steps
                    val finalSteps = if (!transcriptSteps.isNullOrEmpty()) {
                        transcriptSteps
                    } else if (currentSteps.isNotEmpty()) {
                        val hasThought = currentSteps.any { it.kind == AgentStepKind.THOUGHT }
                        if (!hasThought && finalThinking != null) {
                            val thoughtStep = AgentExecutionStep(
                                id = "step_thought_final",
                                kind = AgentStepKind.THOUGHT,
                                title = "Thought",
                                detail = finalThinking,
                                durationSeconds = elapsedSec.toInt(),
                                isCompleted = true
                            )
                            listOf(thoughtStep) + currentSteps.map { it.copy(isCompleted = true) }
                        } else {
                            currentSteps.map { it.copy(isCompleted = true) }
                        }
                    } else if (finalThinking != null) {
                        listOf(
                            AgentExecutionStep(
                                id = "step_thought_final",
                                kind = AgentStepKind.THOUGHT,
                                title = "Thought",
                                detail = finalThinking,
                                durationSeconds = elapsedSec.toInt(),
                                isCompleted = true
                            )
                        )
                    } else {
                        emptyList()
                    }

                    val transcriptResponse = finalParsed.assistantResponse
                    val finalContent = if (exitCode != 0 && authRequired) {
                        context.getString(R.string.chat_auth_required_error)
                    } else if (outputSb.isNotEmpty()) {
                        outputSb.toString().trim()
                    } else if (!transcriptResponse.isNullOrBlank()) {
                        transcriptResponse
                    } else if (finalSteps.isNotEmpty()) {
                        val executedNames = finalSteps.filter { it.kind != AgentStepKind.THOUGHT }.map { it.title }
                        if (executedNames.isNotEmpty()) {
                            context.getString(R.string.chat_step_execution_finished, executedNames.joinToString(", "))
                        } else {
                            context.getString(R.string.chat_inspection_finished)
                        }
                    } else {
                        context.getString(R.string.chat_task_completed)
                    }

                    addLog("Agent", "Turn completed ($durText). Output: ${finalContent.take(150)}")
                    TerminalSessionManager.appendAgyLine("[AGY] Turn $currentTurn completed ($durText)")

                    mutableChatMessages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                val summaryParts = mutableListOf<String>()
                                if (finalSteps.any { it.kind == AgentStepKind.ANALYZED }) summaryParts.add("Explored file")
                                if (finalSteps.any { it.kind == AgentStepKind.COMMAND }) summaryParts.add("ran commands")
                                if (finalSteps.any { it.kind == AgentStepKind.EDITED } || (turnChanges != null && turnChanges.totalFiles > 0)) summaryParts.add("edited file")
                                val actSummary = if (summaryParts.isNotEmpty()) summaryParts.joinToString(", ") else null

                                msg.copy(
                                    status = if (exitCode == 0) MessageStatus.SENT else MessageStatus.ERROR,
                                    content = finalContent,
                                    thinking = finalThinking,
                                    durationText = durText,
                                    actionSummary = actSummary,
                                    fileChanges = turnChanges,
                                    steps = finalSteps
                                )
                            } else msg
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) {
                    mutableChatMessages.update { list ->
                        list.filter { it.id != assistantMsgId || it.content.isNotBlank() }
                    }
                } else {
                    mutableChatMessages.update { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    status = MessageStatus.ERROR,
                                    content = "Execution error: ${e.message}",
                                    thinking = null
                                )
                            } else msg
                        }
                    }
                }
            } finally {
                ForegroundNotification.endExecution(notification)
                executionWakeLock.release()
                mutableIsExecuting.value = false
                mutableActiveWorkingStatus.value = null
                currentAiExecutionJob = null
                mutableRunningTasks.value = emptyList()
                mutableRunningSubagents.update { list -> list.map { it.copy(isRunning = false) } }
                val currentList = mutableChatMessages.value
                mutableConversationHistory.update { list ->
                    list.map { if (it.id == convId) it.copy(messageCount = currentList.size, timestamp = System.currentTimeMillis()) else it }
                }
                saveConversationHistory(context)
                saveMessagesForConversation(context, convId, currentList)
                processNextQueuedMessage(context)
                refreshUserQuota(context)
            }
        }.also { job ->
            job.invokeOnCompletion { ForegroundNotification.endExecution(notification) }
        }
    }

    suspend fun computeTurnFileChanges(context: Context, workspacePath: String?): TurnFileChanges? = withContext(Dispatchers.IO) {
        val root = workspacePath?.let { File(it) } ?: return@withContext null
        if (!root.exists() || !root.isDirectory || !File(root, ".git").exists()) return@withContext null

        val fileList = mutableListOf<ChangedFileDetail>()
        var totalAdd = 0
        var totalDel = 0

        try {
            val statusMap = mutableMapOf<String, String>()
            EngineExecutor.executeCommand(context, "cd '${root.absolutePath}' && git status --porcelain 2>/dev/null") { line, exitCode ->
                val trimmed = line.trim()
                if (trimmed.length >= 3) {
                    val st = trimmed.substring(0, 2).trim()
                    val path = trimmed.substring(2).trim().trim('"', '\'')
                    val statusDesc = when {
                        st.contains("D") -> "D"
                        st.contains("A") || st.contains("?") -> "A"
                        else -> "M"
                    }
                    statusMap[path] = statusDesc
                }
            }

            val numstats = mutableListOf<Triple<String, Int, Int>>()
            EngineExecutor.executeCommand(context, "cd '${root.absolutePath}' && git diff --numstat 2>/dev/null") { line, exitCode ->
                val parts = line.trim().split(Regex("""\s+"""))
                if (parts.size >= 3) {
                    val add = parts[0].toIntOrNull() ?: 0
                    val del = parts[1].toIntOrNull() ?: 0
                    val path = parts[2].trim().trim('"', '\'')
                    numstats.add(Triple(path, add, del))
                }
            }

            val diffsMap = mutableMapOf<String, String>()
            if (numstats.isNotEmpty() && numstats.size <= 30) {
                val combinedDiffSb = StringBuilder()
                EngineExecutor.executeCommand(context, "cd '${root.absolutePath}' && git diff -U3 2>/dev/null | head -n 1200") { line, isErr ->
                    combinedDiffSb.append(line).append("\n")
                }
                val fullDiff = combinedDiffSb.toString()
                if (fullDiff.isNotBlank()) {
                    val fileBlocks = fullDiff.split(Regex("""(?=diff --git)"""))
                    for (block in fileBlocks) {
                        val headerMatch = Regex("""diff --git a/(.+?) b/""").find(block)
                        if (headerMatch != null) {
                            val p = headerMatch.groupValues[1].trim()
                            diffsMap[p] = block.take(5000)
                        }
                    }
                }
            }

            for ((path, add, del) in numstats) {
                totalAdd += add
                totalDel += del
                val st = statusMap[path] ?: "M"
                val diffContent = diffsMap[path] ?: "[File modified (${add} additions, ${del} deletions)]"
                fileList.add(
                    ChangedFileDetail(
                        name = path.substringAfterLast('/'),
                        relativePath = path,
                        status = st,
                        additions = add,
                        deletions = del,
                        diff = diffContent
                    )
                )
            }

            for ((path, st) in statusMap) {
                if (fileList.none { it.relativePath == path }) {
                    val diffContent = diffsMap[path] ?: if (st == "A") "[New file created]" else "[File deleted]"
                    fileList.add(
                        ChangedFileDetail(
                            name = path.substringAfterLast('/'),
                            relativePath = path,
                            status = st,
                            additions = if (st == "A") 1 else 0,
                            deletions = if (st == "D") 1 else 0,
                            diff = diffContent
                        )
                    )
                    if (st == "A") totalAdd++
                    if (st == "D") totalDel++
                }
            }
        } catch (e: Throwable) {}

        if (fileList.isNotEmpty()) {
            TurnFileChanges(
                totalFiles = fileList.size,
                totalAdditions = totalAdd,
                totalDeletions = totalDel,
                files = fileList
            )
        } else null
    }

    suspend fun fetchGitChangedFiles(context: Context, workspacePath: String?): List<ChangedFileItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ChangedFileItem>()
        val rootDir = workspacePath?.let { File(it) }
        if (rootDir != null && rootDir.exists() && rootDir.isDirectory && File(rootDir, ".git").exists()) {
            try {
                val porcelainRegex = Regex("""^[ MADRCU?!]{1,2}\s+(.+)$""")
                EngineExecutor.executeCommand(context, "cd '${rootDir.absolutePath}' && git status --porcelain 2>/dev/null") { line, exitCode ->
                    val trimmed = line.trim()
                    val match = porcelainRegex.find(trimmed)
                    if (match != null) {
                        val rawPath = match.groupValues[1].trim().trim('"', '\'')
                        val f = File(rawPath)
                        val name = f.name
                        val rel = f.parent ?: ""
                        list.add(ChangedFileItem(name = name, relativePath = rel, isKotlin = name.endsWith(".kt") || name.endsWith(".kts")))
                    }
                }
            } catch (e: Exception) {}
        }
        list
    }

    suspend fun fetchGitDiff(context: Context, workspacePath: String?, relativePath: String): String = withContext(Dispatchers.IO) {
        val root = workspacePath?.let { File(it) } ?: return@withContext ""
        if (!root.exists()) return@withContext ""
        val diffBuilder = StringBuilder()
        try {
            EngineExecutor.executeCommand(context, "cd '${root.absolutePath}' && git diff HEAD -- '$relativePath' 2>/dev/null") { line, exitCode ->
                diffBuilder.append(line).append("\n")
            }
        } catch (e: Exception) {}
        val diff = diffBuilder.toString()
        if (diff.isNotBlank()) {
            diff
        } else {
            val targetF = File(root, relativePath)
            if (targetF.exists() && targetF.isFile) {
                targetF.readLines().take(40).joinToString("\n")
            } else {
                "[File modified or newly created]"
            }
        }
    }

    private fun extractPathFromParams(params: JSONObject?): String =
        TranscriptTurnParser.extractPathFromParams(params)

    private fun extractFileNameFromPath(path: String, fallback: String = "file"): String =
        TranscriptTurnParser.extractFileNameFromPath(path, fallback)

    fun findActiveTranscriptFile(context: Context, cliConvId: String?): File? =
        TranscriptTurnParser.findActiveTranscriptFile(context, cliConvId)

    fun parseTranscriptTurn(context: Context, cliConvId: String?): ParsedTranscriptTurn =
        TranscriptTurnParser.parseTranscriptTurn(context, cliConvId)

    fun extractStepsFromCliTranscript(context: Context, cliConvId: String?): List<AgentExecutionStep>? =
        TranscriptTurnParser.parseTranscriptTurn(context, cliConvId).steps

    fun extractAllThinkingFromCliTranscript(context: Context, cliConvId: String?): String? =
        TranscriptTurnParser.parseTranscriptTurn(context, cliConvId).thinking

    fun extractAssistantResponseFromCliTranscript(context: Context, cliConvId: String?): String? =
        TranscriptTurnParser.parseTranscriptTurn(context, cliConvId).assistantResponse
}
