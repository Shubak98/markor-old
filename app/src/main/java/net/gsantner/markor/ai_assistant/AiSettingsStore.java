package net.gsantner.markor.ai_assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AiSettingsStore {
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final String PREFERENCES_NAME = "markor_ai_assistant_prefs";
    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_GEMINI_MODEL = "gemini_model";
    private static final String KEY_GROQ_API_KEY = "groq_api_key";
    private static final String KEY_GROQ_MODEL = "groq_model";
    private static final String KEY_OPENROUTER_API_KEY = "openrouter_api_key";
    private static final String KEY_OPENROUTER_MODEL = "openrouter_model";
    private static final String KEY_CUSTOM_BASE_URL = "custom_base_url";
    private static final String KEY_CUSTOM_API_KEY = "custom_api_key";
    private static final String KEY_CUSTOM_MODEL = "custom_model";
    private static final String KEY_PRIMARY_PROVIDER = "primary_provider";
    private static final String KEY_AUTO_FAILOVER = "auto_failover_enabled";
    private static final String KEY_TEACHER_STYLE = "teacher_style";
    private static final String KEY_GITHUB_PAT = "github_pat";
    private static final String KEY_GITHUB_REPO = "github_repo";
    private static final String KEY_GITHUB_BRANCH = "github_branch";
    private static final String KEY_GITHUB_LOCAL_ROOT = "github_local_root";
    private static final String KEY_GITHUB_LAST_SYNC_MS = "github_last_sync_ms";

    private final SharedPreferences preferences;

    public AiSettingsStore(final Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static String exportSettingsToJson(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required to export AI settings.");
        }
        final Context appContext = context.getApplicationContext();
        return exportSettingsToJson(appContext.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE).getAll());
    }

    static String exportSettingsToJson(final Map<String, ?> savedSettings) {
        if (savedSettings == null) {
            throw new IllegalArgumentException("AI settings are not available for export.");
        }
        final StringBuilder json = new StringBuilder("{\n");
        boolean first = true;
        first = appendJsonEntry(json, "schema_version", "1", first);
        first = appendExportString(json, savedSettings, KEY_PRIMARY_PROVIDER, "gemini", first);
        first = appendExportBoolean(json, savedSettings, KEY_AUTO_FAILOVER, true, first);
        first = appendExportString(json, savedSettings, KEY_GEMINI_API_KEY, "", first);
        first = appendExportString(json, savedSettings, KEY_GEMINI_MODEL, DEFAULT_MODEL, first);
        first = appendExportString(json, savedSettings, KEY_GROQ_API_KEY, "", first);
        first = appendExportString(json, savedSettings, KEY_GROQ_MODEL,
                AiProviderRouter.Provider.GROQ.getDefaultModel(), first);
        first = appendExportString(json, savedSettings, KEY_OPENROUTER_API_KEY, "", first);
        first = appendExportString(json, savedSettings, KEY_OPENROUTER_MODEL,
                AiProviderRouter.Provider.OPENROUTER.getDefaultModel(), first);
        first = appendExportString(json, savedSettings, KEY_CUSTOM_BASE_URL, "", first);
        first = appendExportString(json, savedSettings, KEY_CUSTOM_API_KEY, "", first);
        first = appendExportString(json, savedSettings, KEY_CUSTOM_MODEL,
                AiProviderRouter.Provider.CUSTOM.getDefaultModel(), first);
        first = appendExportString(json, savedSettings, KEY_TEACHER_STYLE,
                AiVoiceTeacherHelper.DEFAULT_TEACHER_STYLE.getPreferenceValue(), first);
        first = appendExportString(json, savedSettings, KEY_GITHUB_PAT, "", first);
        first = appendExportString(json, savedSettings, KEY_GITHUB_REPO, "", first);
        first = appendExportString(json, savedSettings, KEY_GITHUB_BRANCH, "main", first);
        appendExportString(json, savedSettings, KEY_GITHUB_LOCAL_ROOT, "", first);
        return json.append("\n}").toString();
    }

    public static int importSettingsFromJson(final Context context, final String jsonText) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required to import AI settings.");
        }
        final Context appContext = context.getApplicationContext();
        final SharedPreferences preferences = appContext.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        final Map<String, Object> importedSettings = parseSettingsJson(jsonText);
        final SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, Object> entry : importedSettings.entrySet()) {
            if (entry.getValue() instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) entry.getValue());
            } else {
                editor.putString(entry.getKey(), (String) entry.getValue());
            }
        }
        if (!editor.commit()) {
            throw new IllegalStateException("Could not save the imported AI settings.");
        }
        return importedSettings.size();
    }

    static int importSettingsFromJson(final Map<String, Object> settings,
            final String jsonText) {
        if (settings == null) {
            throw new IllegalArgumentException("AI settings storage is not available.");
        }
        final Map<String, Object> importedSettings = parseSettingsJson(jsonText);
        settings.putAll(importedSettings);
        return importedSettings.size();
    }

    private static Map<String, Object> parseSettingsJson(final String jsonText) {
        if (jsonText == null) {
            throw new IllegalArgumentException("The AI settings backup is empty.");
        }
        final Object parsed;
        try {
            parsed = new JsonReader(jsonText).parseDocument();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "The file is not valid AI settings JSON: " + exception.getMessage(), exception);
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException(
                    "The file is not valid AI settings JSON: expected an object.");
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> json = (Map<String, Object>) parsed;
        if (json.containsKey("schema_version")) {
            final Object version = json.get("schema_version");
            final double versionNumber = version instanceof Number
                    ? ((Number) version).doubleValue() : Double.NaN;
            if (versionNumber < 0 || versionNumber > 1
                    || versionNumber != Math.rint(versionNumber)) {
                throw new IllegalArgumentException(
                        "This AI settings backup uses an unsupported schema version.");
            }
        }

        final Map<String, Object> imported = new LinkedHashMap<>();
        for (String key : new String[]{KEY_GEMINI_API_KEY, KEY_GEMINI_MODEL,
                KEY_GROQ_API_KEY, KEY_GROQ_MODEL, KEY_OPENROUTER_API_KEY,
                KEY_OPENROUTER_MODEL, KEY_CUSTOM_BASE_URL, KEY_CUSTOM_API_KEY,
                KEY_CUSTOM_MODEL, KEY_GITHUB_PAT, KEY_GITHUB_REPO, KEY_GITHUB_BRANCH,
                KEY_GITHUB_LOCAL_ROOT}) {
            putImportedString(json, imported, key);
        }
        putImportedString(json, imported, KEY_PRIMARY_PROVIDER);
        if (imported.containsKey(KEY_PRIMARY_PROVIDER)
                && AiProviderRouter.Provider.fromId(
                        (String) imported.get(KEY_PRIMARY_PROVIDER)) == null) {
            throw new IllegalArgumentException("The AI settings backup has an unknown primary provider.");
        }
        putImportedString(json, imported, KEY_TEACHER_STYLE);
        if (imported.containsKey(KEY_TEACHER_STYLE)
                && !isKnownTeacherStyle((String) imported.get(KEY_TEACHER_STYLE))) {
            throw new IllegalArgumentException("The AI settings backup has an unknown teacher style.");
        }
        if (json.containsKey(KEY_AUTO_FAILOVER)) {
            final Object value = json.get(KEY_AUTO_FAILOVER);
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "The auto-failover setting must be true or false.");
            }
            imported.put(KEY_AUTO_FAILOVER, value);
        }
        if (imported.isEmpty()) {
            throw new IllegalArgumentException(
                    "The JSON file does not contain any recognized AI settings.");
        }
        return imported;
    }

    private static void putImportedString(final Map<String, Object> json,
            final Map<String, Object> imported, final String key) {
        if (!json.containsKey(key)) {
            return;
        }
        final Object value = json.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    "The " + key + " setting must be a string.");
        }
        imported.put(key, value);
    }

    private static boolean isKnownTeacherStyle(final String value) {
        for (AiVoiceTeacherHelper.TeacherStyle style
                : AiVoiceTeacherHelper.TeacherStyle.values()) {
            if (style.getPreferenceValue().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean appendExportString(final StringBuilder json,
            final Map<String, ?> savedSettings, final String key, final String defaultValue,
            final boolean first) {
        final Object value = savedSettings.containsKey(key) ? savedSettings.get(key) : defaultValue;
        if (!(value instanceof String)) {
            throw new IllegalStateException("The saved " + key + " setting is not a string.");
        }
        return appendJsonEntry(json, key, quoteJson((String) value), first);
    }

    private static boolean appendExportBoolean(final StringBuilder json,
            final Map<String, ?> savedSettings, final String key, final boolean defaultValue,
            final boolean first) {
        final Object value = savedSettings.containsKey(key) ? savedSettings.get(key) : defaultValue;
        if (!(value instanceof Boolean)) {
            throw new IllegalStateException("The saved " + key + " setting is not a boolean.");
        }
        return appendJsonEntry(json, key, value.toString(), first);
    }

    private static boolean appendJsonEntry(final StringBuilder json, final String key,
            final String encodedValue, final boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append("\n  ").append(quoteJson(key)).append(": ").append(encodedValue);
        return false;
    }

    private static String quoteJson(final String value) {
        final StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '"':
                    quoted.append("\\\"");
                    break;
                case '\\':
                    quoted.append("\\\\");
                    break;
                case '\b':
                    quoted.append("\\b");
                    break;
                case '\f':
                    quoted.append("\\f");
                    break;
                case '\n':
                    quoted.append("\\n");
                    break;
                case '\r':
                    quoted.append("\\r");
                    break;
                case '\t':
                    quoted.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        quoted.append(String.format(java.util.Locale.ROOT, "\\u%04x",
                                (int) character));
                    } else {
                        quoted.append(character);
                    }
                    break;
            }
        }
        return quoted.append('"').toString();
    }

    private static final class JsonReader {
        private static final int MAX_NESTING_DEPTH = 64;
        private final String text;
        private int position;
        private int depth;

        private JsonReader(final String text) {
            this.text = text;
        }

        private Object parseDocument() {
            final Object value = parseValue();
            skipWhitespace();
            if (position != text.length()) {
                throw invalidJson("unexpected trailing content");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (position >= text.length()) {
                throw invalidJson("expected a value");
            }
            final char character = text.charAt(position);
            if (character == '{') {
                enterContainer();
                try {
                    return parseObject();
                } finally {
                    depth--;
                }
            }
            if (character == '[') {
                enterContainer();
                try {
                    return parseArray();
                } finally {
                    depth--;
                }
            }
            if (character == '"') {
                return parseString();
            }
            if (character == 't') {
                return parseLiteral("true", Boolean.TRUE);
            }
            if (character == 'f') {
                return parseLiteral("false", Boolean.FALSE);
            }
            if (character == 'n') {
                return parseLiteral("null", null);
            }
            if (character == '-' || isDigit(character)) {
                return parseNumber();
            }
            throw invalidJson("unexpected character");
        }

        private Map<String, Object> parseObject() {
            position++;
            skipWhitespace();
            final Map<String, Object> values = new LinkedHashMap<>();
            if (take('}')) {
                return values;
            }
            while (true) {
                skipWhitespace();
                if (position >= text.length() || text.charAt(position) != '"') {
                    throw invalidJson("expected an object key");
                }
                final String key = parseString();
                skipWhitespace();
                expect(':');
                values.put(key, parseValue());
                skipWhitespace();
                if (take('}')) {
                    return values;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            position++;
            skipWhitespace();
            final List<Object> values = new ArrayList<>();
            if (take(']')) {
                return values;
            }
            while (true) {
                values.add(parseValue());
                skipWhitespace();
                if (take(']')) {
                    return values;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            final StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                final char character = text.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character < 0x20) {
                    throw invalidJson("unescaped control character in string");
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (position >= text.length()) {
                    throw invalidJson("unfinished string escape");
                }
                final char escape = text.charAt(position++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escape);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        value.append(parseUnicodeEscape());
                        break;
                    default:
                        throw invalidJson("invalid string escape");
                }
            }
            throw invalidJson("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > text.length()) {
                throw invalidJson("unfinished Unicode escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                final int digit = hexDigit(text.charAt(position++));
                if (digit < 0) {
                    throw invalidJson("invalid Unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object parseNumber() {
            final int start = position;
            take('-');
            if (take('0')) {
                if (position < text.length() && isDigit(text.charAt(position))) {
                    throw invalidJson("leading zero in number");
                }
            } else {
                requireDigit();
                while (position < text.length() && isDigit(text.charAt(position))) {
                    position++;
                }
            }
            if (take('.')) {
                requireDigit();
                while (position < text.length() && isDigit(text.charAt(position))) {
                    position++;
                }
            }
            if (take('e') || take('E')) {
                if (!take('+')) {
                    take('-');
                }
                requireDigit();
                while (position < text.length() && isDigit(text.charAt(position))) {
                    position++;
                }
            }
            try {
                return new BigDecimal(text.substring(start, position));
            } catch (NumberFormatException exception) {
                throw invalidJson("invalid number");
            }
        }

        private Object parseLiteral(final String literal, final Object value) {
            if (!text.regionMatches(position, literal, 0, literal.length())) {
                throw invalidJson("invalid literal");
            }
            position += literal.length();
            return value;
        }

        private void requireDigit() {
            if (position >= text.length() || !isDigit(text.charAt(position))) {
                throw invalidJson("expected a digit");
            }
            position++;
        }

        private boolean take(final char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(final char expected) {
            if (!take(expected)) {
                throw invalidJson("expected '" + expected + "'");
            }
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                final char character = text.charAt(position);
                if (character != ' ' && character != '\n'
                        && character != '\r' && character != '\t') {
                    return;
                }
                position++;
            }
        }

        private IllegalArgumentException invalidJson(final String reason) {
            return new IllegalArgumentException(
                    reason + " at character " + position + ".");
        }

        private void enterContainer() {
            depth++;
            if (depth > MAX_NESTING_DEPTH) {
                throw invalidJson("maximum nesting depth exceeded");
            }
        }

        private static boolean isDigit(final char character) {
            return character >= '0' && character <= '9';
        }

        private static int hexDigit(final char character) {
            if (character >= '0' && character <= '9') {
                return character - '0';
            }
            if (character >= 'a' && character <= 'f') {
                return character - 'a' + 10;
            }
            if (character >= 'A' && character <= 'F') {
                return character - 'A' + 10;
            }
            return -1;
        }
    }

    public String getApiKey() {
        return getProviderApiKey(AiProviderRouter.Provider.GEMINI);
    }

    public String getModel() {
        return getProviderModel(AiProviderRouter.Provider.GEMINI);
    }

    public void save(final String apiKey, final String model) {
        saveProvider(AiProviderRouter.Provider.GEMINI, apiKey, model, "");
    }

    public String getPrimaryProvider() {
        final String value = preferences.getString(KEY_PRIMARY_PROVIDER, "gemini");
        return AiProviderRouter.Provider.fromId(value) == null ? "gemini" : value;
    }

    public boolean isAutoFailoverEnabled() {
        return preferences.getBoolean(KEY_AUTO_FAILOVER, true);
    }

    public AiVoiceTeacherHelper.TeacherStyle getTeacherStyle() {
        return resolveTeacherStyle(preferences.getString(KEY_TEACHER_STYLE,
                AiVoiceTeacherHelper.DEFAULT_TEACHER_STYLE.getPreferenceValue()));
    }

    public void saveTeacherStyle(final AiVoiceTeacherHelper.TeacherStyle style) {
        if (style == null) {
            throw new IllegalArgumentException("Teacher style cannot be null.");
        }
        preferences.edit().putString(KEY_TEACHER_STYLE, style.getPreferenceValue()).apply();
    }

    static AiVoiceTeacherHelper.TeacherStyle resolveTeacherStyle(final String savedValue) {
        return AiVoiceTeacherHelper.TeacherStyle.fromPreferenceValue(savedValue);
    }

    public void savePreferences(final String primaryProvider, final boolean autoFailoverEnabled) {
        final AiProviderRouter.Provider provider = AiProviderRouter.Provider.fromId(primaryProvider);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported AI provider: " + primaryProvider);
        }
        preferences.edit()
                .putString(KEY_PRIMARY_PROVIDER, provider.getId())
                .putBoolean(KEY_AUTO_FAILOVER, autoFailoverEnabled)
                .apply();
    }

    public String getProviderApiKey(final AiProviderRouter.Provider provider) {
        return trim(preferences.getString(apiKeyKey(provider), ""));
    }

    public String getProviderModel(final AiProviderRouter.Provider provider) {
        return preferences.getString(modelKey(provider), provider.getDefaultModel());
    }

    public String getProviderBaseUrl(final AiProviderRouter.Provider provider) {
        if (provider == AiProviderRouter.Provider.GROQ
                || provider == AiProviderRouter.Provider.OPENROUTER) {
            return provider.getDefaultBaseUrl();
        }
        if (provider == AiProviderRouter.Provider.CUSTOM) {
            return preferences.getString(KEY_CUSTOM_BASE_URL, "");
        }
        return "";
    }

    public void saveProvider(final AiProviderRouter.Provider provider, final String apiKey,
            final String model, final String baseUrl) {
        final String modelValue = trim(model).isEmpty()
                ? provider.getDefaultModel() : trim(model);
        final SharedPreferences.Editor editor = preferences.edit()
                .putString(apiKeyKey(provider), trim(apiKey))
                .putString(modelKey(provider), modelValue);
        if (provider == AiProviderRouter.Provider.CUSTOM) {
            editor.putString(KEY_CUSTOM_BASE_URL, trim(baseUrl));
        }
        editor.apply();
    }

    public List<AiProviderRouter.ProviderConfig> getProviderConfigs() {
        final List<AiProviderRouter.ProviderConfig> providers = new ArrayList<>();
        for (AiProviderRouter.Provider provider : AiProviderRouter.Provider.values()) {
            providers.add(new AiProviderRouter.ProviderConfig(provider,
                    getProviderApiKey(provider), getProviderModel(provider), getProviderBaseUrl(provider)));
        }
        return providers;
    }

    public boolean hasConfiguredProviders() {
        for (AiProviderRouter.Provider provider : AiProviderRouter.Provider.values()) {
            if (!getProviderApiKey(provider).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public String getGitHubPat() {
        return trim(preferences.getString(KEY_GITHUB_PAT, ""));
    }

    public String getGitHubRepo() {
        return trim(preferences.getString(KEY_GITHUB_REPO, ""));
    }

    public String getGitHubBranch() {
        return trim(preferences.getString(KEY_GITHUB_BRANCH, "main"));
    }

    public String getGitHubLocalRoot() {
        return trim(preferences.getString(KEY_GITHUB_LOCAL_ROOT, ""));
    }

    public void saveGitHubLocalRootIfEmpty(final String localRoot) {
        if (getGitHubLocalRoot().isEmpty() && !trim(localRoot).isEmpty()) {
            preferences.edit().putString(KEY_GITHUB_LOCAL_ROOT, trim(localRoot)).apply();
        }
    }

    public long getGitHubLastSyncMs() {
        return preferences.getLong(KEY_GITHUB_LAST_SYNC_MS, 0L);
    }

    public void saveGitHubSyncSettings(final String pat, final String repo,
            final String branch, final String localRoot) {
        preferences.edit()
                .putString(KEY_GITHUB_PAT, trim(pat))
                .putString(KEY_GITHUB_REPO, trim(repo))
                .putString(KEY_GITHUB_BRANCH, trim(branch).isEmpty() ? "main" : trim(branch))
                .putString(KEY_GITHUB_LOCAL_ROOT, trim(localRoot))
                .apply();
    }

    public void saveGitHubLastSyncMs(final long timestampMs) {
        preferences.edit().putLong(KEY_GITHUB_LAST_SYNC_MS, timestampMs).apply();
    }

    private static String apiKeyKey(final AiProviderRouter.Provider provider) {
        switch (provider) {
            case GEMINI:
                return KEY_GEMINI_API_KEY;
            case GROQ:
                return KEY_GROQ_API_KEY;
            case OPENROUTER:
                return KEY_OPENROUTER_API_KEY;
            case CUSTOM:
                return KEY_CUSTOM_API_KEY;
            default:
                throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        }
    }

    private static String modelKey(final AiProviderRouter.Provider provider) {
        switch (provider) {
            case GEMINI:
                return KEY_GEMINI_MODEL;
            case GROQ:
                return KEY_GROQ_MODEL;
            case OPENROUTER:
                return KEY_OPENROUTER_MODEL;
            case CUSTOM:
                return KEY_CUSTOM_MODEL;
            default:
                throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        }
    }

    private static String trim(final String value) {
        return value == null ? "" : value.trim();
    }
}
