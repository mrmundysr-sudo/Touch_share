package com.touchshare.core

object Pages {
    fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
        }
    }

    fun humanSize(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun shell(title: String, token: String, body: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
<style>
:root{--g:#39ff14;--t:#e6fbe3}
html{background:#05080a}
body{margin:0;font-family:system-ui,sans-serif;color:var(--t)}
.page{max-width:520px;margin:0 auto;min-height:100vh;box-sizing:border-box;padding:29% 8% 24%;
 background:#05080a url(/assets/page_05.webp?t=${token.filter { it.isLetterOrDigit() }}) top center/100% auto no-repeat}
h1{font-size:1.1rem;margin:0 0 12px;color:var(--g);letter-spacing:.05em;text-transform:uppercase}
a.f{display:block;padding:12px 14px;margin:8px 0;background:rgba(10,60,20,.55);border:1px solid rgba(57,255,20,.45);
 border-radius:12px;color:var(--t);text-decoration:none;word-break:break-word}
a.f small{color:#8fd68a;display:block}
progress{width:100%;accent-color:var(--g)}
.row{margin:10px 0;font-size:.9rem;word-break:break-word}
button,::file-selector-button{font-size:1rem;font-weight:700;color:#fff;padding:12px 18px;margin:8px 0;
 background:linear-gradient(#35d12f,#0c7a1c);border:2px solid #cfd8d4;border-radius:14px}
input[type=file]{font-size:1rem;color:var(--t);max-width:100%}
p{line-height:1.45}
</style></head><body><div class="page">$body</div></body></html>"""

    fun listPage(token: String, files: List<ShareFile>): String {
        val items = files.joinToString("") {
            """<a class="f" href="/f/${esc(it.id)}?t=${esc(token)}">${esc(it.name)}<small>${humanSize(it.size)}</small></a>"""
        }
        val body = "<h1>Touch Share</h1>" + if (files.isEmpty()) "<p>No files shared.</p>" else items
        return shell("Touch Share", token, body)
    }

    fun uploadPage(token: String): String = shell("Touch Share", token, """<h1>Send files to this phone</h1>
<input id="p" type="file" multiple><br><button id="b">Upload</button><div id="log"></div>
<script>
var T=${jsString(token)};
document.getElementById('b').onclick=function(){
 var fs=document.getElementById('p').files,log=document.getElementById('log');
 Array.prototype.forEach.call(fs,function(f){
  var row=document.createElement('div');row.className='row';row.textContent=f.name+' ';
  var pr=document.createElement('progress');pr.max=1;pr.value=0;row.appendChild(pr);log.appendChild(row);
  var x=new XMLHttpRequest();x.open('POST','/upload?t='+encodeURIComponent(T));
  x.upload.onprogress=function(e){if(e.lengthComputable)pr.value=e.loaded/e.total};
  x.onload=function(){row.textContent=f.name+(x.status==200?' ✓ sent':' ✗ failed ('+x.status+')')};
  x.onerror=function(){row.textContent=f.name+' ✗ failed'};
  var d=new FormData();d.append('file',f,f.name);x.send(d);
 });
};
</script>""")

    fun waitingPage(token: String): String = shell("Touch Share", token, """<h1>Waiting for approval</h1>
<p id="m">The other phone needs to tap <b>Allow</b> for this device.</p>
<script>
var T=${jsString(token)};
function poll(){
 var x=new XMLHttpRequest();x.open('GET','/status?t='+encodeURIComponent(T));
 x.onload=function(){
  if(x.status==403){document.getElementById('m').textContent='The other phone declined this device.';return}
  if(x.status==200){try{var s=JSON.parse(x.responseText).state;if(s==='allowed'){location.replace('/?t='+encodeURIComponent(T));return}}catch(e){}}
  setTimeout(poll,1500)};
 x.onerror=function(){setTimeout(poll,3000)};
 x.send();
}
poll();
</script>""")

    private fun jsString(s: String) = "\"" + s.filter { it.isLetterOrDigit() } + "\""
}
