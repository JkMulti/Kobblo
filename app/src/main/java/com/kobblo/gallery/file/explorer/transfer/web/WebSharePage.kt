package com.kobblo.gallery.file.explorer.transfer.web

import com.google.gson.Gson

/**
 * The page a computer opens. Everything is inline, so it works with no internet. Names are only ever
 * set through textContent: a file called `<img onerror=...>` must stay a name.
 *
 * Plain HTTP rules out the browser's own notifications, which need a secure page. So something on offer shows as a
 * question over the page, in the tab's title, and with a short tone made on the spot if the browser allows it.
 *
 * Its words are the phone's, in the phone's language, from [WebShareText]. Only the code here is fixed.
 */
internal object WebSharePage {
    private val gson = Gson()
    private val place = Regex("__([A-Z][A-Z_]*?)__")

    /**
     * The page, in [text]'s language. Its words are placed in one pass, so a device name that happens to look like a
     * place for words stays a name. What the script needs travels as JSON, which Gson writes safe to sit in a script.
     */
    fun html(deviceName: String, text: WebShareText): String {
        val words = HashMap<String, String>()
        text.page.forEach { (name, value) -> words[name] = escapeHtml(value) }
        text.page["DROP"]?.let { drop ->
            words["DROP"] = WebShareText.fill(escapeHtml(drop), "<strong>${escapeHtml(text.page["CHOOSE_FILES"].orEmpty())}</strong>")
        }
        words["DEVICE"] = escapeHtml(deviceName)
        words["LANG"] = escapeHtml(text.language)
        words["DIR"] = if (text.rightToLeft) "rtl" else "ltr"
        words["TEXT"] = gson.toJson(text.script + ("lang" to text.language))
        return place.replace(PAGE) { found -> words[found.groupValues[1]] ?: found.value }
    }

    internal fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private val PAGE = """
<!doctype html>
<html lang="__LANG__" dir="__DIR__">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>__DEVICE__</title>
<style>
:root{color-scheme:light dark;--canvas:#f2f2f7;--snow:#fff;--mist:#e5e5ea;--ink:#000;--smoke:#6c6c70;--action:#007aff;--danger:#ff3b30;--hair:rgba(60,60,67,.29);--veil:rgba(0,0,0,.35)}
@media (prefers-color-scheme:dark){:root{--canvas:#000;--snow:#1c1c1e;--mist:#2c2c2e;--ink:#fff;--smoke:#98989f;--action:#0a84ff;--danger:#ff453a;--hair:rgba(84,84,88,.5);--veil:rgba(0,0,0,.6)}}
*{box-sizing:border-box}
[hidden]{display:none!important}
body{margin:0;background:var(--canvas);color:var(--ink);font:17px/1.35 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased}
main{max-width:720px;margin:0 auto;padding:28px 16px 48px}
h1{font-size:34px;line-height:1.2;letter-spacing:-.5px;margin:0 0 4px;overflow-wrap:anywhere}
h2{font-size:22px;letter-spacing:-.2px;margin:28px 4px 8px}
.sub{color:var(--smoke);font-size:15px;margin:0 0 20px}
.group{background:var(--snow);border-radius:16px;overflow:hidden}
.row{display:flex;align-items:center;gap:12px;padding:11px 14px 11px 16px;position:relative}
.row+.row::before{content:"";position:absolute;left:16px;right:0;top:0;border-top:.5px solid var(--hair)}
.name{flex:1;min-width:0}
.title{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.meta{color:var(--smoke);font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
a.btn,button{appearance:none;border:0;border-radius:999px;background:var(--mist);color:var(--action);font:inherit;font-size:15px;font-weight:500;padding:6px 14px;text-decoration:none;cursor:pointer;white-space:nowrap}
a.primary,button.primary{background:var(--action);color:#fff}
button:disabled{opacity:.5;cursor:default}
.head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:28px 4px 8px}
.head h2{margin:0}
.empty{padding:24px 16px;text-align:center;color:var(--smoke)}
#drop{display:block;background:var(--snow);border-radius:16px;padding:28px 16px;text-align:center;cursor:pointer;border:2px dashed transparent;transition:border-color .2s,background .2s}
#drop.over{border-color:var(--action)}
#drop strong{color:var(--action);font-weight:500}
input[type=file]{display:none}
.bar{height:4px;border-radius:2px;background:var(--mist);margin-top:6px;overflow:hidden}
.bar i{display:block;height:100%;width:0;background:var(--action);transition:width .15s linear}
.bad{color:var(--danger)}
.note{color:var(--smoke);font-size:13px;margin:12px 4px 0}
.say{white-space:pre-wrap;overflow-wrap:anywhere;padding:11px 16px;user-select:text}
textarea{display:block;width:100%;min-height:72px;border:0;border-radius:16px;background:var(--snow);color:var(--ink);font:inherit;padding:12px 16px;resize:vertical}
.right{display:flex;justify-content:flex-end;margin-top:8px}
.veil{position:fixed;inset:0;background:var(--veil);display:flex;align-items:center;justify-content:center;padding:16px;z-index:9}
.ask{background:var(--snow);border-radius:20px;padding:20px;width:100%;max-width:440px}
.ask h2{margin:0 0 6px;overflow-wrap:anywhere}
.ask p{color:var(--smoke);font-size:15px;margin:0;overflow-wrap:anywhere}
.acts{display:flex;gap:10px;justify-content:flex-end;margin-top:18px}
.acts button{padding:9px 20px}
.link{background:none;padding:0;font-size:13px;font-weight:400}
</style>
</head>
<body>
<main id="main" hidden>
<h1>__DEVICE__</h1>
<p class="sub" id="summary">__LOADING__</p>
<div class="head"><h2>__FILES__</h2><a class="btn primary" id="all" href="zip" hidden>__DOWNLOAD_ALL__</a></div>
<div class="group" id="notes" style="margin-bottom:12px" hidden></div>
<div class="group" id="files"></div>
<p class="note" id="rejectedLine" hidden><span id="rejectedText"></span> <button class="link" id="changeMind">__CHANGED_MIND__</button></p>
<h2>__SEND_TO_PHONE__</h2>
<label id="drop"><input type="file" id="pick" multiple>__DROP__</label>
<p class="note" id="folderLine" hidden><label><input type="file" id="pickFolder" webkitdirectory multiple><strong style="color:var(--action);font-weight:500;cursor:pointer">__CHOOSE_FOLDER__</strong></label></p>
<p class="note">__ASKED_FIRST__</p>
<div class="group" id="uploads" style="margin-top:12px" hidden></div>
<h2>__SEND_NOTE__</h2>
<textarea id="words" placeholder="__NOTE_PLACEHOLDER__"></textarea>
<div class="right"><button class="primary" id="sendWords">__SEND__</button></div>
<p class="note" id="wordsResult" hidden></p>
<p class="note">__UNENCRYPTED__</p>
</main>
<div class="veil" id="gate"><div class="ask"><h2 id="gateTitle">__WAITING__</h2><p id="gateText"></p><div class="acts" id="gateActs" hidden><button class="primary" id="gateAgain">__TRY_AGAIN__</button></div></div></div>
<div class="veil" id="offer" role="dialog" aria-modal="true" hidden><div class="ask"><h2 id="offerTitle"></h2><p id="offerNames"></p><div class="acts"><button id="offerReject">__REJECT__</button><button class="primary" id="offerAccept">__ACCEPT__</button></div></div></div>
<script>
(function(){
  var device=document.title,T=__TEXT__;
  function byId(id){return document.getElementById(id)}
  // The phone's words, with their places filled in, and for a count the form the phone's language uses for it.
  function fmt(s){var a=arguments,n=0;return String(s).replace(/%(?:(\d+)\$)?([sd%])/g,function(m,i,t){return t=='%'?'%':a[i?+i:++n]})}
  var rules=null;try{rules=new Intl.PluralRules(T.lang)}catch(e){}
  function count(forms,n){var a=[].slice.call(arguments,1);a.unshift(forms[rules?rules.select(n):n==1?'one':'other']||forms.other);return fmt.apply(null,a)}
  var filesEl=byId('files'),notesEl=byId('notes'),summary=byId('summary'),all=byId('all'),uploads=byId('uploads');
  function size(n){if(n<1024)return n+' B';var u=['KB','MB','GB','TB'],i=-1;do{n/=1024;i++}while(n>=1024&&i<3);return n.toFixed(n<10?1:0)+' '+u[i]}
  function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e}
  function row(title,meta){var r=el('div','row'),n=el('div','name');n.appendChild(el('div','title',title));var m=el('div','meta',meta);n.appendChild(m);r.appendChild(n);return {row:r,name:n,meta:m}}
  function send(method,path,body,done){var x=new XMLHttpRequest();x.open(method,path);x.onload=function(){var d=null;try{d=JSON.parse(x.responseText)}catch(e){}done(x.status,d,x.responseText)};x.onerror=function(){done(0,null,'')};x.send(body==null?null:JSON.stringify(body))}

  // Asks the phone something, then keeps checking until there's an answer. Saying "waiting" is the page's job.
  function ask(what,report){
    send('POST','ask',what,function(s,d,t){
      if(s==429)return report('cooling');
      if(s==507||s==413)return report('refused',null,t);
      if(s!=200||!d)return report('error');
      if(d.state!='waiting')return report(d.state,d.id);
      report('waiting',d.id);
      var misses=0;
      (function wait(){setTimeout(function(){send('GET','ask/'+encodeURIComponent(d.id),null,function(s2,d2){
        if(s2==0&&++misses<5)return wait();
        if(s2!=200||!d2)return report('error',d.id);
        misses=0;
        if(d2.state=='waiting')return wait();
        report(d2.state,d.id);
      })},1000)})();
    });
  }
  function why(state,text){
    return state=='rejected'?T.rejected:state=='unanswered'?T.unanswered:state=='withdrawn'?T.withdrawn:state=='cooling'?T.cooling:text||T.unreachable;
  }

  // First, the person with the phone lets this browser in. Until then it shows nothing.
  function gate(){
    byId('gate').hidden=false;byId('gateActs').hidden=true;byId('gateTitle').textContent=T.waiting;byId('gateText').textContent=fmt(T.letIn,device);
    ask({kind:'open'},function(state,id,text){
      if(state=='waiting')return;
      if(state=='accepted'){byId('gate').hidden=true;byId('main').hidden=false;poll();return}
      byId('gateTitle').textContent=T.notLetIn;byId('gateText').textContent=why(state,text);byId('gateActs').hidden=false;
    });
  }
  byId('gateAgain').addEventListener('click',gate);

  var shown='',failures=0,offered='',offerNow=null,tone=null;
  function poll(){
    send('GET','api/files',null,function(s,d){
      if(s==403){byId('main').hidden=true;gate();return}
      if(s!=200||!d){
        if(++failures<3){setTimeout(poll,2500);return}
        summary.textContent=T.linkStopped;summary.className='sub bad';filesEl.textContent='';offerHide();return;
      }
      failures=0;render(d);offerShow(d.offer);setTimeout(poll,2500);
    });
  }
  function render(d){
    var key=JSON.stringify([d.files,d.notes,d.rejected]);if(key==shown)return;shown=key;
    filesEl.textContent='';notesEl.textContent='';
    (d.notes||[]).forEach(function(text){notesEl.appendChild(el('div','say',text))});
    notesEl.hidden=!(d.notes||[]).length;
    var total=0;
    d.files.forEach(function(f){
      total+=f.size;
      var parts=f.name.split('/'),leaf=parts.pop();
      var r=row(leaf,(parts.length?parts.join('/')+' · ':'')+size(f.size));
      var a=el('a','btn',T.download);a.href='d/'+encodeURIComponent(f.id);a.setAttribute('download',leaf);r.row.appendChild(a);
      filesEl.appendChild(r.row);
    });
    filesEl.hidden=!d.files.length&&!notesEl.hidden;
    if(!d.files.length)filesEl.appendChild(el('div','empty',T.nothingAccepted));
    all.hidden=d.files.length<2;
    summary.textContent=d.files.length?count(T.files,d.files.length,size(total)):T.canStillSend;
    byId('rejectedLine').hidden=!d.rejected;
    byId('rejectedText').textContent=d.rejected?count(T.youRejected,d.rejected):'';
  }

  // The phone wants to send something. A prompt over the page, the tab's title, and a tone if it's allowed one.
  function words(o){
    var files=o.count?count(T.files,o.count,size(o.bytes)):'',notes=o.notes?count(T.offerNotes,o.notes):'';
    return files&&notes?fmt(T.offerBoth,files,notes):files||notes;
  }
  function offerShow(o){
    if(!o){offerHide();return}
    var key=o.ids.join(',');if(key==offered)return;offered=key;offerNow=o;
    byId('offerTitle').textContent=fmt(T.offerTitle,device,words(o));
    var more=o.count-o.names.length,names=o.names.join(', ');
    byId('offerNames').textContent=more>0?fmt(T.namesMore,names,more):names;
    byId('offer').hidden=false;
    document.title=fmt(T.tabOffer,device);
    beep();
  }
  function offerHide(){byId('offer').hidden=true;offered='';offerNow=null;document.title=device}
  function fetchNow(o){
    if(!o.fileIds.length)return;
    var a=el('a');a.setAttribute('download','');a.href=o.fileIds.length==1?'d/'+encodeURIComponent(o.fileIds[0]):'zip?ids='+o.fileIds.join(',');
    document.body.appendChild(a);a.click();a.remove();
  }
  byId('offerAccept').addEventListener('click',function(){var o=offerNow;if(!o)return;offerHide();send('POST','offer',{accept:o.ids},function(s){if(s==200)fetchNow(o);shown=''})});
  byId('offerReject').addEventListener('click',function(){var o=offerNow;if(!o)return;offerHide();send('POST','offer',{reject:o.ids},function(){shown=''})});
  byId('changeMind').addEventListener('click',function(){send('POST','offer',{undo:true},function(){shown=''})});
  function beep(){
    try{
      var C=window.AudioContext||window.webkitAudioContext;if(!C)return;
      tone=tone||new C();if(tone.state=='suspended')tone.resume();
      var o=tone.createOscillator(),g=tone.createGain();o.frequency.value=880;g.gain.value=.06;o.connect(g);g.connect(tone.destination);o.start();o.stop(tone.currentTime+.18);
    }catch(e){}
  }

  // Sending files: names and sizes first, and nothing else until the phone says yes.
  var queue=[],busy=false,batch=[],batches=[],asking=false;
  function addOne(f,path){var r=row(path,size(f.size));uploads.hidden=false;uploads.appendChild(r.row);batch.push({file:f,path:path,r:r})}
  function add(list){for(var i=0;i<list.length;i++){addOne(list[i],list[i].webkitRelativePath||list[i].name)}flush()}
  function flush(){if(batch.length){batches.push(batch);batch=[]}askNext()}
  function tell(b,text,bad){b.forEach(function(u){u.r.meta.textContent=text;u.r.meta.className=bad?'meta bad':'meta'})}
  function clearButton(u){if(u.btn){u.btn.remove();u.btn=null}}
  function askNext(){
    if(asking||!batches.length)return;
    asking=true;
    var b=batches.shift(),lead=b[0];
    tell(b,T.waitingAccept);
    ask({kind:'files',files:b.map(function(u){return {name:u.path,size:u.file.size}})},function(state,id,text){
      clearButton(lead);
      if(state=='waiting'){
        lead.btn=el('button','',T.cancel);
        lead.btn.addEventListener('click',function(){send('POST','ask/'+encodeURIComponent(id)+'/cancel',{})});
        lead.r.row.appendChild(lead.btn);
        return;
      }
      asking=false;
      if(state=='accepted'){
        b.forEach(function(u){u.ask=id;u.r.meta.className='meta';u.r.meta.textContent=size(u.file.size);queue.push(u)});
        next();
      }else{
        tell(b,why(state,text),true);
        lead.btn=el('button','',T.tryAgain);
        lead.btn.addEventListener('click',function(){clearButton(lead);batches.push(b);askNext()});
        lead.r.row.appendChild(lead.btn);
      }
      askNext();
    });
  }
  function fail(u,text,again){
    u.r.meta.textContent=text;u.r.meta.className='meta bad';
    u.btn=el('button','',T.tryAgain);
    u.btn.addEventListener('click',function(){clearButton(u);u.r.meta.className='meta';u.r.meta.textContent=size(u.file.size);if(again){batches.push([u]);askNext()}else{queue.push(u);next()}});
    u.r.row.appendChild(u.btn);
  }
  function next(){
    if(busy||!queue.length)return;busy=true;
    var u=queue.shift(),x=new XMLHttpRequest(),bar=el('div','bar'),fill=el('i');bar.appendChild(fill);u.r.name.appendChild(bar);
    x.open('POST','upload?name='+encodeURIComponent(u.path)+'&ask='+encodeURIComponent(u.ask));
    x.upload.onprogress=function(e){if(e.lengthComputable)fill.style.width=(100*e.loaded/e.total)+'%'};
    x.onload=function(){busy=false;bar.remove();if(x.status==200){var d=JSON.parse(x.responseText);u.r.meta.textContent=fmt(T.savedAs,d.name,size(d.size))}else{fail(u,x.responseText||fmt(T.failedStatus,x.status),x.status==403)}next()};
    x.onerror=function(){busy=false;bar.remove();fail(u,T.connectionLost,false);next()};
    x.send(u.file);
  }
  // A folder dropped on the page only shows its contents to someone who walks it.
  function walk(entry,done){
    if(entry.isFile){entry.file(function(f){addOne(f,entry.fullPath.replace(/^\//,''));done()},done);return}
    var reader=entry.createReader(),pending=0,finished=false;
    function check(){if(finished&&!pending)done()}
    (function more(){reader.readEntries(function(list){if(!list.length){finished=true;check();return}
      list.forEach(function(c){pending++;walk(c,function(){pending--;check()})});more()},function(){finished=true;check()})})();
  }
  function dropped(e){
    var items=e.dataTransfer&&e.dataTransfer.items;
    if(!items||!items.length||!items[0].webkitGetAsEntry){if(e.dataTransfer&&e.dataTransfer.files)add(e.dataTransfer.files);return}
    var left=items.length;for(var i=0;i<items.length;i++){var entry=items[i].webkitGetAsEntry();if(entry){walk(entry,function(){if(!--left)flush()})}else{var loose=items[i].getAsFile&&items[i].getAsFile();if(loose)addOne(loose,loose.name);if(!--left)flush()}}
  }
  var folder=byId('pickFolder');
  byId('folderLine').hidden=!('webkitdirectory' in folder)||/Android|iPhone|iPad/.test(navigator.userAgent);
  folder.addEventListener('change',function(e){add(e.target.files);e.target.value=''});
  byId('pick').addEventListener('change',function(e){add(e.target.files);e.target.value=''});

  // A note asks the same way. The words stay here until the phone says yes.
  var wordsEl=byId('words'),wordsResult=byId('wordsResult'),sendWords=byId('sendWords');
  function say(text,bad){wordsResult.hidden=false;wordsResult.className=bad?'note bad':'note';wordsResult.textContent=text}
  sendWords.addEventListener('click',function(){
    var text=wordsEl.value;if(!text.trim())return;
    sendWords.disabled=true;say(T.waitingAccept);
    ask({kind:'note',size:new Blob([text]).size},function(state,id,why2){
      if(state=='waiting')return;
      if(state!='accepted'){sendWords.disabled=false;say(why(state,why2),true);return}
      var x=new XMLHttpRequest();x.open('POST','text?ask='+encodeURIComponent(id));
      x.onload=function(){sendWords.disabled=false;if(x.status==200){say(T.noteSent);wordsEl.value=''}else{say(x.responseText||T.noteFailed,true)}};
      x.onerror=function(){sendWords.disabled=false;say(T.connectionLostNote,true)};
      x.send(text);
    });
  });
  var drop=byId('drop');
  ['dragenter','dragover'].forEach(function(t){drop.addEventListener(t,function(e){e.preventDefault();drop.classList.add('over')})});
  ['dragleave','drop'].forEach(function(t){drop.addEventListener(t,function(e){e.preventDefault();drop.classList.remove('over')})});
  drop.addEventListener('drop',dropped);
  gate();
})();
</script>
</body>
</html>
""".trimIndent()
}
