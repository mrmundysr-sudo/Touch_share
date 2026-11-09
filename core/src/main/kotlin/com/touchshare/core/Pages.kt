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

    private fun shell(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
<style>body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:#12151c;color:#e8ecf4}
h1{font-size:1.2rem}a.f{display:block;padding:14px;margin:8px 0;background:#1d2330;border-radius:10px;color:#e8ecf4;text-decoration:none}
a.f small{color:#8d98ad;display:block}progress{width:100%}.row{margin:8px 0;font-size:.9rem}
button,input[type=file]{font-size:1rem;padding:12px;margin:8px 0}</style></head><body>$body</body></html>"""

    fun listPage(token: String, files: List<ShareFile>): String {
        val items = files.joinToString("") {
            """<a class="f" href="/f/${esc(it.id)}?t=${esc(token)}">${esc(it.name)}<small>${humanSize(it.size)}</small></a>"""
        }
        val body = "<h1>Touch Share</h1>" + if (files.isEmpty()) "<p>No files shared.</p>" else items
        return shell("Touch Share", body)
    }

    fun uploadPage(token: String): String = shell("Touch Share", """<h1>Send files to this phone</h1>
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

    private fun jsString(s: String) = "\"" + s.filter { it.isLetterOrDigit() } + "\""
}
