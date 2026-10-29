package nz.org.aotearoa.guandanscore.recognition.site

import nz.org.aotearoa.guandanscore.recognition.GeminiScripts

/**
 * 注入 ChatGPT 页面的 JavaScript 单一事实来源。
 *
 * ## 验证状态：**未真机验证**（AI-164）
 * 脚本结构与 [DeepSeekScripts] 同构（同一套 `ok` / `why` 契约、同一套不读 Cookie 的规则），
 * 但其中的 DOM 假设尚未在目标设备上核对。**不得**当作已验证行为写进文档。
 *
 * 规则：
 * - 静态字符串；动态参数用 [GeminiScripts.quote] JSON 编码。
 * - 每个脚本返回可解析 JSON，带 `ok` 与失败时的 `why`。
 * - 不读 Cookie / 令牌 / 图片 Base64。
 * - 禁止同步忙等（会卡死事件循环）。
 */
object ChatGPTScripts {

    private fun q(raw: String): String = GeminiScripts.quote(raw)

    /** 只读诊断：各选择器命中数 + 页面基础状态。 */
    fun diagnose(): String = """
        (function() {
          function count(sel) {
            try { return document.querySelectorAll(sel).length; } catch (e) { return -1; }
          }
          var input = null;
          try { input = document.querySelector(${q(ChatGPTSelectors.input())}); } catch (e) {}
          var inputTag = input ? input.tagName.toLowerCase() : '';
          return JSON.stringify({
            ok: true,
            href: location.href,
            readyState: document.readyState,
            innerHeight: window.innerHeight,
            innerWidth: window.innerWidth,
            inputCount: count(${q(ChatGPTSelectors.input())}),
            inputTag: inputTag,
            inputEditable: !!(input && (input.isContentEditable || inputTag === 'textarea')),
            sendCount: count(${q(ChatGPTSelectors.send())}),
            responseCount: count(${q(ChatGPTSelectors.response())}),
            fileInputCount: count(${q(ChatGPTSelectors.fileInput())}),
            loginIndicatorCount: count(${q(ChatGPTSelectors.login())}),
            passwordCount: count(${q(ChatGPTSelectors.password())}),
            generatingCount: count(${q(ChatGPTSelectors.generating())}),
            attachmentCount: count(${q(ChatGPTSelectors.attachment())}),
            editableCount: count('div[contenteditable="true"]')
          });
        })()
    """.trimIndent()

    /** 读回答快照。用 `textContent`，离屏行 `innerText` 可能为空。 */
    fun snapshot(): String = """
        (function() {
          var nodes;
          try { nodes = document.querySelectorAll(${q(ChatGPTSelectors.response())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          var last = nodes && nodes.length > 0 ? nodes[nodes.length - 1] : null;
          var text = last ? (last.textContent || '').trim() : '';
          var generating = 0, password = 0, loginUi = 0;
          try { generating = document.querySelectorAll(${q(ChatGPTSelectors.generating())}).length; } catch (e) {}
          try { password = document.querySelectorAll(${q(ChatGPTSelectors.password())}).length; } catch (e) {}
          try { loginUi = document.querySelectorAll(${q(ChatGPTSelectors.login())}).length; } catch (e) {}
          return JSON.stringify({
            ok: true,
            responseCount: nodes ? nodes.length : 0,
            lastAnswerText: text,
            generating: generating > 0,
            passwordCount: password,
            loginIndicatorCount: loginUi,
            href: location.href
          });
        })()
    """.trimIndent()

    /** 判定是否未登录（必须同时有密码框与登录 UI，M6）。 */
    fun loginCheck(): String = """
        (function() {
          var p = 0, l = 0;
          try { p = document.querySelectorAll(${q(ChatGPTSelectors.password())}).length; } catch (e) {}
          try { l = document.querySelectorAll(${q(ChatGPTSelectors.login())}).length; } catch (e) {}
          return JSON.stringify({ ok: true, passwordCount: p, loginIndicatorCount: l,
                                  requiresLogin: p > 0 && l > 0, href: location.href });
        })()
    """.trimIndent()

    /** 校验附件预览是否真的出现。 */
    fun verifyAttachment(): String = """
        (function() {
          var n = 0;
          try { n = document.querySelectorAll(${q(ChatGPTSelectors.attachment())}).length; } catch (e) {
            return JSON.stringify({ ok: false, why: 'selector-error' });
          }
          return JSON.stringify({ ok: true, attachmentCount: n, attached: n > 0 });
        })()
    """.trimIndent()

    /**
     * 填 prompt 并提交。
     *
     * ProseMirror 不接受直接写 `textContent`：必须派发 `beforeinput` / `input`，
     * 否则编辑器内部状态不会同步，发送按钮保持禁用。
     */
    fun fillAndSubmit(prompt: String): String = """
        (function(text) {
          var el;
          try { el = document.querySelector(${q(ChatGPTSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          try {
            el.focus();
            if (el.isContentEditable) {
              el.textContent = text;
              el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
            } else {
              el.value = text;
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
            }
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'fill-failed' });
          }

          var filled = (el.value === undefined ? el.textContent : el.value) === text;

          // 主路径：Enter keydown。ProseMirror 依赖 keydown 提交。
          var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
          var enterSent = false;
          try {
            el.dispatchEvent(new KeyboardEvent('keydown', opts));
            el.dispatchEvent(new KeyboardEvent('keyup', opts));
            enterSent = true;
          } catch (e) {}

          // 兜底：点发送按钮。按钮可能在下一帧才可用，用 setTimeout 重试（禁止同步忙等）。
          var sendCount = 0, clicked = false;
          function findSend() {
            var list = [];
            try { list = document.querySelectorAll(${q(ChatGPTSelectors.send())}); } catch (e) { return null; }
            sendCount = list.length;
            for (var i = 0; i < list.length; i++) {
              var b = list[i];
              var r = b.getBoundingClientRect();
              var dis = b.disabled === true || b.getAttribute('aria-disabled') === 'true';
              if (!dis && r.width > 0 && r.height > 0) return b;
            }
            return null;
          }
          var btn = findSend();
          if (btn) { try { btn.click(); clicked = true; } catch (e) {} }
          if (!clicked) {
            var attempts = 0;
            var waitClick = function() {
              attempts++;
              var b = findSend();
              if (b) { try { b.click(); clicked = true; return; } catch (e) {} }
              if (attempts < 20) setTimeout(waitClick, 50);
            };
            setTimeout(waitClick, 50);
          }

          var valueNow = (el.value === undefined ? (el.textContent || '') : el.value);
          return JSON.stringify({
            ok: true,
            inputFilled: filled,
            valueEmpty: String(valueNow || '').trim().length === 0,
            length: text.length,
            sentEnter: enterSent,
            sendClicked: clicked,
            sendClickable: !!btn,
            sendCount: sendCount,
            sendDisabled: sendCount > 0 && !btn,
            url: location.href
          });
        })(${q(prompt)})
    """.trimIndent()

    /** AI-176：只读发送按钮状态，点击前轮询可用性。 */
    fun sendState(): String = """
        (function() {
          var list = [];
          try { list = Array.prototype.slice.call(document.querySelectorAll(${q(ChatGPTSelectors.send())})); } catch (e) {}
          var usable = null;
          for (var i = 0; i < list.length; i++) {
            var b = list[i];
            var r = b.getBoundingClientRect();
            var dis = b.disabled === true || b.getAttribute('aria-disabled') === 'true';
            if (!dis && r.width > 0 && r.height > 0) { usable = b; break; }
          }
          var input = null;
          try { input = document.querySelector(${q(ChatGPTSelectors.input())}); } catch (e) {}
          var value = input ? (input.value === undefined ? (input.textContent || '') : input.value) : '';
          return JSON.stringify({
            ok: true,
            sendCount: list.length,
            sendDisabled: list.length > 0 && !usable,
            sendClickable: !!usable,
            valueEmpty: String(value || '').trim().length === 0,
            url: location.href
          });
        })()
    """.trimIndent()

    /**
     * 合成 paste 注入图片。
     *
     * 返回值**不可**作为成败判据（部分站点会 `preventDefault`），调用方必须再用
     * [verifyAttachment] 确认预览是否真的出现。
     */
    fun pasteImage(fileName: String, mimeType: String, base64: String): String = """
        (function(name, mime, b64) {
          var el;
          try { el = document.querySelector(${q(ChatGPTSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          var bytes;
          try {
            var binary = atob(b64);
            bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
          } catch (e) { return JSON.stringify({ ok: false, why: 'decode-failed' }); }
          var file;
          try { file = new File([bytes], name, { type: mime }); }
          catch (e) { return JSON.stringify({ ok: false, why: 'file-construct-failed' }); }
          var dt = new DataTransfer();
          dt.items.add(file);
          el.focus();
          var ev;
          try {
            ev = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true, composed: true });
          } catch (e) { return JSON.stringify({ ok: false, why: 'clipboard-event-unsupported' }); }
          var accepted = el.dispatchEvent(ev);
          return JSON.stringify({ ok: true, dispatched: true, accepted: accepted, bytes: bytes.length });
        })(${q(fileName)}, ${q(mimeType)}, ${q(base64)})
    """.trimIndent()

    /** JS 点击文件输入框。与其它站点一样，预期不触发 `onShowFileChooser`（user activation）。 */
    fun uploadTrigger(): String = """
        (function() {
          var fs;
          try { fs = document.querySelectorAll('input[type="file"]'); } catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (fs && fs.length > 0) {
            try { fs[0].click(); } catch (e) {}
            return JSON.stringify({ ok: false, why: 'js-click-needs-user-activation', fileInputs: fs.length });
          }
          return JSON.stringify({ ok: false, why: 'no-file-input' });
        })()
    """.trimIndent()
}
