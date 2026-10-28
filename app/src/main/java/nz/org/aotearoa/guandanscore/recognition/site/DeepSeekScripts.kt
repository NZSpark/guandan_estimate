package nz.org.aotearoa.guandanscore.recognition.site

import nz.org.aotearoa.guandanscore.recognition.GeminiScripts

/**
 * 注入 DeepSeek 页面的 JavaScript 单一事实来源。
 *
 * 与 [GeminiScripts] 完全独立：DeepSeek 的输入框是 React 受控 `textarea`、
 * 发送按钮是 `div[role=button]`、主提交路径是 Enter keydown，和 Gemini 的
 * `rich-textarea [contenteditable]` + 发送按钮点击不是一套东西。
 *
 * 规则：
 * - 静态字符串；动态参数用 [GeminiScripts.quote] JSON 编码。
 * - 每个脚本返回可解析 JSON，带 `ok` 与失败时的 `why`。
 * - 不读 Cookie / 令牌 / 图片 Base64。
 * - 读回答用 `textContent`，不用 `innerText`（虚拟列表离屏行为空）。
 *
 * 选择器见 [DeepSeekSelectors]（已按 2026-10-28 真机实测回填）。
 */
object DeepSeekScripts {

    private fun q(raw: String): String = GeminiScripts.quote(raw)

    /** 只读诊断：各选择器命中数 + 页面基础状态。 */
    fun diagnose(): String = """
        (function() {
          function count(sel) {
            try { return document.querySelectorAll(sel).length; } catch (e) { return -1; }
          }
          var input = null;
          try { input = document.querySelector(${q(DeepSeekSelectors.input())}); } catch (e) {}
          var inputTag = input ? input.tagName.toLowerCase() : '';
          return JSON.stringify({
            ok: true,
            href: location.href,
            readyState: document.readyState,
            innerHeight: window.innerHeight,
            inputCount: count(${q(DeepSeekSelectors.input())}),
            inputTag: inputTag,
            inputEditable: !!(input && (input.isContentEditable || inputTag === 'textarea')),
            sendCount: count(${q(DeepSeekSelectors.send())}),
            responseCount: count(${q(DeepSeekSelectors.response())}),
            fileInputCount: count(${q(DeepSeekSelectors.fileInput())}),
            loginIndicatorCount: count(${q(DeepSeekSelectors.login())}),
            passwordCount: count(${q(DeepSeekSelectors.password())}),
            generatingCount: count(${q(DeepSeekSelectors.generating())}),
            attachmentCount: count(${q(DeepSeekSelectors.attachment())}),
            editableCount: count('div[contenteditable="true"]')
          });
        })()
    """.trimIndent()

    /**
     * 读回答快照。
     *
     * 用 `textContent`：虚拟列表的离屏行 `innerText` 为空，会把“有答案”误判成“无答案”。
     * 生成中信号缺失时 generating 为 false（不得作为完成前提）。
     */
    fun snapshot(): String = """
        (function() {
          var nodes;
          try { nodes = document.querySelectorAll(${q(DeepSeekSelectors.response())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          var last = nodes && nodes.length > 0 ? nodes[nodes.length - 1] : null;
          var text = last ? (last.textContent || '').trim() : '';
          var generating = 0, password = 0, loginUi = 0;
          try { generating = document.querySelectorAll(${q(DeepSeekSelectors.generating())}).length; } catch (e) {}
          try { password = document.querySelectorAll(${q(DeepSeekSelectors.password())}).length; } catch (e) {}
          try { loginUi = document.querySelectorAll(${q(DeepSeekSelectors.login())}).length; } catch (e) {}
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

    /**
     * AI-008D：DeepSeek 页面结构深度探测（只读）。
     *
     * 在 [diagnose] 的计数之外，回答几个驱动层必须依赖的事实：
     * - 输入框是否**唯一**且是**最内层**（外层 wrapper 也会命中选择器，写错会填到壳上）；
     * - 发送按钮的**可点击判据**（`disabled` / `aria-disabled` / 尺寸为 0 都不能点）；
     * - 回答节点的**容器标识**（class 前缀、层级深度），供快照脚本稳定定位；
     * - 是否存在 **Shadow DOM**（有则 `querySelectorAll` 会漏，必须改遍历方式）。
     */
    fun deepProbe(): String = """
        (function() {
          function all(sel) {
            try { return Array.prototype.slice.call(document.querySelectorAll(sel)); } catch (e) { return []; }
          }
          function box(el) {
            if (!el) return null;
            var r = el.getBoundingClientRect();
            return { w: Math.round(r.width), h: Math.round(r.height), x: Math.round(r.left), y: Math.round(r.top) };
          }
          function cls(el) {
            if (!el) return '';
            var c = el.className;
            if (typeof c === 'object' && c && c.baseVal != null) c = c.baseVal;
            return (c || '').toString().split(/\\s+/).filter(function (s) { return s.length > 0; }).slice(0, 4).join(' ');
          }

          var inputs = all(${q(DeepSeekSelectors.input())});
          var input = inputs.length > 0 ? inputs[0] : null;
          // 最内层判定：输入框内部不应再包含可编辑节点。
          var innerEditable = 0;
          if (input) {
            try { innerEditable = input.querySelectorAll('[contenteditable="true"], textarea').length; } catch (e) {}
          }

          var sendEl = all(${q(DeepSeekSelectors.send())});
          var send = sendEl.length > 0 ? sendEl[0] : null;
          var sendBox = box(send);
          var sendDisabled = !!(send && (send.disabled === true ||
            (send.getAttribute && send.getAttribute('aria-disabled') === 'true')));
          var sendClickable = !!send && !sendDisabled && sendBox !== null && sendBox.w > 0 && sendBox.h > 0;

          var responses = all(${q(DeepSeekSelectors.response())});
          var lastResponse = responses.length > 0 ? responses[responses.length - 1] : null;
          var depth = 0, walk = lastResponse;
          while (walk && walk.parentElement && depth < 32) { walk = walk.parentElement; depth++; }

          // Shadow DOM：直接扫所有已知宿主 + 递归 open shadowRoot 计数。
          var shadowHosts = 0;
          (function scan(root) {
            if (!root || !root.querySelectorAll) return;
            var hosts = root.querySelectorAll('*');
            for (var i = 0; i < hosts.length; i++) {
              if (hosts[i].shadowRoot) { shadowHosts++; if (shadowHosts < 50) scan(hosts[i].shadowRoot); }
            }
          })(document);

          return JSON.stringify({
            ok: true,
            href: location.href,
            readyState: document.readyState,
            innerHeight: window.innerHeight,
            innerWidth: window.innerWidth,
            dpr: window.devicePixelRatio || 1,
            inputCount: inputs.length,
            inputUnique: inputs.length === 1,
            inputTag: input ? input.tagName.toLowerCase() : '',
            inputId: input ? (input.id || '') : '',
            inputClass: cls(input),
            inputEditable: !!(input && (input.isContentEditable || (input.tagName || '').toLowerCase() === 'textarea')),
            inputInnerEditableCount: innerEditable,
            inputIsInnermost: innerEditable === 0,
            inputBox: box(input),
            sendCount: sendEl.length,
            sendTag: send ? send.tagName.toLowerCase() : '',
            sendAriaLabel: send ? (send.getAttribute('aria-label') || '') : '',
            sendDisabled: sendDisabled,
            sendBox: sendBox,
            sendClickable: sendClickable,
            responseCount: responses.length,
            responseTag: lastResponse ? lastResponse.tagName.toLowerCase() : '',
            responseClass: cls(lastResponse),
            responseDepth: depth,
            shadowHostCount: shadowHosts,
            hasShadowDom: shadowHosts > 0
          });
        })()
    """.trimIndent()

    /** 登录检测：必须同时有密码框与登录 UI。 */
    fun loginCheck(): String = """
        (function() {
          var password = 0, loginUi = 0;
          try { password = document.querySelectorAll(${q(DeepSeekSelectors.password())}).length; } catch (e) {}
          try { loginUi = document.querySelectorAll(${q(DeepSeekSelectors.login())}).length; } catch (e) {}
          return JSON.stringify({ ok: true, passwordCount: password, loginIndicatorCount: loginUi });
        })()
    """.trimIndent()

    /**
     * 填 prompt + 提交，一次注入完成。
     *
     * 与 Gemini 的关键差异：
     * 1. `textarea.value = text` 不会通知 React（受控组件），必须走 prototype setter
     *    再派发 `input` + `change`，否则发送按钮保持禁用。
     * 2. 主提交路径是 **Enter keydown**；点发送按钮只跳新会话 URL、不发请求。
     * 3. Enter 之后再兜底点一次发送按钮（若仍启用）。
     *
     * 返回 `ok` 只代表“已尽力提交”，真实成功由调用方用快照确认新回答。
     */
    fun fillAndSubmit(prompt: String): String = """
        (function(text) {
          var el;
          try { el = document.querySelector(${q(DeepSeekSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });

          el.focus();
          var isTextarea = el.tagName && el.tagName.toLowerCase() === 'textarea';
          try {
            if (isTextarea) {
              var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
              setter.call(el, text);
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              if (el.value !== text) {
                try { el.select(); document.execCommand('insertText', false, text); } catch (e) {}
              }
            } else if (el.isContentEditable) {
              el.textContent = text;
              el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
            } else {
              return JSON.stringify({ ok: false, why: 'unsupported-input-type' });
            }
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'fill-failed' });
          }

          var filled = (el.value === undefined ? el.textContent : el.value) === text;

          // 主路径：Enter keydown（实测可提交）。
          var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
          try {
            el.dispatchEvent(new KeyboardEvent('keydown', opts));
            el.dispatchEvent(new KeyboardEvent('keyup', opts));
          } catch (e) {}

          // 兜底：若输入框仍保留文本，再点一次启用的发送按钮。
          function findSend() {
            var list;
            try { list = document.querySelectorAll(${q(DeepSeekSelectors.send())}); }
            catch (e) { return null; }
            for (var i = 0; i < list.length; i++) {
              var b = list[i];
              var cls = (b.className || '').toString();
              var dis = b.disabled === true ||
                        b.getAttribute('aria-disabled') === 'true' ||
                        cls.indexOf('ds-button--disabled') >= 0;
              if (!dis) return b;
            }
            return null;
          }
          var btn = findSend();
          var clicked = false;
          if (btn) { try { btn.click(); clicked = true; } catch (e) {} }

          return JSON.stringify({
            ok: true,
            inputFilled: filled,
            length: text.length,
            sentEnter: true,
            clickedSendFallback: clicked
          });
        })(${q(prompt)})
    """.trimIndent()

    /**
     * 合成 paste 注入图片。
     *
     * 实测：DeepSeek 会接受并显示预览，但 `dispatchEvent` 返回 false。
     * **不能**用返回值判成败；调用方必须再用 [verifyAttachment] 确认。
     */
    fun pasteImage(fileName: String, mimeType: String, base64: String): String = """
        (function(name, mime, b64) {
          var el;
          try { el = document.querySelector(${q(DeepSeekSelectors.input())}); }
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
          // accepted=false 是正常现象（DeepSeek 仍会处理）。不要据此判失败。
          return JSON.stringify({ ok: true, dispatched: true, accepted: accepted, bytes: bytes.length });
        })(${q(fileName)}, ${q(mimeType)}, ${q(base64)})
    """.trimIndent()

    /** 校验附件预览是否真的出现。 */
    fun verifyAttachment(): String = """
        (function() {
          var n = 0;
          try { n = document.querySelectorAll(${q(DeepSeekSelectors.attachment())}).length; } catch (e) {
            return JSON.stringify({ ok: false, why: 'selector-error' });
          }
          return JSON.stringify({ ok: true, attachmentCount: n, attached: n > 0 });
        })()
    """.trimIndent()

    /**
     * 触发页面自身的上传入口。
     *
     * 实测：JS `input[type=file].click()` **不会**触发 `onShowFileChooser`
     * （`user activation` 限制）。因此本脚本只用于诊断，驱动层必须改用
     * 原生触摸（[locateUploadIcon] 的坐标）。
     */
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

    /**
     * 计算附件图标（回形针）的视口坐标，供驱动层用原生触摸点击。
     *
     * 这是 FILE_CHOOSER 路径唯一可行的触发方式：必须由真实触摸产生 user activation。
     * 返回 `{ok, x, y, w, h, innerHeight, dpr}`，坐标是 CSS 像素（与 WebView 视口一致）。
     */
    fun locateUploadIcon(): String = """
        (function() {
          var input = null;
          try { input = document.querySelector(${q(DeepSeekSelectors.input())}); } catch (e) {}
          if (!input) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          var box = input;
          for (var i = 0; i < 8 && box && box.parentElement; i++) { box = box.parentElement; }
          if (!box) return JSON.stringify({ ok: false, why: 'no-container' });
          var ir = input.getBoundingClientRect();

          function center(r) {
            return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2),
                     w: Math.round(r.width), h: Math.round(r.height), left: r.left, top: r.top };
          }
          function labelOf(el) {
            var a = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('data-tooltip'));
            var t = (el.textContent || '').trim();
            return ((a || '') + ' ' + (t.length <= 12 ? t : '')).toLowerCase();
          }

          // 1) 锚点：发送按钮。没有锚点就无法区分工具栏里的各个开关。
          var sendEl = null;
          try { sendEl = document.querySelector(${q(DeepSeekSelectors.send())}); } catch (e) {}
          var send = null;
          if (sendEl) {
            var sr = sendEl.getBoundingClientRect();
            if (sr.width > 0 && sr.height > 0) send = center(sr);
          }

          var cands = box.querySelectorAll('div.ds-button, div[role="button"], button');
          var labelled = null, leftOfSend = null;
          for (var j = 0; j < cands.length; j++) {
            var c = cands[j];
            var r = c.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) continue;
            if (r.width > 60 || r.height > 60) continue;
            var cls = (c.className || '').toString();
            if (cls.indexOf('ds-button__icon') >= 0) continue;  // 内层图标，取外层按钮
            if (cls.indexOf('ds-toggle-button') >= 0) continue; // 主题/侧栏开关，绝不上传
            if (cls.indexOf('--primary') >= 0) continue;        // 发送键本身
            // 只取输入框同一水平带内的按钮。
            if (r.top < ir.top - 60 || r.top > ir.bottom + 60) continue;
            var info = center(r);
            var lab = labelOf(c);
            if (lab.indexOf('attach') >= 0 || lab.indexOf('upload') >= 0 ||
                lab.indexOf('上传') >= 0 || lab.indexOf('附件') >= 0) {
              if (!labelled || info.left > labelled.left) labelled = info;
            }
            if (send) {
              // 发送键左侧、且垂直对齐（同一行）的最近按钮。
              if (info.left < send.left && Math.abs(info.top - send.top) <= 24) {
                if (!leftOfSend || info.left > leftOfSend.left) leftOfSend = info;
              }
            }
          }

          var best = labelled || leftOfSend;
          if (!best) {
            return JSON.stringify({ ok: false, why: 'icon-not-found', send: send,
                                    candidates: cands.length });
          }
          return JSON.stringify({ ok: true, x: best.x, y: best.y, w: best.w, h: best.h,
                                  via: labelled ? 'label' : 'left-of-send',
                                  innerHeight: window.innerHeight,
                                  dpr: window.devicePixelRatio || 1 });
        })()
    """.trimIndent()

    /**
     * 华为 WebView 布局修复。
     * 规则：innerHeight < 100 时**禁止**写高度（会把页面压成 1px，M3）。
     */
    fun pageFix(): String = """
        (function() {
          var h = window.innerHeight;
          if (!h || h < 100) return JSON.stringify({ ok: false, why: 'viewport-zero', innerHeight: h || 0 });
          return JSON.stringify({ ok: true, innerHeight: h });
        })()
    """.trimIndent()
}
