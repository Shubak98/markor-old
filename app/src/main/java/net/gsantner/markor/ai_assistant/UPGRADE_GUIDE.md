# AI Assistant portability

## Add the main-screen overflow shortcuts

Import `AiAssistantModule` in `MainActivity.java`, add the field to the activity, and
initialize it in `onCreate` after `_cu` is initialized. `MainActivity` implements
`ActivityResultCaller`, so the assistant's voice and file-picker launchers are registered
before the activity is started:

```java
import net.gsantner.markor.ai_assistant.AiAssistantModule;

private AiAssistantModule _aiAssistantModule;

_aiAssistantModule = new AiAssistantModule(this, this,
        file -> DocumentActivity.launch(MainActivity.this, file, false, null));
```

Add the AI Tasks ID (`Menu.FIRST + 42`) inside the marked hook beside the two
existing IDs. Add these marked blocks to `onOptionsItemSelected` and
`onCreateOptionsMenu`:

```java
// >>> AI_ASSISTANT_HOOK_START
if (item.getItemId() == MENU_AI_ASSISTANT) {
    _aiAssistantModule.openMainAssistantDialog();
    return true;
}
if (item.getItemId() == MENU_GITHUB_SYNC) {
    _aiAssistantModule.openGitHubSyncDialog();
    return true;
}
if (item.getItemId() == MENU_AI_TASKS) {
    _aiAssistantModule.openTodoAssistantDialog();
    return true;
}
// <<< AI_ASSISTANT_HOOK_END
```

```java
// >>> AI_ASSISTANT_HOOK_START
final MenuItem aiAssistantItem =
        menu.add(Menu.NONE, MENU_AI_ASSISTANT, Menu.NONE, "✦ AI Assistant");
aiAssistantItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
final MenuItem githubSyncItem =
        menu.add(Menu.NONE, MENU_GITHUB_SYNC, Menu.NONE, "☁ GitHub Sync");
githubSyncItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
final MenuItem aiTasksItem =
        menu.add(Menu.NONE, MENU_AI_TASKS, Menu.NONE, "📋 AI Tasks");
aiTasksItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
// <<< AI_ASSISTANT_HOOK_END
```

The no-file assistant entry still supports Folder Search, New Note, the global
Mnemonics Vault, settings, and GitHub Sync. Edit, Jump, and note-based study modes
explain that a note must be open. An unset sync folder always resolves to Markor's
Notebook root, and the Sync dialog displays the resolved path.

## Copy the module

Copy the entire `app/src/main/java/net/gsantner/markor/ai_assistant/` folder into the same path in the new Markor repository. No resource, XML, Gradle, or settings-file changes are required. The folder now includes large-note context selection, gallery image encoding and vision requests, tutor and quiz dialogs, Anki CSV generation, and Mermaid insertion.

The main assistant dialog also supports an optional Markdown heading scope shared by
its tabs and tools. Scope is cleared by tapping its chip; without a selected heading,
requests continue to use the whole note. AI output previews include a
Refine / Edit action for AI follow-ups or direct manual edits before applying, saving,
or replaying the result.

## Paste the hook

In the new `DocumentEditAndViewFragment.java`, add the field to the fragment class and paste the initialization inside `onCreate`, directly after `super.onCreate(savedInstanceState)`. This registers the voice and image-picker result launchers before the fragment is started:

```java
private net.gsantner.markor.ai_assistant.AiAssistantModule aiAssistantModule;

@Override
public void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // >>> AI_ASSISTANT_HOOK_START
    aiAssistantModule = new net.gsantner.markor.ai_assistant.AiAssistantModule(this);
    // <<< AI_ASSISTANT_HOOK_END
    // Keep the new version's existing onCreate implementation here.
}
```

In `onCreateOptionsMenu`, add the following after the editor menu is created. Keep the existing `isText` variable or replace it with an equivalent text-document check:

```java
// >>> AI_ASSISTANT_HOOK_START
final MenuItem aiEditItem = menu.add(Menu.NONE, Menu.NONE, Menu.NONE, "AI Edit");
aiEditItem.setVisible(isText && !_isPreviewVisible);
aiEditItem.setOnMenuItemClickListener(item -> {
    aiAssistantModule.openEditorDialog(_hlEditor);
    return true;
});
// <<< AI_ASSISTANT_HOOK_END
```

In `onViewCreated`, after the initial call that selects edit or preview mode and after `_hlEditor` has been assigned, attach the floating button and provide the save/preview callback:

```java
// >>> AI_ASSISTANT_HOOK_START
if (!_document.isBinaryFileNoTextLoading()) {
    aiAssistantModule.attachFloatingButton(view, _hlEditor, _document.file,
            (target, result) -> {
                if (_isPreviewVisible && _webView != null) {
                    final String javascript = "(function(){var t="
                            + org.json.JSONObject.quote(target)
                            + ";var n=t.replace(/^#{1,6}\\s+/, '').trim();"
                            + "var e=document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,th');"
                            + "for(var i=0;i<e.length;i++){var x=(e[i].innerText||e[i].textContent||'').trim();"
                            + "if(x===t||x===n){e[i].scrollIntoView({behavior:'smooth',block:'center'});return true;}}"
                            + "return false;})()";
                    _webView.evaluateJavascript(javascript,
                            value -> result.onResult("true".equals(value)));
                } else {
                    final int index = _hlEditor.getText().toString().indexOf(target);
                    if (index >= 0) {
                        _hlEditor.requestFocus();
                        _hlEditor.setSelection(index);
                        _hlEditor.bringPointIntoView(index);
                    }
                    result.onResult(index >= 0);
                }
            },
            file -> net.gsantner.markor.activity.DocumentActivity.launch(
                    getActivity(), file, null, null),
            () -> {
                if (saveDocument(false) && _isPreviewVisible) {
                    updateViewModeText();
                }
            });
}
aiAssistantModule.setViewModeVisible(_isPreviewVisible);
// <<< AI_ASSISTANT_HOOK_END
```

In the central `setViewModeVisibility(boolean show, boolean animate)` method, update the floating button immediately after `_isPreviewVisible` is assigned. This keeps it visible in preview mode and hidden in edit mode:

```java
_isPreviewVisible = show;
// >>> AI_ASSISTANT_HOOK_START
aiAssistantModule.setViewModeVisible(show);
// <<< AI_ASSISTANT_HOOK_END
```

The module constructor registers both the voice-recognition launcher and the Gallery/Files image picker before the fragment is started. No extra fragment lines are needed for the image picker. The floating button and menu item open the same multi-mode AI Assistant dialog. The `attachFloatingButton` hook passes the current document `File`, a callback for editor/WebView section jumps, and an `openFile(File)` callback. Applying a diff or appending an answer updates `_hlEditor`, saves the document, and refreshes the WebView preview when view mode is active. The assistant code and its tests remain self-contained in `ai_assistant/`.

## Pin / Unpin browser hooks

Copy `AiPinnedStore.java` and its unit test with the `ai_assistant/` package.
The store syncs notebook-relative paths in `ai_pinned.json` at the notebook
root, with one-time migration from `pinned_paths` and SharedPreferences fallback
for paths outside the notebook. The virtual folder is only a reserved `File`
path; no directory is created, so GitHub Sync never sees a Pinned folder.

This checkout calls the browser classes `GsFileBrowserListAdapter.java` and
`GsFileBrowserFragment.java` (not `FilesystemViewerAdapter` or
`FilesystemViewerFragment`). Keep the marked hooks when porting the feature:

* `GsFileBrowserListAdapter.java`
  * Add the store/root/sentinel fields beside the other adapter members, then
    initialize them in the constructor immediately after `_context = context`.
  * In `updateVirtualFolders()`, after the existing Favourites mapping, map the
    sentinel only when `_dopt.rootFolder` is the Notebook root and the reserved
    path does not exist on disk.
  * In `onBindViewHolder()`, replace the sentinel's display title with
    `📌 Pinned (N)` and apply `AiUiTheme.ACCENT` to its title and icon.
  * In `_loadFolder()`, after the Favourites listing branch, populate the
    sentinel view from `getPinnedFiles()`. After sorting, insert the sentinel
    at index `0` of `newData`, making it the first real Notebook-root entry
    (the existing `..` parent row, when present, stays ahead of it). Exclude it
    from `/storage`'s virtual-root list.
  * In `selectAll()` and `toggleSelection()`, reject the sentinel. In
    `isFileWriteable()`, report it as non-writable. In `getCurrentParent()`,
    return the Notebook root for the sentinel. Include it in static
    `isVirtualFolder()` and exclude it from `isCurrentFolderSortable()`.
    `goBack()` also falls back to the Notebook root when the sentinel was
    restored directly with no adapter back-stack entry.
    `getSelectableItemCount()` excludes the parent and injected Pinned row so
    the existing “Select all” menu state remains accurate.
    `refreshPinnedEntry()` rebinds the root row after pin changes so its count
    updates even though the root's file list itself is unchanged.
* `GsFileBrowserFragment.java`
  * Initialize the store in `onViewCreated()` immediately after
    `_appSettings = ApplicationObject.settings()`.
  * In `onCreateOptionsMenu()`, add the action immediately after inflating
    `R.menu.filesystem__menu`. Update its title and visibility in
    `updateMenuItems()` alongside the other selection actions; handle its ID in
    `onOptionsItemSelected()` beside the Favourite cases. The handler pins or
    unpins the selected real paths, displays the toast, clears selection, and
    reloads the current folder.
* `MainActivity.java`
  * In `getFileBrowserTitle()`, immediately after reading the current folder
    from `_notebook`, recognize the sentinel and display `📌 Pinned` instead
    of its reserved path name. Existing fragment Back handling and the
    adapter's parent row navigate back to the Notebook root.

The inserted browser code is delimited by
`// >>> AI_ASSISTANT_HOOK_START` and `// <<< AI_ASSISTANT_HOOK_END`. Preserve
those markers and avoid creating the sentinel path on disk.

## AI Tasks (`todo.txt`) assistant

Copy `AiTodoAssistantHelper.java` and `AiTodoAssistantDialog.java` together
with their unit test under `app/src/test/java/net/gsantner/markor/ai_assistant/`.
The helper reads Markor's configured `AppSettings.get().getTodoFile()`,
parses todo.txt priority, completion, `due:YYYY-MM-DD`, and `ref:` fields,
and validates linked Markdown paths against the canonical Notebook root.
Date filters and prompts use the device's local date; prompts include the
English weekday and the shared Default English Output Rule.

In `AiAssistantModule.java`, keep the `tasks` session key mapping for the
`AI Tasks` dialog label, the `openTodoAssistantDialog()` entry point, and the
`previewTodoFileChanges()` path. Task edits and overdue-date shifts use the
existing compact Diff Preview, verify that the source file has not changed,
write only after Apply, and refresh a currently open todo editor.
`AiTodoAssistantDialog` uses `AiProviderRouter`, so the configured 429/413
provider failover behavior remains centralized in the router. `AiUiTheme.java`
recognizes `AI Tasks` as a session tool, supplying the standard `⇄ Tools` and
minimize actions.

In `MainActivity.java`, use the existing `AI_ASSISTANT_HOOK` blocks:

* Add `MENU_AI_TASKS = Menu.FIRST + 42` beside the AI Assistant and GitHub Sync
  IDs.
* In `onOptionsItemSelected()`, handle `MENU_AI_TASKS` by calling
  `_aiAssistantModule.openTodoAssistantDialog()`; the menu action also supplies
  a bridge to read and refresh the Todo tab when `todo.txt` is already open.
* In `onCreateOptionsMenu()`, add one `"📋 AI Tasks"` item with ID
  `MENU_AI_TASKS` and `MenuItem.SHOW_AS_ACTION_NEVER`.
* Keep the `"📋 Tasks"` chip in the Main AI Hub wired to the same module entry
  point. No layout, XML, manifest, or Gradle changes are needed.

The built-in To-Do tab is a `DocumentEditAndViewFragment` opened on
`AppSettings.getTodoFile()`. Its crimson `AI` button opens AI Tasks directly,
raised above the main-screen add FAB area; other document buttons keep opening
the general editor assistant. The button stays visible in edit and preview
modes. A minimized AI Tasks session is resumed first; otherwise, the most
recently minimized session is resumed before opening a new AI Tasks dialog.
Keep `DocumentEditAndViewFragment.applyTodoText()` and the `MainActivity` todo
bridge inside their marked hooks: after Apply they replace the open editor text
on the UI thread, save it, and refresh the preview when visible. These paths
cover Edit Tasks, Shift Overdue, and Diff Preview Apply without XML or manifest
changes.

The Tasks dialog has `AI Command & Plan` and `Tasks & Reminders` tabs. Its
`Today's Study Plan` action and `Edit Tasks`/`Task Chat` modes share the
todo parser; only edit and overdue-shift actions open a diff for explicit safe
application. Note references are stored as relative `ref:` tags, and opening a
linked note requires an existing `.md` file that resolves inside the Notebook.
Calendar and Clock reminders use native insert/set-alarm intents and require no
manifest permission additions.
