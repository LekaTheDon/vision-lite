package com.lekathedon.visionlite.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Type a TV form on the phone (Vision's PhoneFormServer idea, without NanoHTTPD): one QR opens a page with the
 * fields under a random one-time path, one Send fills them in. Runs only while the form is on screen; answers a
 * single successful send. [onSubmit] runs on the server thread.
 */
class PhoneForm private constructor(
    private val server: ServerSocket,
    private val title: String,
    private val fields: List<Field>,
    private val onSubmit: (List<String>) -> Unit
) {
    /** [link] = a button above the field that opens a sign-in page on the phone ([linkText] on it). */
    data class Field(val label: String, val hint: String = "", val initial: String = "", val secret: Boolean = false, val note: String? = null,
                     val link: String? = null, val linkText: String = "Open sign-in")

    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var used = false
    @Volatile private var stopped = false

    fun url(ip: String) = "http://$ip:${server.localPort}/f/$token"

    fun stop() { stopped = true; runCatching { server.close() } }

    private fun loop() {
        while (!stopped) {
            val s = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { s.soTimeout = 5000; handle(s) }
            runCatching { s.close() }
        }
    }

    private fun handle(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val length = lines.firstOrNull { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val page = "/f/$token"
        when {
            path != page -> reply(s, 404, "text/plain", "Not found")
            used -> reply(s, 410, "text/html; charset=utf-8", DONE)
            method == "GET" -> reply(s, 200, "text/html; charset=utf-8", html())
            method == "POST" && length in 1..MAX_BODY -> {
                val body = ByteArray(length); var off = 0
                while (off < length) { val n = input.read(body, off, length - off); if (n <= 0) break; off += n }
                val values = runCatching {
                    val a = JSONObject(String(body, 0, off, StandardCharsets.UTF_8)).getJSONArray("values")
                    List(fields.size) { i -> if (i < a.length()) a.optString(i, "") else "" }
                }.getOrNull()
                if (values == null) reply(s, 400, "application/json", """{"error":"bad form"}""")
                else { used = true; reply(s, 200, "application/json", """{"status":"sent"}"""); onSubmit(values) }
            }
            else -> reply(s, 405, "text/plain", "")
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

    private fun reply(s: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 410 -> "Gone"; else -> "Error" }
        s.getOutputStream().apply {
            write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun js(s: String) = s.replace("</", "<\\/")

    private fun jsonFields() = JSONArray().apply {
        fields.forEach { f -> put(JSONObject().put("label", f.label).put("hint", f.hint).put("value", f.initial).put("secret", f.secret).apply { f.note?.let { put("note", it) }; f.link?.let { put("link", it); put("linkText", f.linkText) } }) }
    }.toString()

    // Page from Vision's PhoneFormWebPage (same look and script).
    private fun html() = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Vision</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#0d0f1a;color:#e8eaf2;font:16px system-ui,-apple-system,sans-serif}
main{max-width:520px;margin:0 auto;padding:24px 16px 40px}
h1{font-size:22px;margin:0 0 4px}
p.sub{color:#9aa0b4;margin:0 0 22px}
label{display:block;font-size:14px;color:#b9bed0;margin:16px 0 6px}
.row{display:flex;gap:8px}
input{flex:1;width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #2c3150;background:#161a2c;color:#fff;font-size:17px}
input:focus{outline:none;border-color:#8b6cff}
button{border:0;border-radius:12px;font-size:16px;cursor:pointer}
.show{padding:0 14px;background:#232842;color:#cfd3e6}
.send{width:100%;margin-top:28px;padding:16px;background:linear-gradient(90deg,#3d7bff,#9b4dff);color:#fff;font-weight:600}
.send:disabled{opacity:.6}
#msg{margin-top:18px;text-align:center;color:#9aa0b4}
#msg.ok{color:#5fd38d;font-size:18px}
.note{font-size:13px;color:#8f95ab;margin:-2px 0 8px}
.go{display:block;text-align:center;padding:15px;border-radius:12px;background:#232842;color:#fff;text-decoration:none;font-weight:600;margin:8px 0 12px}
</style></head><body><main>
<h1 id="t"></h1><p class="sub">Fill in, then send to your TV.</p>
<form id="f" autocomplete="off"></form>
<button class="send" id="s" type="button">Send to TV</button>
<div id="msg"></div>
</main><script>
var title=${js(JSONObject.quote(title))};
var fields=${js(jsonFields())};
document.getElementById('t').textContent=title;
var form=document.getElementById('f'),inputs=[];
fields.forEach(function(fd,i){
  var l=document.createElement('label');l.textContent=fd.label;l.htmlFor='i'+i;form.appendChild(l);
  if(fd.note){var n=document.createElement('div');n.className='note';n.textContent=fd.note;form.appendChild(n);}
  if(fd.link){var a=document.createElement('a');a.className='go';a.href=fd.link;a.target='_blank';a.rel='noopener';a.textContent=fd.linkText;form.appendChild(a);}
  var row=document.createElement('div');row.className='row';
  var x=document.createElement('input');x.id='i'+i;x.value=fd.value||'';x.placeholder=fd.hint||'';
  x.type=fd.secret?'password':(/url/i.test(fd.label)?'url':'text');
  x.autocapitalize='off';x.spellcheck=false;x.enterKeyHint=i<fields.length-1?'next':'send';
  x.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();
    if(i<fields.length-1)inputs[i+1].focus();else send();}});
  row.appendChild(x);
  if(fd.secret){var b=document.createElement('button');b.type='button';b.className='show';b.textContent='Show';
    b.onclick=function(){x.type=x.type==='password'?'text':'password';b.textContent=x.type==='password'?'Show':'Hide';};
    row.appendChild(b);}
  form.appendChild(row);inputs.push(x);
});
if(inputs.length)inputs[0].focus();
var btn=document.getElementById('s'),msg=document.getElementById('msg');
function send(){
  btn.disabled=true;msg.className='';msg.textContent='Sending…';
  fetch(location.pathname,{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({values:inputs.map(function(x){return x.value.trim();})})})
  .then(function(r){if(!r.ok)throw new Error(r.status);
    form.style.display='none';btn.style.display='none';msg.className='ok';
    msg.textContent='Sent. Check your TV.';})
  .catch(function(){btn.disabled=false;msg.textContent='Could not reach the TV. Is it still showing the QR code?';});
}
btn.onclick=send;
</script></body></html>"""

    companion object {
        private const val MAX_BODY = 64 * 1024
        const val FIRST_PORT = 8094
        private const val DONE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="background:#0d0f1a;color:#e8eaf2;font:17px system-ui,sans-serif;text-align:center;padding:48px 16px">Already sent to the TV.</body></html>"""

        fun start(title: String, fields: List<Field>, onSubmit: (List<String>) -> Unit): PhoneForm? {
            for (port in FIRST_PORT until FIRST_PORT + 10) {
                val ss = runCatching { ServerSocket(port) }.getOrNull() ?: continue
                return PhoneForm(ss, title, fields, onSubmit).also { f -> Thread({ f.loop() }, "phone-form").apply { isDaemon = true }.start() }
            }
            return null
        }

        /** The box's LAN address (Ethernet or Wi-Fi; not the VPN's). */
        fun lanIp(@Suppress("UNUSED_PARAMETER") context: Context): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
                .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
