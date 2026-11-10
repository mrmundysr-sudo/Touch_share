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
<input id="p" type="file" multiple><br><button id="b">Upload</button>
<p id="hint">If a transfer is interrupted it carries on by itself. If you close the page, choose the same files again and tap Upload to continue.</p>
<div id="log"></div>
<script>
var T=${jsString(token)},CHUNK=2*1024*1024,queue=[],busy=false;
function q(p){return p+(p.indexOf('?')<0?'?':'&')+'t='+encodeURIComponent(T)}
function req(m,u,body,onp,cb){
 var x=new XMLHttpRequest();x.open(m,q(u));
 if(onp)x.upload.onprogress=onp;
 x.onload=function(){cb(null,x)};x.onerror=function(){cb('net')};x.send(body||null);
}
function js(x){try{return JSON.parse(x.responseText)}catch(e){return {}}}
function put(k,v){try{localStorage.setItem(k,v)}catch(e){}}
function get(k){try{return localStorage.getItem(k)}catch(e){return null}}
function del(k){try{localStorage.removeItem(k)}catch(e){}}
function upload(file,row,pr,done){
 var key='ts-up-'+T+'-'+file.name+'-'+file.size+'-'+file.lastModified,id=null,offset=0,tries=0;
 function label(t){row.firstChild.nodeValue=file.name+' '+t+' '}
 function fail(t){label('\u2717 '+t);pr.style.display='none';done()}
 function ok(){label('\u2713 sent');pr.style.display='none';del(key);done()}
 function retry(){tries++;if(tries>8)return fail('connection lost');label('(reconnecting\u2026)');setTimeout(resync,Math.min(1000*tries,5000))}
 function resync(){
  if(!id)return start();
  req('GET','/upload/status?id='+id,null,null,function(e,x){
   if(e)return retry();
   if(x.status==404){del(key);id=null;return start()}
   if(x.status!=200)return retry();
   offset=js(x).offset||0;send();
  });
 }
 function start(){
  req('POST','/upload/start?name='+encodeURIComponent(file.name)+'&size='+file.size,null,null,function(e,x){
   if(e)return retry();
   if(x.status==507)return fail('not enough space on the other phone');
   if(x.status==403)return fail('declined');
   if(x.status!=200)return retry();
   var j=js(x);id=j.id;offset=j.offset||0;put(key,id);
   if(j.done)return ok();
   send();
  });
 }
 function send(){
  var end=Math.min(offset+CHUNK,file.size);
  req('PUT','/upload/chunk?id='+id+'&offset='+offset,file.slice(offset,end),function(e){
   if(e.lengthComputable)pr.value=(offset+e.loaded)/file.size;
  },function(e,x){
   if(e)return retry();
   if(x.status==200){var j=js(x);tries=0;offset=j.offset;pr.value=offset/file.size;if(j.done)return ok();label('');return send()}
   if(x.status==409){offset=js(x).offset||0;return send()}
   if(x.status==404){del(key);id=null;offset=0;return start()}
   if(x.status==403)return fail('declined');
   retry();
  });
 }
 var saved=get(key);
 if(saved){id=saved;resync()}else start();
}
function next(){
 if(busy||!queue.length)return;busy=true;
 var file=queue.shift(),log=document.getElementById('log'),row=document.createElement('div');
 row.className='row';row.appendChild(document.createTextNode(file.name+' '));
 var pr=document.createElement('progress');pr.max=1;pr.value=0;row.appendChild(pr);log.appendChild(row);
 upload(file,row,pr,function(){busy=false;next()});
}
document.getElementById('b').onclick=function(){
 var fs=document.getElementById('p').files;
 for(var i=0;i<fs.length;i++)queue.push(fs[i]);
 next();
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
