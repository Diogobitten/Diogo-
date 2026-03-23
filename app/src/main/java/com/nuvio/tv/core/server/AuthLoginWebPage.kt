package com.nuvio.tv.core.server

object AuthLoginWebPage {

    fun getHtml(supabaseUrl: String, supabaseAnonKey: String): String = """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>Diogo+ — Login</title>
<style>
*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background:#000;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center}
.card{width:100%;max-width:400px;padding:2.5rem 2rem;margin:1rem}
.logo{text-align:center;margin-bottom:2rem}
.logo h1{font-size:1.8rem;font-weight:700;letter-spacing:-0.02em}
.logo p{font-size:0.85rem;color:rgba(255,255,255,0.4);margin-top:0.5rem}
.tabs{display:flex;gap:0;margin-bottom:2rem;border:1px solid rgba(255,255,255,0.12);border-radius:100px;overflow:hidden}
.tab{flex:1;padding:0.75rem;text-align:center;font-size:0.85rem;font-weight:500;cursor:pointer;transition:all 0.2s;background:transparent;color:rgba(255,255,255,0.5);border:none}
.tab.active{background:#fff;color:#000}
.field{margin-bottom:1.25rem}
.field label{display:block;font-size:0.75rem;font-weight:500;color:rgba(255,255,255,0.3);letter-spacing:0.08em;text-transform:uppercase;margin-bottom:0.5rem}
.field input{width:100%;background:transparent;border:1px solid rgba(255,255,255,0.12);border-radius:100px;padding:0.875rem 1.25rem;color:#fff;font-family:inherit;font-size:0.9rem;transition:border-color 0.3s}
.field input:focus{outline:none;border-color:rgba(255,255,255,0.4)}
.field input::placeholder{color:rgba(255,255,255,0.2)}
.btn-submit{width:100%;padding:1rem;background:#fff;color:#000;border:none;border-radius:100px;font-family:inherit;font-size:0.95rem;font-weight:600;cursor:pointer;transition:all 0.2s;margin-top:0.5rem}
.btn-submit:hover{opacity:0.9}
.btn-submit:active{transform:scale(0.98)}
.btn-submit:disabled{opacity:0.3;cursor:not-allowed}
.msg{text-align:center;font-size:0.85rem;margin-top:1.25rem;min-height:1.5rem}
.msg.error{color:rgba(207,102,121,0.9)}
.msg.success{color:#7CFF9B}
.done{text-align:center;padding:3rem 1rem}
.done-icon{font-size:3rem;margin-bottom:1rem}
.done h2{font-size:1.3rem;font-weight:600;margin-bottom:0.5rem}
.done p{font-size:0.85rem;color:rgba(255,255,255,0.4)}
</style>
</head>
<body>
<div class="card" id="loginCard">
  <div class="logo">
    <h1>Diogo+</h1>
    <p>Entre ou crie sua conta</p>
  </div>
  <div class="tabs">
    <button class="tab active" id="tabLogin" onclick="switchTab('login')">Entrar</button>
    <button class="tab" id="tabSignup" onclick="switchTab('signup')">Criar conta</button>
  </div>
  <form id="authForm" onsubmit="handleSubmit(event)">
    <div class="field">
      <label>Email</label>
      <input type="email" id="email" placeholder="seu@email.com" required autocomplete="email">
    </div>
    <div class="field">
      <label>Senha</label>
      <input type="password" id="password" placeholder="••••••••" required autocomplete="current-password" minlength="6">
    </div>
    <button type="submit" class="btn-submit" id="submitBtn">Entrar</button>
  </form>
  <div class="msg" id="msg"></div>
</div>

<div class="card" id="doneCard" style="display:none">
  <div class="done">
    <div class="done-icon">✓</div>
    <h2>Conectado!</h2>
    <p>Volte para a TV. O login foi realizado com sucesso.</p>
  </div>
</div>

<script>
var SUPABASE_URL = '${supabaseUrl}';
var SUPABASE_KEY = '${supabaseAnonKey}';
var mode = 'login';

function switchTab(m) {
  mode = m;
  document.getElementById('tabLogin').className = 'tab' + (m === 'login' ? ' active' : '');
  document.getElementById('tabSignup').className = 'tab' + (m === 'signup' ? ' active' : '');
  document.getElementById('submitBtn').textContent = m === 'login' ? 'Entrar' : 'Criar conta';
  document.getElementById('msg').textContent = '';
  document.getElementById('msg').className = 'msg';
}

async function handleSubmit(e) {
  e.preventDefault();
  var email = document.getElementById('email').value.trim();
  var password = document.getElementById('password').value;
  var msgEl = document.getElementById('msg');
  var btn = document.getElementById('submitBtn');

  if (!email || !password) return;
  btn.disabled = true;
  msgEl.textContent = mode === 'login' ? 'Entrando...' : 'Criando conta...';
  msgEl.className = 'msg';

  try {
    var endpoint = mode === 'login'
      ? SUPABASE_URL + '/auth/v1/token?grant_type=password'
      : SUPABASE_URL + '/auth/v1/signup';

    var res = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'apikey': SUPABASE_KEY
      },
      body: JSON.stringify({ email: email, password: password })
    });

    var data = await res.json();

    if (!res.ok) {
      throw new Error(data.error_description || data.msg || data.message || 'Erro desconhecido');
    }

    if (mode === 'signup' && !data.access_token) {
      msgEl.textContent = 'Conta criada! Fazendo login...';
      msgEl.className = 'msg success';
      // Auto-login after signup
      var loginRes = await fetch(SUPABASE_URL + '/auth/v1/token?grant_type=password', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'apikey': SUPABASE_KEY
        },
        body: JSON.stringify({ email: email, password: password })
      });
      data = await loginRes.json();
      if (!loginRes.ok) {
        throw new Error(data.error_description || data.msg || 'Erro no login após cadastro');
      }
    }

    if (data.access_token && data.refresh_token) {
      msgEl.textContent = 'Conectando à TV...';
      msgEl.className = 'msg success';

      var cbRes = await fetch('/api/auth-callback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          access_token: data.access_token,
          refresh_token: data.refresh_token
        })
      });

      if (cbRes.ok) {
        document.getElementById('loginCard').style.display = 'none';
        document.getElementById('doneCard').style.display = 'block';
      } else {
        throw new Error('Falha ao enviar token para a TV');
      }
    } else {
      throw new Error('Resposta inesperada do servidor');
    }
  } catch (err) {
    msgEl.textContent = err.message;
    msgEl.className = 'msg error';
  } finally {
    btn.disabled = false;
  }
}
</script>
</body>
</html>
""".trimIndent()
}
