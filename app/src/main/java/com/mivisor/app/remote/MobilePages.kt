package com.mivisor.app.remote

/**
 * Páginas que ve el móvil: HTML/CSS/JS mínimos, sin frameworks ni recursos externos (funciona sin Internet).
 * El texto mostrado se escapa siempre: nunca se interpreta como HTML.
 */
object MobilePages {
    private const val STYLE = """
        *{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#060a1c;color:#f4f6ff;
        padding:20px;min-height:100vh}h1{font-size:15px;color:#22d3ee;margin:0 0 4px;letter-spacing:.08em;text-transform:uppercase}
        h2{font-size:22px;margin:0 0 16px}textarea,pre{width:100%;background:#0d1330;color:#f4f6ff;border:1px solid #1e2858;
        border-radius:12px;padding:14px;font-size:16px;font-family:ui-monospace,Menlo,monospace}textarea{min-height:160px;resize:vertical}
        pre{white-space:pre-wrap;word-break:break-all;font-size:12px;max-height:65vh;overflow:auto}
        button{width:100%;margin-top:14px;padding:16px;border:0;border-radius:12px;background:#8b5cf6;color:#fff;font-size:17px;font-weight:700}
        button:disabled{opacity:.5}.msg{margin-top:16px;font-size:16px}.ok{color:#34d399}.err{color:#fb7185}.hint{color:#a3aed6;font-size:13px}
    """

    fun input(remainingMs: Long): String = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>MiVisor</title><style>$STYLE</style></head><body>
<h1>MiVisor</h1><h2>Enviar código al televisor</h2>
<textarea id="t" placeholder="Pega o escribe aquí" autocapitalize="off" autocomplete="off" autocorrect="off" spellcheck="false" maxlength="16000"></textarea>
<button id="b">ENVIAR AL TELEVISOR</button>
<div id="m" class="msg"></div>
<p class="hint">El texto aparecerá en el formulario del televisor. Allí podrás revisarlo antes de guardar.
Esta página caduca en ${(remainingMs / 60_000).coerceAtLeast(1)} min.</p>
<script>
var b=document.getElementById('b'),t=document.getElementById('t'),m=document.getElementById('m');
b.onclick=function(){
  if(!t.value.trim()){m.className='msg err';m.textContent='Escribe o pega el código primero.';return;}
  b.disabled=true;m.className='msg';m.textContent='Enviando…';
  fetch(location.pathname.replace(/\/$/,'')+'/send',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:t.value})})
  .then(function(r){return r.json().catch(function(){return {ok:false};}).then(function(j){return {s:r.status,j:j};});})
  .then(function(x){
    if(x.s===200&&x.j.ok){m.className='msg ok';m.textContent='✓ Texto enviado al televisor';t.disabled=true;}
    else if(x.s===413){m.className='msg err';m.textContent='El texto es demasiado largo.';b.disabled=false;}
    else{m.className='msg err';m.textContent='Esta sesión ya no está disponible. Genera un QR nuevo en el televisor.';}
  }).catch(function(){m.className='msg err';m.textContent='No se pudo contactar con el televisor. ¿Estáis en la misma red?';b.disabled=false;});
};
</script></body></html>"""

    fun show(text: String): String = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>MiVisor · Registro</title><style>$STYLE</style></head><body>
<h1>MiVisor</h1><h2>Registro de errores</h2>
<pre id="p">${escape(text)}</pre>
<button id="b">COPIAR</button><div id="m" class="msg"></div>
<p class="hint">Cópialo y envíalo a quien te ayuda con MiVisor. No contiene tus códigos ni contraseñas.</p>
<script>
document.getElementById('b').onclick=function(){
  var r=document.createRange();r.selectNodeContents(document.getElementById('p'));
  var s=window.getSelection();s.removeAllRanges();s.addRange(r);
  var ok=false;try{ok=document.execCommand('copy');}catch(e){}
  var m=document.getElementById('m');m.className='msg '+(ok?'ok':'');m.textContent=ok?'✓ Copiado':'Texto seleccionado: mantén pulsado y copia.';
};
</script></body></html>"""

    fun unavailable(): String = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>MiVisor</title><style>$STYLE</style></head><body>
<h1>MiVisor</h1><h2>Esta sesión ya no está disponible.</h2>
<p class="hint">Genera un QR nuevo en el televisor.</p></body></html>"""

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
