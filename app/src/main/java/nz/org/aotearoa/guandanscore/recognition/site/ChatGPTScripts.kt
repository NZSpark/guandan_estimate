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
          // 新版助手单元带 "ChatGPT said:" 前缀，只保留正文。
          text = text.replace(/^ChatGPT said:\s*/i, '').trim();
          var generating = 0, password = 0, loginUi = 0, attach = 0;
          try { generating = document.querySelectorAll(${q(ChatGPTSelectors.generating())}).length; } catch (e) {}
          try { password = document.querySelectorAll(${q(ChatGPTSelectors.password())}).length; } catch (e) {}
          try { loginUi = document.querySelectorAll(${q(ChatGPTSelectors.login())}).length; } catch (e) {}
          // AI-042：发送前的输入区现状与可读会话标识（只读，不修改页面）。
          var inputEl = null;
          try { inputEl = document.querySelector(${q(ChatGPTSelectors.input())}); } catch (e) {}
          // 附件只数 composer 附近的预览：全局会把历史消息里的 blob 图片算进来。
          if (inputEl) {
            var box = inputEl;
            for (var bi = 0; bi < 6 && box.parentElement; bi++) box = box.parentElement;
            try { attach = box.querySelectorAll(${q(ChatGPTSelectors.attachment())}).length; } catch (e) {}
          }
          var inputText = inputEl ? (inputEl.value === undefined ? (inputEl.textContent || '') : inputEl.value) : '';
          var sessionId = '';
          try {
            var sm = (location.pathname || '').match(/\/c\/([^\/?#]+)/);
            sessionId = sm ? sm[1] : '';
          } catch (e) {}
          return JSON.stringify({
            ok: true,
            responseCount: nodes ? nodes.length : 0,
            lastAnswerText: text,
            generating: generating > 0,
            passwordCount: password,
            loginIndicatorCount: loginUi,
            inputText: inputText,
            attachmentCount: attach,
            attached: attach > 0,
            sessionId: sessionId,
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

    /**
     * 校验附件预览是否真的出现。
     * 只数 composer 附近，避免命中历史消息里的图片（与 Gemini / DeepSeek 同一原则）。
     */
    fun verifyAttachment(): String = """
        (function() {
          var input = null;
          try { input = document.querySelector(${q(ChatGPTSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!input) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          var box = input;
          for (var i = 0; i < 6 && box.parentElement; i++) box = box.parentElement;
          var n = 0;
          try { n = box.querySelectorAll(${q(ChatGPTSelectors.attachment())}).length; } catch (e) {
            return JSON.stringify({ ok: false, why: 'selector-error' });
          }
          // AI-183：额度用尽时明确上报，别让 20s 轮询后只剩「无预览」。
          var quota = false;
          try {
            var bodyText = document.body ? (document.body.innerText || '') : '';
            quota = /reached the limit for file attachments|Get Plus for more uploads|Or wait .* to upload again/i.test(bodyText);
          } catch (e) {}
          return JSON.stringify({
            ok: true, attachmentCount: n, attached: n > 0, scoped: true,
            quotaBlocked: quota && n === 0,
            why: quota && n === 0 ? 'upload-quota-exhausted' : null
          });
        })()
    """.trimIndent()

    /**
     * AI-182：只填文本，不提交。
     *
     * 顺序依据 ChatGPTBridge `doc/image_upload.md` §5.4：文本先进 composer，
     * 附件随后附加，最后提交，三者必须属于同一条消息。这里绝不能顺手点发送，
     * 也不能在附件已经挂上后执行 `selectAll` / 整段覆盖。
     */
    fun fillComposer(prompt: String): String = """
        (function(text) {
          var el;
          try { el = document.querySelector(${q(ChatGPTSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });

          function valueOf() {
            return String(el.value === undefined ? (el.textContent || '') : el.value);
          }
          try {
            el.focus();
            if (valueOf().trim() !== text.trim()) {
              // 先走 ProseMirror 的 paste 处理器；它会把文本写进编辑器 state。
              try {
                var dt = new DataTransfer();
                dt.setData('text/plain', text);
                el.dispatchEvent(new ClipboardEvent('paste', {
                  clipboardData: dt, bubbles: true, cancelable: true
                }));
              } catch (e) {}
              if (valueOf().trim() !== text.trim()) {
                document.execCommand('selectAll', false, null);
                try { document.execCommand('insertText', false, text); } catch (e) {}
              }
              if (valueOf().trim() !== text.trim()) {
                if (el.isContentEditable) {
                  el.textContent = text;
                  el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
                } else {
                  el.value = text;
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                }
              }
            }
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'fill-failed' });
          }
          var now = valueOf();
          var filled = now.trim() === text.trim();
          return JSON.stringify({
            ok: filled,
            inputFilled: filled,
            why: filled ? null : 'fill-not-applied',
            valueEmpty: String(now || '').trim().length === 0,
            length: text.length
          });
        })(${q(prompt)})
    """.trimIndent()

    /**
     * AI-182：只点发送。
     *
     * 附件已在提交前确认；这里不再重填文本，避免覆盖附件。发送按钮尚未渲染时
     * 返回 `ok:false, inputFilled:true`，由驱动层轮询后重试。
     */
    fun submitComposer(): String = """
        (function() {
          function findSend() {
            var list = [];
            try { list = document.querySelectorAll(${q(ChatGPTSelectors.send())}); } catch (e) { return null; }
            for (var i = 0; i < list.length; i++) {
              var b = list[i];
              var r = b.getBoundingClientRect();
              var dis = b.disabled === true || b.getAttribute('aria-disabled') === 'true';
              if (!dis && r.width > 0 && r.height > 0) return b;
            }
            return null;
          }
          var input = null;
          try { input = document.querySelector(${q(ChatGPTSelectors.input())}); } catch (e) {}
          var value = input ? (input.value === undefined ? (input.textContent || '') : input.value) : '';
          var btn = findSend();
          var clicked = false;
          if (btn) { try { btn.click(); clicked = true; } catch (e) {} }
          return JSON.stringify({
            ok: clicked,
            why: clicked ? null : (btn ? 'click-failed' : 'send-not-ready'),
            inputFilled: true,
            sendClicked: clicked,
            sendClickable: !!btn,
            sendDisabled: !btn,
            valueEmpty: String(value || '').trim().length === 0,
            url: location.href
          });
        })()
    """.trimIndent()

    /**
     * 填 prompt 并提交。
     *
     * ProseMirror 不接受直接写 `textContent`：必须派发 `beforeinput` / `input`，
     * 否则编辑器内部状态不会同步，发送按钮保持禁用。
     *
     * AI-182：生产流程已改用 [fillComposer] + 上传 + [submitComposer]；
     * 本函数保留给探针/兼容测试，主流程不再使用。
     */
    fun fillAndSubmit(prompt: String): String = """
        (function(text) {
          var el;
          try { el = document.querySelector(${q(ChatGPTSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });

          function valueOf() {
            return String(el.value === undefined ? (el.textContent || '') : el.value);
          }
          // AI-158：ProseMirror 只认真实的输入事件；写 textContent 不会更新编辑器状态，
          // 发送按钮因此一直是 disabled（真机实测）。insertText 会走 beforeinput/input。
          try {
            el.focus();
            if (valueOf().trim() !== text.trim()) {
              // ProseMirror 的粘贴处理器会把文本写进编辑器 state；只改 DOM 不算数。
              try {
                var dt = new DataTransfer();
                dt.setData('text/plain', text);
                el.dispatchEvent(new ClipboardEvent('paste', {
                  clipboardData: dt, bubbles: true, cancelable: true
                }));
              } catch (e) {}
              if (valueOf().trim() !== text.trim()) {
                document.execCommand('selectAll', false, null);
                try { document.execCommand('insertText', false, text); } catch (e) {}
              }
              if (valueOf().trim() !== text.trim()) {
                if (el.isContentEditable) {
                  el.textContent = text;
                  el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
                } else {
                  el.value = text;
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                }
              }
            }
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'fill-failed' });
          }

          var filled = valueOf().trim() === text.trim();

          function findSend() {
            var list = [];
            try { list = document.querySelectorAll(${q(ChatGPTSelectors.send())}); } catch (e) { return null; }
            for (var i = 0; i < list.length; i++) {
              var b = list[i];
              var r = b.getBoundingClientRect();
              var dis = b.disabled === true || b.getAttribute('aria-disabled') === 'true';
              if (!dis && r.width > 0 && r.height > 0) return b;
            }
            return null;
          }

          // 只认「真的点到了可用按钮」。按钮尚未渲染/仍 disabled 时明确返回失败，
          // 由驱动层 awaitSendEnabled 轮询后重试一次（此时文本已在，脚本只补点击）。
          var btn = findSend();
          var clicked = false;
          if (btn) { try { btn.click(); clicked = true; } catch (e) {} }

          var valueNow = valueOf();
          return JSON.stringify({
            ok: clicked,
            why: clicked ? null : (btn ? 'click-failed' : 'send-not-ready'),
            inputFilled: filled,
            valueEmpty: String(valueNow || '').trim().length === 0,
            length: text.length,
            sentEnter: false,
            sendClicked: clicked,
            sendClickable: !!btn,
            sendCount: btn ? 1 : 0,
            sendDisabled: !btn,
            url: location.href
          });
        })(${q(prompt)})
    """.trimIndent()

    /**
     * AI-158：清空未发送的文本草稿。
     *
     * ChatGPT 会把未发送文本持久化为草稿，进程重启或回到首页都会被恢复；
     * 驱动层快照因此一直判「输入区脏」。这里只清文本，不读内容、不碰附件。
     */
    fun clearComposer(): String = """
        (function() {
          var el = null;
          try {
            el = document.querySelector(
              "#prompt-textarea, div.ProseMirror[contenteditable='true'], div[contenteditable='true'][data-placeholder]"
            );
          } catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          try {
            el.focus();
            if (el.isContentEditable) {
              el.textContent = '';
              el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
            } else {
              el.value = '';
              el.dispatchEvent(new Event('input', { bubbles: true }));
            }
          } catch (e) { return JSON.stringify({ ok: false, why: 'clear-failed' }); }
          var left = (el.value === undefined ? (el.textContent || '') : el.value);
          return JSON.stringify({ ok: true, remainingLength: String(left).trim().length });
        })()
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
          // AI-183：先区分「页面本身收不了」与「事件没派发成功」。
          // 真机实测：免费账号附件额度用尽时，paste 被静默丢弃，页面上只有额度提示。
          try {
            var bodyText = document.body ? (document.body.innerText || '') : '';
            if (/reached the limit for file attachments|Get Plus for more uploads|Or wait .* to upload again/i.test(bodyText)) {
              return JSON.stringify({
                ok: false, dispatched: false, quotaBlocked: true,
                why: 'upload-quota-exhausted'
              });
            }
          } catch (e) {}
          // Gemini 同源现象：没有文档焦点时站点会 preventDefault 但丢弃文件。
          try {
            if (!document.hasFocus()) {
              return JSON.stringify({
                ok: false, dispatched: false, documentNotFocused: true,
                why: 'document-not-focused'
              });
            }
          } catch (e) {}
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
