@echo off
rem ============================================================
rem  Run ALL offline assertion suites.
rem
rem  Usage:  cmd /c build\run_all_tests.bat
rem
rem  Exit code 0 only when every suite passes. Use this before
rem  packaging a delivery so no regression ships.
rem
rem  NOTE: keep this file ASCII-only AND CRLF (NOT pure LF).
rem  * Measured 2026-10-07: with pure-LF endings cmd mis-parses THIS file --
rem    lines get swallowed, even `cd /d "%~dp0"` never takes effect, and the
rem    suites report false FAILs (python looked for scripts in the caller cwd).
rem    CRLF alone fixed it (21/27 green). The three sub run.bat are simple
rem    enough to survive LF, but do not rely on that.
rem  * Non-ASCII is a second hazard: cmd decodes it with the OEM code page
rem    (936 on this machine), so keep every line ASCII.
rem ============================================================
setlocal
cd /d "%~dp0"

echo.
echo ============ 1/82   VideoFitMath (video background math) ============
call vidtest\run.bat
if errorlevel 1 (
  echo [FAIL] vidtest
  exit /b 1
)

echo.
echo.
echo ============ 2/82   ChatProvider + balance (API capability ^& balance) ============
call cpidtest\run.bat
if errorlevel 1 (
  echo [FAIL] cpidtest
  exit /b 1
)

echo.
echo.
echo ============ 3/82   ApiClient source assertions ============
python verify_api_source.py
if errorlevel 1 (
  echo [FAIL] verify_api_source
  exit /b 1
)

echo.
echo.
echo ============ 4/82   reverse check: API (assertions must catch errors) ============
python reverse_check_api.py
if errorlevel 1 (
  echo [FAIL] reverse_check_api
  exit /b 1
)

echo.
echo.
echo ============ 5/82   reverse check: balance (assertions must catch errors) ============
python reverse_check_balance.py
if errorlevel 1 (
  echo [FAIL] reverse_check_balance
  exit /b 1
)

echo.
echo.
echo ============ 6/82   theme follow + its reverse check ============
python verify_theme_follow.py
if errorlevel 1 (
  echo [FAIL] verify_theme_follow
  exit /b 1
)
python reverse_check_theme.py
if errorlevel 1 (
  echo [FAIL] reverse_check_theme
  exit /b 1
)

echo.
echo.
echo ============ 7/82   predictive back source assertions ============
python verify_predictive_source.py
if errorlevel 1 (
  echo [FAIL] verify_predictive_source
  exit /b 1
)

echo.
echo.
echo ============ 8/82   reverse check: predictive back ============
python reverse_check_back_anim.py
if errorlevel 1 (
  echo [FAIL] reverse_check_back_anim
  exit /b 1
)

echo.
echo.
echo ============ 9/82   reverse check: pure translation ============
python reverse_check_translate.py
if errorlevel 1 (
  echo [FAIL] reverse_check_translate
  exit /b 1
)

echo.
echo.
echo ============ 10/82   image background + voice resource split ============
python verify_image_voice.py
if errorlevel 1 (
  echo [FAIL] verify_image_voice
  exit /b 1
)

echo.
echo.
echo ============ 11/82   reverse check: image + voice ============
python reverse_check_image_voice.py
if errorlevel 1 (
  echo [FAIL] reverse_check_image_voice
  exit /b 1
)

echo.
echo.
echo ============ 12/82   floating layers theme (bubble/ask/menu) ============
python verify_float_theme.py
if errorlevel 1 (
  echo [FAIL] verify_float_theme
  exit /b 1
)

echo.
echo.
echo ============ 13/82   reverse check: floating layers theme ============
python reverse_check_float_theme.py
if errorlevel 1 (
  echo [FAIL] reverse_check_float_theme
  exit /b 1
)

echo.
echo.
echo ============ 14/82   FpsMath (frame-rate math, real source) ============
call fpstest\run.bat
if errorlevel 1 (
  echo [FAIL] fpstest
  exit /b 1
)

echo.
echo.
echo ============ 15/82   game assistant (Shizuku FPS) ============
python verify_game_assistant.py
if errorlevel 1 (
  echo [FAIL] verify_game_assistant
  exit /b 1
)

echo.
echo.
echo ============ 16/82   overlay window geometry (cutout / landscape) ============
python verify_overlay_geometry.py
if errorlevel 1 (
  echo [FAIL] verify_overlay_geometry
  exit /b 1
)

echo.
echo.
echo ============ 17/82   reverse check: overlay geometry ============
python reverse_check_overlay_geometry.py
if errorlevel 1 (
  echo [FAIL] reverse_check_overlay_geometry
  exit /b 1
)

echo.
echo.
echo ============ 18/82   reverse check: game assistant ============
python reverse_check_game_assistant.py
if errorlevel 1 (
  echo [FAIL] reverse_check_game_assistant
  exit /b 1
)

echo.
echo.
echo ============ 19/82   monitor bar device-script selftest ============
rem The device script must self-test too: it reads logcat text, and a
rem misread parser means the whole device round was wasted. (--selftest, no device)
python verify_monitor_bar.py --selftest
if errorlevel 1 (
  echo [FAIL] verify_monitor_bar selftest
  exit /b 1
)

echo.
echo.
echo.
echo ============ 20/82   local lines (pure-local custom speech + chat) ============
python verify_local_lines.py
if errorlevel 1 (
  echo [FAIL] verify_local_lines
  exit /b 1
)

echo.
echo.
echo ============ 21/82   reverse check: local lines ============
python reverse_check_local_lines.py
if errorlevel 1 (
  echo [FAIL] reverse_check_local_lines
  exit /b 1
)

echo.
echo.
echo ============ 22/82   animation wiring (all 106 playable) ============
python verify_anim_wiring.py
if errorlevel 1 (
  echo [FAIL] verify_anim_wiring
  exit /b 1
)

echo.
echo.
echo ============ 23/82   reverse check: animation wiring ============
python reverse_check_anim_wiring.py
if errorlevel 1 (
  echo [FAIL] reverse_check_anim_wiring
  exit /b 1
)

echo.
echo.
echo ============ 24/82   character skeleton (action system split) ============
python verify_character_skeleton.py
if errorlevel 1 (
  echo [FAIL] verify_character_skeleton
  exit /b 1
)

echo.
echo.
echo ============ 25/82   reverse check: character skeleton ============
python reverse_check_character_skeleton.py
if errorlevel 1 (
  echo [FAIL] reverse_check_character_skeleton
  exit /b 1
)
echo.
echo.
echo ============ 26/82   scene bindings (P1: per-character overrides) ============
python verify_scene_bindings.py
if errorlevel 1 (
  echo [FAIL] verify_scene_bindings
  exit /b 1
)

echo.
echo.
echo ============ 27/82   reverse check: scene bindings ============
python reverse_check_scene_bindings.py
if errorlevel 1 (
  echo [FAIL] reverse_check_scene_bindings
  exit /b 1
)

echo.
echo.
echo ============ 28/82   action import (0j: auto pack.json / filename prefixes / add actions) ============
python verify_action_import.py
if errorlevel 1 (
  echo [FAIL] verify_action_import
  exit /b 1
)

echo.
echo.
echo ============ 29/82   reverse check: action import ============
python reverse_check_action_import.py
if errorlevel 1 (
  echo [FAIL] reverse_check_action_import
  exit /b 1
)

echo.
echo.
echo ============ 30/82   character export (bindings + export/import round trip) ============
python verify_character_export.py
if errorlevel 1 (
  echo [FAIL] verify_character_export
  exit /b 1
)

echo.
echo.
echo ============ 31/82   reverse check: character export ============
python reverse_check_character_export.py
if errorlevel 1 (
  echo [FAIL] reverse_check_character_export
  exit /b 1
)


echo.
echo.
echo ============ 32/82   character switch (P3: picker / switch / delete) ============
python verify_character_switch.py
if errorlevel 1 (
  echo [FAIL] verify_character_switch
  exit /b 1
)

echo.
echo.
echo ============ 33/82   reverse check: character switch ============
python reverse_check_character_switch.py
if errorlevel 1 (
  echo [FAIL] reverse_check_character_switch
  exit /b 1
)


echo.
echo.
echo ============ 34/82   appearance bindings (P5: per-character appearance) ============
python verify_appearance_bindings.py
if errorlevel 1 (
  echo [FAIL] verify_appearance_bindings
  exit /b 1
)

echo.
echo.
echo ============ 35/82   reverse check: appearance bindings ============
python reverse_check_appearance_bindings.py
if errorlevel 1 (
  echo [FAIL] reverse_check_appearance_bindings
  exit /b 1
)


echo.
echo.
echo ============ 36/82   edge snap (non-docked state must reach the screen edge) ============
python verify_edge_snap.py
if errorlevel 1 (
  echo [FAIL] verify_edge_snap
  exit /b 1
)

echo.
echo.
echo ============ 37/82   reverse check: edge snap ============
python reverse_check_edge_snap.py
if errorlevel 1 (
  echo [FAIL] reverse_check_edge_snap
  exit /b 1
)


echo.
echo.
echo ============ 38/82   appearance travels with the character pack ============
python verify_appearance_pack.py
if errorlevel 1 (
  echo [FAIL] verify_appearance_pack
  exit /b 1
)

echo.
echo.
echo ============ 39/82   reverse check: appearance pack ============
python reverse_check_appearance_pack.py
if errorlevel 1 (
  echo [FAIL] reverse_check_appearance_pack
  exit /b 1
)


echo.
echo.
echo ============ 40/82   web search (route C: search then prompt) ============
python verify_web_search.py
if errorlevel 1 (
  echo [FAIL] verify_web_search
  exit /b 1
)

echo.
echo.
echo ============ 41/82   reverse check: web search ============
python reverse_check_web_search.py
if errorlevel 1 (
  echo [FAIL] reverse_check_web_search
  exit /b 1
)

echo.
echo ============ 42/82   web smart (query clean + weather API) ============
python verify_web_smart.py
if errorlevel 1 (
  echo [FAIL] verify_web_smart
  exit /b 1
)

echo.
echo ============ 43/82   reverse check: web smart ============
python reverse_check_web_smart.py
if errorlevel 1 (
  echo [FAIL] reverse_check_web_smart
  exit /b 1
)

echo.
echo ============ 44/82   search provider (pluggable: any API) ============
python verify_web_provider.py
if errorlevel 1 (
  echo [FAIL] verify_web_provider
  exit /b 1
)

echo.
echo ============ 45/82   reverse check: search provider ============
python reverse_check_web_provider.py
if errorlevel 1 (
  echo [FAIL] reverse_check_web_provider
  exit /b 1
)

echo.
echo ============ 46/82   web resilience (cache / cooldown / fallback) ============
python verify_web_resilience.py
if errorlevel 1 (
  echo [FAIL] verify_web_resilience
  exit /b 1
)

echo.
echo ============ 47/82   reverse check: web resilience ============
python reverse_check_web_resilience.py
if errorlevel 1 (
  echo [FAIL] reverse_check_web_resilience
  exit /b 1
)

echo.
echo.
echo ============ 48/82   clip list (search / sort / drag in the action lists) ============
python verify_clip_list.py
if errorlevel 1 (
  echo [FAIL] verify_clip_list
  exit /b 1
)
rem The device script must self-test too (it parses menu JSON and reads row order).
python verify_clip_list_ui.py --selftest
if errorlevel 1 (
  echo [FAIL] verify_clip_list_ui selftest
  exit /b 1
)

echo.
echo.
echo ============ 49/82   reverse check: clip list ============
python reverse_check_clip_list.py
if errorlevel 1 (
  echo [FAIL] reverse_check_clip_list
  exit /b 1
)
echo.
echo.
echo ============ 50/82   keepalive (watchdog / boot / no-die-after-restart) ============
python verify_keepalive.py
if errorlevel 1 (
  echo [FAIL] verify_keepalive
  exit /b 1
)

echo.
echo.
echo ============ 51/82   reverse check: keepalive ============
python reverse_check_keepalive.py
if errorlevel 1 (
  echo [FAIL] reverse_check_keepalive
  exit /b 1
)

echo.
echo.
echo ============ 52/82   scene exits (every scene must be reachable) ============
python verify_scene_exits.py
if errorlevel 1 (
  echo [FAIL] verify_scene_exits
  exit /b 1
)

echo.
echo.
echo ============ 53/82   reverse check: scene exits ============
python reverse_check_scene_exits.py
if errorlevel 1 (
  echo [FAIL] reverse_check_scene_exits
  exit /b 1
)

echo.
echo.
echo ============ 54/82   dialog theme (every 3rd-level menu follows the app UI) ============
python verify_dialog_theme.py
if errorlevel 1 (
  echo [FAIL] verify_dialog_theme
  exit /b 1
)

echo.
echo ============ 55/82   reverse check: dialog theme ============
python reverse_check_dialog_theme.py
if errorlevel 1 (
  echo [FAIL] reverse_check_dialog_theme
  exit /b 1
)

echo.
echo.
echo ============ 56/82   page theme (usage page + static bubble drawables) ============
python verify_page_theme.py
if errorlevel 1 (
  echo [FAIL] verify_page_theme
  exit /b 1
)

echo.
echo ============ 57/82   reverse check: page theme ============
python reverse_check_page_theme.py
if errorlevel 1 (
  echo [FAIL] reverse_check_page_theme
  exit /b 1
)

echo.
echo ============ 58/82   theme custom (glass colors + ui alpha) ============
python verify_theme_custom.py
if errorlevel 1 (
  echo [FAIL] verify_theme_custom
  exit /b 1
)

echo.
echo ============ 59/82   reverse check: theme custom ============
python reverse_check_theme_custom.py
if errorlevel 1 (
  echo [FAIL] reverse_check_theme_custom
  exit /b 1
)

echo.
echo ============ 60/82   custom chat api (cleartext + loopback + probe) ============
python verify_custom_api.py
if errorlevel 1 (
  echo [FAIL] verify_custom_api
  exit /b 1
)

echo.
echo ============ 61/82   reverse check: custom chat api ============
python reverse_check_custom_api.py
if errorlevel 1 (
  echo [FAIL] reverse_check_custom_api
  exit /b 1
)

echo.
echo ============ 62/82   click sound (synth waveform + pet poke) ============
python verify_click_sound.py
if errorlevel 1 (
  echo [FAIL] verify_click_sound
  exit /b 1
)

echo.
echo ============ 63/82   reverse check: click sound ============
python reverse_check_click_sound.py
if errorlevel 1 (
  echo [FAIL] reverse_check_click_sound
  exit /b 1
)

echo.
echo ============ 64/82   delivery docs match reality ============
python verify_docs.py
if errorlevel 1 (
  echo [FAIL] verify_docs
  exit /b 1
)

echo.
echo ============ 65/82   reverse check: delivery docs ============
python reverse_check_docs.py
if errorlevel 1 (
  echo [FAIL] reverse_check_docs
  exit /b 1
)

echo.
echo.
echo ============ 66/82   local GGUF model (llama.cpp engine layer) ============
python verify_local_llm.py
if errorlevel 1 (
  echo [FAIL] verify_local_llm
  exit /b 1
)

echo.
echo.
echo ============ 67/82   reverse check: local GGUF model ============
python reverse_check_local_llm.py
if errorlevel 1 (
  echo [FAIL] reverse_check_local_llm
  exit /b 1
)

echo.
echo.
echo ============ 68/82   local GGUF model import (gguf gate / streamed copy / UI) ============
python verify_local_llm_import.py
if errorlevel 1 (
  echo [FAIL] verify_local_llm_import
  exit /b 1
)

echo.
echo.
echo ============ 69/82   reverse check: local GGUF model import ============
python reverse_check_local_llm_import.py
if errorlevel 1 (
  echo [FAIL] reverse_check_local_llm_import
  exit /b 1
)

echo.
echo.
echo ============ 70/82   local GGUF model chat wiring (router / resident load / no-net) ============
python verify_local_llm_chat.py
if errorlevel 1 (
  echo [FAIL] verify_local_llm_chat
  exit /b 1
)

echo.
echo.
echo ============ 71/82   reverse check: local GGUF model chat wiring ============
python reverse_check_local_llm_chat.py
if errorlevel 1 (
  echo [FAIL] reverse_check_local_llm_chat
  exit /b 1
)
echo.
echo ============ 72/82   local GGUF model speed (arm64 ISA / Vulkan GPU / KV reuse) ============
python verify_local_llm_speed.py
if errorlevel 1 (
echo [FAIL] verify_local_llm_speed
exit /b 1
)
echo.
echo ============ 73/82   reverse check: local GGUF model speed ============
python reverse_check_local_llm_speed.py
if errorlevel 1 (
echo [FAIL] reverse_check_local_llm_speed
exit /b 1
)

echo.
echo.
echo ============ 74/82   DSH core (pure javac + java, real agent loop) ============
call dshtest\run.bat
if errorlevel 1 (
  echo [FAIL] dshtest
  exit /b 1
)

echo.
echo.
echo ============ 75/82   DSH port assertions (kernel + glue + docs) ============
python verify_dsh_core.py
if errorlevel 1 (
  echo [FAIL] verify_dsh_core
  exit /b 1
)

echo.
echo.
echo ============ 76/82   reverse check: DSH port assertions ============
python reverse_check_dsh_core.py
if errorlevel 1 (
  echo [FAIL] reverse_check_dsh_core
  exit /b 1
)

echo.
echo.
echo ============ 77/82  harness client: real upstream UI in a WebView (old workbench deleted) ============
python verify_harness_client.py
if errorlevel 1 (
  echo [FAIL] verify_harness_client
  exit /b 1
)

echo.
echo ============ 78/82  reverse check: harness client (entry / webview / token / manifest) ============
python reverse_check_harness_client.py
if errorlevel 1 (
  echo [FAIL] reverse_check_harness_client
  exit /b 1
)

echo.
echo ============ 79/82  android providers: subprocess / sandbox / attachments (phone runs the host itself) ============
python verify_android_providers.py
if errorlevel 1 (
  echo [FAIL] verify_android_providers
  exit /b 1
)

echo.
echo ============ 80/82  reverse check: android providers (kill ladder / fail-closed / integrity / patch / --no-open / local-first) ============
python reverse_check_android_providers.py
if errorlevel 1 (
  echo [FAIL] reverse_check_android_providers
  exit /b 1
)

echo.
echo ============ 81/82  dual track: main app (34) + Linux engine (28) wiring ============
python verify_dual_track.py
if errorlevel 1 (
  echo [FAIL] verify_dual_track
  exit /b 1
)

echo.
echo ============ 82/82  reverse check: dual track (targetSdk / PROOT_NO_SECCOMP / patch / fallback / loopback) ============
python reverse_check_dual_track.py
if errorlevel 1 (
  echo [FAIL] reverse_check_dual_track
  exit /b 1
)

echo ============ ALL SUITES PASSED ============
exit /b 0
