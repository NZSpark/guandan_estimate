package nz.org.aotearoa.guandanscore.recognition

import org.json.JSONObject

/**
 * 注入 Gemini 页面的 JavaScript 单一事实来源。
 *
 * 规则：
 * - 所有脚本为可审计的静态字符串，不拼接来自用户的原始文本。
 * - 动态参数一律经 [quote] 做 JSON 编码后再嵌入。
 * - 所有脚本返回 JSON 字符串，含 `ok` 与失败时的 `why`；不得把异常吞成成功。
 * - 不读取、不返回 Cookie、令牌或图片 Base64。
 */
object GeminiScripts {

    /** JSON 安全编码。`JSONObject.quote` 会正确处理引号、反斜杠、换行、U+2028/2029。 */
    fun quote(raw: String): String = JSONObject.quote(raw)

    /** 只读诊断：统计各选择器命中数量与页面基础状态。 */
    fun diagnose(): String = """
        (function() {
          function count(sel) {
            try { return document.querySelectorAll(sel).length; } catch (e) { return -1; }
          }
          function first(sel) {
            try { return document.querySelector(sel); } catch (e) { return null; }
          }
          var input = first(${quote(GeminiSelectors.input())});
          var inputTag = input ? input.tagName.toLowerCase() : '';
          return JSON.stringify({
            ok: true,
            href: location.href,
            readyState: document.readyState,
            innerHeight: window.innerHeight,
            inputCount: count(${quote(GeminiSelectors.input())}),
            inputTag: inputTag,
            inputEditable: !!(input && (input.isContentEditable || inputTag === 'textarea')),
            sendCount: count(${quote(GeminiSelectors.send())}),
            responseCount: count(${quote(GeminiSelectors.response())}),
            fileInputCount: count(${quote(GeminiSelectors.fileInput())}),
            loginIndicatorCount: count(${quote(GeminiSelectors.login())}),
            generatingCount: count(${quote(GeminiSelectors.generating())})
          });
        })()
    """.trimIndent()

    /** 读取当前回答快照：节点数量 + 最后一个非空回答的文本。 */
    fun snapshot(): String = """
        (function() {
          var nodes;
          try { nodes = document.querySelectorAll(${quote(GeminiSelectors.response())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          var list = [];
          for (var i = 0; i < nodes.length; i++) {
            var t = (nodes[i].innerText || '').trim();
            if (t) list.push(t);
          }
          var generating = 0;
          try { generating = document.querySelectorAll(${quote(GeminiSelectors.generating())}).length; }
          catch (e) { generating = 0; }
          return JSON.stringify({
            ok: true,
            responseCount: nodes.length,
            lastAnswerText: list.length ? list[list.length - 1] : '',
            generating: generating > 0
          });
        })()
    """.trimIndent()

    /** 聚焦输入区并把 prompt 写入（不提交）。参数经 JSON 编码。 */
    fun fillPrompt(prompt: String): String = """
        (function(text) {
          var el;
          try { el = document.querySelector(${quote(GeminiSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          el.focus();
          if (el.isContentEditable) {
            el.textContent = text;
            el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
          } else if (el.tagName && el.tagName.toLowerCase() === 'textarea') {
            var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
            setter.call(el, text);
            el.dispatchEvent(new Event('input', { bubbles: true }));
          } else {
            return JSON.stringify({ ok: false, why: 'unsupported-input-type' });
          }
          return JSON.stringify({ ok: true, length: text.length });
        })(${quote(prompt)})
    """.trimIndent()

    /** 点击发送按钮。 */
    fun submit(): String = """
        (function() {
          var btn;
          try { btn = document.querySelector(${quote(GeminiSelectors.send())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!btn) return JSON.stringify({ ok: false, why: 'send-button-not-found' });
          if (btn.disabled) return JSON.stringify({ ok: false, why: 'send-button-disabled' });
          btn.click();
          return JSON.stringify({ ok: true });
        })()
    """.trimIndent()

    /**
     * 在页面上下文里构造 [File] 并通过合成 paste 事件注入图片。
     * 返回 `dispatched: true` **只代表事件已派发**，不代表页面接受了附件；
     * 调用方必须再用 [verifyAttachment] 确认预览出现。
     */
    fun pasteImage(fileName: String, mimeType: String, base64: String): String = """
        (function(name, mime, b64) {
          var el;
          try { el = document.querySelector(${quote(GeminiSelectors.input())}); }
          catch (e) { return JSON.stringify({ ok: false, why: 'selector-error' }); }
          if (!el) return JSON.stringify({ ok: false, why: 'composer-not-found' });
          var bytes;
          try {
            var binary = atob(b64);
            bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'decode-failed' });
          }
          var file;
          try { file = new File([bytes], name, { type: mime }); }
          catch (e) { return JSON.stringify({ ok: false, why: 'file-construct-failed' }); }
          var dt = new DataTransfer();
          dt.items.add(file);
          el.focus();
          var ev;
          try {
            ev = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true, composed: true });
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'clipboard-event-unsupported' });
          }
          var accepted = el.dispatchEvent(ev);
          return JSON.stringify({ ok: true, dispatched: true, accepted: accepted, bytes: bytes.length });
        })(${quote(fileName)}, ${quote(mimeType)}, ${quote(base64)})
    """.trimIndent()

    /**
     * 校验图片附件是否真的出现：统计当前消息区域内图片/附件预览数量。
     * 单纯派发事件成功不能作为上传成功依据。
     */
    fun verifyAttachment(): String = """
        (function() {
          var imgs = 0, previews = 0;
          try {
            imgs = document.querySelectorAll('img[src^="blob:"], img[src^="data:image"]').length;
            previews = document.querySelectorAll('[data-test-id*="attachment"], .attachment-preview, .file-preview').length;
          } catch (e) {
            return JSON.stringify({ ok: false, why: 'selector-error' });
          }
          return JSON.stringify({ ok: true, imageCount: imgs, previewCount: previews, attached: (imgs + previews) > 0 });
        })()
    """.trimIndent()
}
