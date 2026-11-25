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

Add these exact marked blocks to `onOptionsItemSelected` and `onCreateOptionsMenu`.
The IDs are activity constants (`Menu.FIRST + 40` and `Menu.FIRST + 41`):

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
