package com.nuvio.tv.core.server

object DiobotWebPage {
    fun getHtml(): String = """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Diobot - IA Concierge</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0d0d0d;color:#fff;height:100vh;display:flex;flex-direction:column;overflow:hidden}
.header{padding:16px 20px;background:#1a1a1a;border-bottom:1px solid #2a2a2a;display:flex;align-items:center;gap:12px}
.header h1{font-size:20px;color:#4FC3F7}
.header span{font-size:13px;color:rgba(255,255,255,0.4)}
.messages{flex:1;overflow-y:auto;padding:16px 20px;display:flex;flex-direction:column;gap:12px}
.msg{max-width:85%;padding:10px 14px;border-radius:16px;font-size:15px;line-height:1.4;word-wrap:break-word;white-space:pre-line}
.msg.user{align-self:flex-end;background:#1A5276;border-bottom-right-radius:4px}
.msg.bot{align-self:flex-start;background:#1e1e1e;border-bottom-left-radius:4px}
.suggestions{display:flex;gap:10px;overflow-x:auto;padding:8px 0;-webkit-overflow-scrolling:touch}
.suggestions::-webkit-scrollbar{display:none}
.card{min-width:130px;max-width:130px;background:#1e1e1e;border-radius:12px;overflow:hidden;flex-shrink:0;border:2px solid transparent}
.card .poster{width:100%;height:170px;object-fit:cover;background:#2a2a2e;display:flex;align-items:center;justify-content:center;font-size:32px}
.card .info{padding:6px 8px}
.card .title{font-size:12px;color:#fff;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.card .type{font-size:10px;color:#4FC3F7;margin-top:2px}
.card .actions{display:flex;flex-direction:column;gap:4px;padding:4px 8px 8px}
.card .actions button{width:100%;padding:7px;border:none;border-radius:6px;font-size:11px;cursor:pointer;font-weight:600}
.btn-play{background:#4FC3F7;color:#000}
.btn-save{background:#66BB6A;color:#000}
.btn-detail{background:#2a2a2e;color:#fff}
.typing{align-self:flex-start;display:flex;gap:4px;padding:8px}
.typing span{width:8px;height:8px;background:#4FC3F7;border-radius:50%;animation:bounce 0.6s infinite alternate}
.typing span:nth-child(2){animation-delay:0.2s}
.typing span:nth-child(3){animation-delay:0.4s}
@keyframes bounce{to{opacity:0.3;transform:translateY(-4px)}}
.input-area{padding:12px 20px;background:#1a1a1a;border-top:1px solid #2a2a2a;display:flex;gap:10px;align-items:center}
.input-area input{flex:1;background:#2a2a2e;border:none;border-radius:20px;padding:12px 16px;color:#fff;font-size:15px;outline:none}
.input-area input::placeholder{color:rgba(255,255,255,0.3)}
.mic-btn{width:48px;height:48px;border-radius:50%;border:none;background:#2a2a2e;color:#fff;font-size:20px;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all 0.2s}
.mic-btn.listening{background:#4FC3F7;animation:pulse 1s infinite}
.mic-btn.disabled{opacity:0.4;pointer-events:none}
.send-btn{width:48px;height:48px;border-radius:50%;border:none;background:#4FC3F7;color:#000;font-size:20px;cursor:pointer;display:flex;align-items:center;justify-content:center}
.send-btn:disabled{opacity:0.4}
@keyframes pulse{0%,100%{transform:scale(1)}50%{transform:scale(1.1)}}
.welcome{text-align:center;padding:40px 20px;color:rgba(255,255,255,0.5)}
.welcome .emoji{font-size:48px;margin-bottom:12px}
.welcome h2{color:#fff;margin-bottom:8px;font-size:18px}
.welcome p{font-size:14px;line-height:1.5}
.toast{position:fixed;top:20px;left:50%;transform:translateX(-50%);background:#66BB6A;color:#000;padding:10px 20px;border-radius:8px;font-size:14px;font-weight:600;z-index:100;opacity:0;transition:opacity 0.3s}
.toast.show{opacity:1}
</style>
</head>
<body>
<div class="header">
<h1>🤖 Diobot</h1>
<span>IA Concierge</span>
</div>
<div class="messages" id="messages">
<div class="welcome">
<div class="emoji">🤖</div>
<h2>Olá! Eu sou o Diobot</h2>
<p>Me diga o que você quer assistir.<br>Posso buscar, salvar na biblioteca e reproduzir na TV.<br>Use o microfone ou digite.</p>
</div>
</div>
<div id="toast" class="toast"></div>
<div class="input-area">
<button class="mic-btn" id="micBtn" onclick="toggleMic()">🎤</button>
<input type="text" id="textInput" placeholder="O que você quer assistir?" onkeydown="if(event.key==='Enter')sendText()">
<button class="send-btn" id="sendBtn" onclick="sendText()">➤</button>
</div>
</body>
<script>
const msgs=document.getElementById('messages');
const input=document.getElementById('textInput');
const micBtn=document.getElementById('micBtn');
let isListening=false;
let recognition=null;
let processing=false;

if('webkitSpeechRecognition' in window||'SpeechRecognition' in window){
  const SR=window.SpeechRecognition||window.webkitSpeechRecognition;
  recognition=new SR();
  recognition.lang='pt-BR';
  recognition.continuous=false;
  recognition.interimResults=true;
  recognition.onresult=function(e){
    let final='';let interim='';
    for(let i=e.resultIndex;i<e.results.length;i++){
      if(e.results[i].isFinal)final+=e.results[i][0].transcript;
      else interim+=e.results[i][0].transcript;
    }
    if(interim)input.value=interim;
    if(final){input.value=final;sendText();}
  };
  recognition.onend=function(){isListening=false;micBtn.classList.remove('listening');micBtn.textContent='🎤';};
  recognition.onerror=function(){isListening=false;micBtn.classList.remove('listening');micBtn.textContent='🎤';};
}else{micBtn.classList.add('disabled');}

function toggleMic(){
  if(!recognition)return;
  if(isListening){recognition.stop();isListening=false;micBtn.classList.remove('listening');micBtn.textContent='🎤';}
  else{recognition.start();isListening=true;micBtn.classList.add('listening');micBtn.textContent='⏹';}
}

function addMsg(text,isUser){
  const w=msgs.querySelector('.welcome');if(w)w.remove();
  const d=document.createElement('div');d.className='msg '+(isUser?'user':'bot');d.textContent=text;
  msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight;
}

function showToast(text){
  const t=document.getElementById('toast');t.textContent=text;t.classList.add('show');
  setTimeout(()=>t.classList.remove('show'),2500);
}

function addSuggestions(suggestions){
  if(!suggestions||!suggestions.length)return;
  const row=document.createElement('div');row.className='suggestions';
  suggestions.forEach(s=>{
    const card=document.createElement('div');card.className='card';
    const itemId=s.imdbId||(s.tmdbId?'tmdb:'+s.tmdbId:'');
    const posterUrl=s.poster||'';
    card.innerHTML=
      '<div class="poster" style="'+(posterUrl?'background:url('+posterUrl+') center/cover no-repeat':'')+'">'+(!posterUrl?(s.type==='movie'?'🎬':'📺'):'')+'</div>'+
      '<div class="info"><div class="title">'+esc(s.title)+'</div><div class="type">'+(s.type==='movie'?'Filme':'Série')+'</div></div>'+
      '<div class="actions">'+
      '<button class="btn-play" onclick="sendCmd(\'play\',\''+esc(itemId)+'\',\''+esc(s.type)+'\',\''+esc(s.title)+'\')">▶ Reproduzir</button>'+
      '<button class="btn-save" onclick="sendCmd(\'save\',\''+esc(itemId)+'\',\''+esc(s.type)+'\',\''+esc(s.title)+'\')">💾 Salvar</button>'+
      '<button class="btn-detail" onclick="sendCmd(\'detail\',\''+esc(itemId)+'\',\''+esc(s.type)+'\',\''+esc(s.title)+'\')">📋 Detalhes</button>'+
      '</div>';
    row.appendChild(card);
  });
  msgs.appendChild(row);msgs.scrollTop=msgs.scrollHeight;
}

function esc(s){return(s||'').replace(/'/g,"\\'").replace(/"/g,'&quot;');}

function showTyping(){
  const w=msgs.querySelector('.welcome');if(w)w.remove();
  const d=document.createElement('div');d.className='typing';d.id='typing';
  d.innerHTML='<span></span><span></span><span></span>';
  msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight;
}
function hideTyping(){const t=document.getElementById('typing');if(t)t.remove();}

async function sendText(){
  const text=input.value.trim();if(!text||processing)return;
  input.value='';processing=true;
  addMsg(text,true);showTyping();
  try{
    const r=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:text})});
    const data=await r.json();hideTyping();
    if(data.message)addMsg(data.message,false);
    if(data.suggestions)addSuggestions(data.suggestions);
  }catch(e){hideTyping();addMsg('Erro de conexão. A TV está ligada?',false);}
  processing=false;
}

async function sendCmd(action,itemId,itemType,title){
  try{
    await fetch('/api/command',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:action,itemId:itemId,itemType:itemType,title:title})});
    if(action==='play')addMsg('▶ Reproduzindo '+title+' na TV...',false);
    else if(action==='save'){showToast('💾 '+title+' salvo na biblioteca!');addMsg('💾 '+title+' salvo na biblioteca!',false);}
    else addMsg('📺 Abrindo '+title+' na TV...',false);
  }catch(e){addMsg('Erro ao enviar comando para a TV.',false);}
}
</script>
</html>
""".trimIndent()
}
