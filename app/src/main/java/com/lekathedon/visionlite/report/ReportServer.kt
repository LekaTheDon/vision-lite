package com.lekathedon.visionlite.report

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * The report on the phone: one QR opens it under a random one-time path on the home network (no server anywhere
 * else), with Copy / Share / Download so it can be passed on. Lives only while the report screen is open.
 */
class ReportServer private constructor(private val server: ServerSocket, private val name: String, private val text: String) {
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var stopped = false

    fun url(ip: String) = "http://$ip:${server.localPort}/r/$token"
    fun stop() { stopped = true; runCatching { server.close() } }

    private fun loop() {
        while (!stopped) {
            val s = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { s.soTimeout = 5000; handle(s) }
            runCatching { s.close() }
        }
    }

    private fun handle(s: Socket) {
        val head = readHead(BufferedInputStream(s.getInputStream())) ?: return
        val path = head.substringBefore("\r\n").split(' ').getOrNull(1) ?: ""
        when (path) {
            "/r/$token" -> reply(s, 200, "text/html; charset=utf-8", html())
            "/r/$token/$name" -> reply(s, 200, "text/plain; charset=utf-8", text, "Content-Disposition: attachment; filename=\"$name\"\r\n")
            else -> reply(s, 404, "text/plain", "Not found")
        }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val b = StringBuilder()
        while (b.length < 8192) {
            val c = input.read(); if (c < 0) return null
            b.append(c.toChar())
            if (b.endsWith("\r\n\r\n")) return b.toString()
        }
        return null
    }

    private fun reply(s: Socket, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        s.getOutputStream().apply {
            write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "${extra}Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes); flush()
        }
    }

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun html() = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio Vision report</title><style>
body{margin:0;background:#0d0f1a;color:#e8eaf2;font:16px system-ui,sans-serif}
.bar{position:sticky;top:0;display:flex;gap:8px;flex-wrap:wrap;padding:12px 16px;background:#161a2b}
button,a.b{flex:1;min-width:90px;padding:12px;border:0;border-radius:10px;background:#3d6bff;color:#fff;font:600 15px system-ui;text-align:center;text-decoration:none}
p{margin:12px 16px;color:#aab}pre{margin:0 16px 32px;white-space:pre-wrap;word-break:break-all;font:12px ui-monospace,monospace;color:#cfd3e0}
</style></head><body><div class="bar"><button id="c">Copy</button><button id="s">Share</button><a class="b" href="$token/$name" download>Download</a></div>
<p id="m">Send this report to the Nuvio Vision team (Share or Download, then attach it). Keys and logins are already blanked.</p>
<pre id="t">${esc(text)}</pre><script>
var t=document.getElementById('t').textContent,m=document.getElementById('m');
document.getElementById('c').onclick=function(){(navigator.clipboard?navigator.clipboard.writeText(t):Promise.reject()).then(function(){m.textContent='Copied.'},function(){var r=document.createRange();r.selectNodeContents(document.getElementById('t'));var s=getSelection();s.removeAllRanges();s.addRange(r);m.textContent='Selected: copy it from the menu.'})};
document.getElementById('s').onclick=function(){var f;try{f=new File([t],'$name',{type:'text/plain'})}catch(e){}
if(f&&navigator.canShare&&navigator.canShare({files:[f]}))navigator.share({files:[f],title:'Nuvio Vision report'});
else if(navigator.share)navigator.share({title:'Nuvio Vision report',text:t});else m.textContent='Sharing is not available here: use Download.'};
</script></body></html>"""

    companion object {
        private const val FIRST_PORT = 8110

        fun start(name: String, text: String): ReportServer? {
            for (port in FIRST_PORT until FIRST_PORT + 10) {
                val ss = runCatching { ServerSocket(port) }.getOrNull() ?: continue
                return ReportServer(ss, name, text).also { r -> Thread({ r.loop() }, "report-page").apply { isDaemon = true }.start() }
            }
            return null
        }
    }
}
