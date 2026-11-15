/*
 * Automation script: JMSMESSAGEADMIN   (language: groovy, no launch point)
 *
 * Call it in a browser while logged in to Maximo:
 *   https://<host>/maximo/api/script/JMSMESSAGEADMIN
 *   (or /maximo/oslc/script/JMSMESSAGEADMIN, on older 7.6.x)
 *
 * Single page application. The page is loaded once; everything else is a data request:
 *   (no resource)            -> minimal HTML page that loads the CSS and the JavaScript
 *   resource=css             -> stylesheet
 *   resource=js              -> JavaScript, builds the whole user interface
 *   resource=json & op=...   -> JSON data requests made by the JavaScript:
 *       op=queues                                              list of queues + user name
 *       op=queue    & queuename                                configuration of one queue
 *       op=count    & queuename & confactname [& selector]     number of messages
 *       op=messages & queuename & confactname & selector & maxcount
 *                                                              messages (headers, properties, body)
 *       op=delete   & queuename & id=... & id=...   (POST)     delete messages by JMSMessageID
 *   On failure a JSON request returns {"error": "..."}.
 *
 * Messages are read with plain JMS (InitialContext + QueueBrowser) and deleted with
 * MicServiceRemote.deleteQueueData(). javax.jms / jakarta.jms classes are used dynamically.
 */

import groovy.json.JsonOutput
import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.naming.InitialContext

import psdi.iface.jms.MaxQueueCache
import psdi.iface.mic.MicServiceRemote
import psdi.server.MXServer

// Access is controlled by the script's own security in Maximo.

// ---------------------------------------------------------------- request parameters

Map<String, List<String>> parseQuery(String qs, Map<String, List<String>> into) {
	if (qs == null) return into
	qs = qs.trim()
	if (qs.isEmpty() || qs.startsWith("{") || qs.startsWith("[") || !qs.contains("=")) return into
	for (String pair : qs.split("&")) {
		if (pair == null || pair.isEmpty()) continue
		int idx = pair.indexOf('=')
		String k = URLDecoder.decode(idx >= 0 ? pair.substring(0, idx) : pair, "UTF-8")
		String v = idx >= 0 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : ""
		List<String> l = into.get(k)
		if (l == null) { l = new ArrayList<String>(); into.put(k, l) }
		l.add(v)
	}
	return into
}

/** Adds the keys of src that p does not have yet. */
void mergeParams(Map<String, List<String>> p, Map<String, List<String>> src) {
	src.each { k, v -> if (!p.containsKey(k) && v != null && !v.isEmpty()) p.put(k, v) }
}

def httpRequest() {
	try {
		def req = binding.hasVariable("request") ? binding.getVariable("request") : null
		return req?.getHttpServletRequest()
	} catch (Throwable t) {
		return null
	}
}

/**
 * Reads the request parameters from every place Maximo may put them.
 * Once the servlet container has called getParameter(), the form body of a POST is consumed and
 * "requestBody" is empty, but the servlet's getParameterMap() still has the form fields.
 */
Map<String, List<String>> readParams() {
	Map<String, List<String>> p = new LinkedHashMap<String, List<String>>()
	def http = httpRequest()

	// 1) servlet parameters = query string + form body
	if (http != null) {
		try {
			Map<String, List<String>> s = new LinkedHashMap<String, List<String>>()
			http.getParameterMap().each { k, v ->
				List<String> l = new ArrayList<String>()
				if (v instanceof String[]) { for (String x : (String[]) v) l.add(x) } else if (v != null) l.add(v.toString())
				s.put(k.toString(), l)
			}
			mergeParams(p, s)
		} catch (Throwable t) { }
	}

	// 2) raw POST body (application/x-www-form-urlencoded), if Maximo passes it
	if (binding.hasVariable("requestBody")) {
		def rb = binding.getVariable("requestBody")
		if (rb instanceof byte[]) rb = new String((byte[]) rb, "UTF-8")
		Map<String, List<String>> b = new LinkedHashMap<String, List<String>>()
		if (rb != null) parseQuery(rb.toString(), b)
		mergeParams(p, b)
	}

	// 3) query string
	Map<String, List<String>> q = new LinkedHashMap<String, List<String>>()
	try { parseQuery(http?.getQueryString(), q) } catch (Throwable t) { }
	if (q.isEmpty() && binding.hasVariable("request")) {
		def req = binding.getVariable("request")
		for (String k : ["resource", "op", "queuename", "confactname", "selector", "maxcount"]) {
			try {
				def v = req.getQueryParam(k)
				if (v != null) q.put(k, [v.toString()])
			} catch (Throwable t) { }
		}
	}
	mergeParams(p, q)
	return p
}

String p1(Map<String, List<String>> p, String k, String dflt) {
	List<String> l = p.get(k)
	return (l == null || l.isEmpty() || l.get(0) == null) ? dflt : l.get(0)
}

List<String> pAll(Map<String, List<String>> p, String k) {
	List<String> l = p.get(k)
	return l == null ? new ArrayList<String>() : l.findAll { it != null && !it.trim().isEmpty() }.collect { it.trim() }
}

// ---------------------------------------------------------------- helpers

/** QueueConfig has no documented getters for everything, so read its toString(): queueName=..~confactName=..~... */
Map<String, String> parseCfg(def cfg) {
	Map<String, String> m = new LinkedHashMap<String, String>()
	if (cfg == null) return m
	String s = cfg.toString()
	int qi = s.indexOf("queueName=")
	if (qi > 0) s = s.substring(qi)
	for (String part : s.split("~")) {
		if (part.startsWith("initialCtxFactory") && !part.startsWith("initialCtxFactory=")) {
			m.put("initialCtxFactory", part.substring("initialCtxFactory".length()))
			continue
		}
		int e = part.indexOf('=')
		if (e > 0) m.put(part.substring(0, e), part.substring(e + 1))
	}
	return m
}

boolean isA(def o, String simpleName) {
	if (o == null) return false
	for (String pkg : ["javax.jms.", "jakarta.jms."]) {
		for (ClassLoader cl : [o.getClass().getClassLoader(), Thread.currentThread().getContextClassLoader()]) {
			try {
				if (Class.forName(pkg + simpleName, false, cl).isInstance(o)) return true
			} catch (Throwable t) { }
		}
	}
	return false
}

String decodeBody(byte[] b) {
	if (b == null) return ""
	try {
		if (b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
			b = new GZIPInputStream(new ByteArrayInputStream(b)).getBytes()
		} else if (b.length > 2 && (b[0] & 0xff) == 0x78 && ((b[1] & 0xff) == 0x9c || (b[1] & 0xff) == 0xda || (b[1] & 0xff) == 0x01)) {
			b = new InflaterInputStream(new ByteArrayInputStream(b)).getBytes()
		}
	} catch (Throwable t) { /* not compressed after all */ }
	return new String(b, "UTF-8")
}

String readBody(def m) {
	if (isA(m, "TextMessage")) return m.getText() ?: ""
	if (isA(m, "BytesMessage")) {
		m.reset()
		long len = m.getBodyLength()
		byte[] bytes = new byte[(int) len]
		m.readBytes(bytes)
		return decodeBody(bytes)
	}
	if (isA(m, "ObjectMessage")) {
		def o = m.getObject()
		if (o instanceof byte[]) return decodeBody((byte[]) o)
		return String.valueOf(o)
	}
	return "[" + m.getClass().getName() + " - body type not supported]"
}

/** Keeps strings, numbers and booleans as they are (so the JavaScript knows the type), everything else as text. */
def jsonValue(def v) {
	if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) return v
	return v.toString()
}

String safeFileName(String s) {
	return (s == null ? "" : s).replaceAll("[^A-Za-z0-9._-]", "_")
}

/** Same naming as Maximo's "view queue data": SENDER_INTERFACE_MEAMessageID.xml (falls back to JMSMessageID). */
String messageFileName(Map props, String jmsId) {
	def sender = props.get("SENDER")
	def iface = props.get("INTERFACE")
	def meaId = props.get("MEAMessageID")
	List<String> parts = []
	if (sender) parts.add(sender.toString())
	if (iface) parts.add(iface.toString())
	parts.add(meaId ? meaId.toString() : jmsId)
	return safeFileName(parts.join("_")) + ".xml"
}

Map msgInfo(def m) {
	SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
	Map<String, Object> props = new LinkedHashMap<String, Object>()
	long ts = m.getJMSTimestamp()
	props.put("JMSTimestamp", ts > 0 ? df.format(new Date(ts)) : "")
	props.put("JMSCorrelationID", m.getJMSCorrelationID())
	props.put("JMSPriority", m.getJMSPriority())
	props.put("JMSRedelivered", m.getJMSRedelivered())
	props.put("JMSDeliveryMode", m.getJMSDeliveryMode() == 2 ? "PERSISTENT" : "NON_PERSISTENT")
	List<String> names = []
	def en = m.getPropertyNames()
	while (en.hasMoreElements()) names.add(en.nextElement().toString())
	names.sort().each { n -> props.put(n, jsonValue(m.getObjectProperty(n))) }
	String body
	try { body = readBody(m) } catch (Throwable t) { body = "[cannot read body: " + t + "]" }
	String id = m.getJMSMessageID()
	return [id: id, ts: ts, file: messageFileName(props, id), props: props, body: body]
}

// ---------------------------------------------------------------- JMS

Map openJms(String queueName, String cf) {
	def cfg = MaxQueueCache.getInstance().getQueueConfig(queueName)
	if (cfg == null) throw new IllegalArgumentException("Queue is not configured in Maximo: " + queueName)
	if (cf == null || cf.trim().isEmpty() || cf == "null") {
		throw new IllegalArgumentException("No connection factory (confactName) for queue " + queueName)
	}
	cf = cf.trim()

	Properties env = null
	try { env = cfg.getEnv() } catch (Throwable t) { }
	InitialContext ctx = (env != null && !env.isEmpty()) ? new InitialContext(env) : new InitialContext()

	Map j = [ctx: ctx]
	try {
		def qcf = ctx.lookup(cf)
		if (qcf == null) throw new IllegalStateException("JNDI lookup returned null for connection factory " + cf)
		def queue = ctx.lookup(queueName)
		if (queue == null) throw new IllegalStateException("JNDI lookup returned null for queue " + queueName)

		String cred = MXServer.getMXServer().getProperty("mxe.int.queueusercred")
		def conn
		if (cred != null && cred.contains(":")) {
			int i = cred.indexOf(':')
			conn = qcf.createQueueConnection(cred.substring(0, i), cred.substring(i + 1))
		} else {
			conn = qcf.createQueueConnection()
		}
		j.conn = conn
		j.session = conn.createQueueSession(false, 1)   // non-transacted, AUTO_ACKNOWLEDGE
		j.queue = queue
		conn.start()
		return j
	} catch (Throwable t) {
		closeJms(j)
		throw t
	}
}

void closeJms(Map j) {
	if (j == null) return
	try { j.session?.close() } catch (Throwable t) { }
	try { j.conn?.close() } catch (Throwable t) { }
	try { j.ctx?.close() } catch (Throwable t) { }
}

/** countOnly: counts every matching message. Otherwise stops after max messages (max < 0 = all). */
Map browse(String queueName, String confactName, String selector, int max, boolean countOnly) {
	Map j = null
	def browser = null
	try {
		j = openJms(queueName, confactName)
		browser = (selector == null || selector.trim().isEmpty()) ?
				j.session.createBrowser(j.queue) : j.session.createBrowser(j.queue, selector.trim())
		def en = browser.getEnumeration()
		int total = 0
		List<Map> msgs = []
		boolean more = false
		while (en.hasMoreElements()) {
			def m = en.nextElement()
			if (countOnly) { total++; continue }
			if (max >= 0 && msgs.size() >= max) { more = true; break }
			msgs.add(msgInfo(m))
			total++
		}
		return [total: total, messages: msgs, more: more]
	} finally {
		try { browser?.close() } catch (Throwable t) { }
		closeJms(j)
	}
}

/**
 * Deletes messages with Maximo's own MIC service, one call per message:
 * deleteQueueData(queueName, "JMSMessageID = '<id>'", 1, null)
 */
Map deleteMessages(String queueName, List<String> ids) {
	MicServiceRemote mic = (MicServiceRemote) MXServer.getMXServer().lookup("MIC")
	int deleted = 0
	List<String> notFound = []
	List<String> failed = []
	for (String id : new LinkedHashSet<String>(ids)) {
		String sel = "JMSMessageID = '" + id.replace("'", "''") + "'"
		try {
			def r = mic.deleteQueueData(queueName, sel, 1, null)
			// if the method returns the number of deleted messages, use it; otherwise count the call as done
			if (r instanceof Number && ((Number) r).intValue() <= 0) notFound.add(id) else deleted++
		} catch (Throwable t) {
			service.log_error("JMSMESSAGEADMIN: deleteQueueData failed for " + queueName + ", " + sel + ": " + t)
			failed.add(id + " (" + t + ")")
		}
	}
	return [deleted: deleted, notFound: notFound, failed: failed]
}

List<Map> queueList() {
	def all = MaxQueueCache.getInstance().getQueueConfig()
	def values = (all instanceof Map) ? ((Map) all).values() : all
	List<Map> rows = []
	values.each { cfg ->
		Map<String, String> c = parseCfg(cfg)
		String q = c.get("queueName")
		if (!q) return
		String mail = c.get("emailaddr")
		rows.add([
				queueName  : q,
				confactName: c.get("confactName") == "null" ? "" : c.get("confactName"),
				inbound    : "true".equals(c.get("inbound")),
				sequential : "true".equals(c.get("sequential")),
				errorQueue : q.toLowerCase().endsWith("err"),
				emailaddr  : (mail == null || mail == "null") ? "" : mail
		])
	}
	rows.sort { ((String) it.queueName).toLowerCase() }
	return rows
}

// ---------------------------------------------------------------- JSON requests

Map handleJson(Map<String, List<String>> params) {
	String op = p1(params, "op", "").trim().toLowerCase()
	String queueName = p1(params, "queuename", "").trim()
	String cf = p1(params, "confactname", "").trim()
	String selector = p1(params, "selector", "")

	if (op == "queues") {
		return [user: userInfo.getUserName(), queues: queueList()]
	}

	if (queueName.isEmpty()) throw new IllegalArgumentException("Parameter queuename is missing")
	def cfg = MaxQueueCache.getInstance().getQueueConfig(queueName)
	if (cfg == null) throw new IllegalArgumentException("Queue is not configured in Maximo: " + queueName)
	if (cf.isEmpty() || cf == "null") cf = parseCfg(cfg).get("confactName") ?: ""

	if (op == "queue") {
		return [queue: queueName, config: parseCfg(cfg)]
	}
	if (op == "count") {
		return [queue: queueName, count: browse(queueName, cf, selector, -1, true).total]
	}
	if (op == "messages") {
		int max = 1
		try { max = Integer.parseInt(p1(params, "maxcount", "1").trim()) } catch (Exception e) { max = 1 }
		Map r = browse(queueName, cf, selector, max, false)
		return [queue: queueName, more: r.more, messages: r.messages]
	}
	if (op == "delete") {
		def http = httpRequest()
		if (http != null && !"POST".equalsIgnoreCase(http.getMethod())) throw new IllegalStateException("Delete must be sent as POST")
		List<String> ids = pAll(params, "id")
		if (ids.isEmpty()) throw new IllegalArgumentException("No messages to delete")
		Map d = deleteMessages(queueName, ids)
		service.log_info("JMSMESSAGEADMIN: user " + userInfo.getUserName() + " deleted " + d.deleted + " message(s) from " + queueName + ": " + ids)
		return d
	}
	throw new IllegalArgumentException("Unknown op: " + op)
}

// ---------------------------------------------------------------- page, CSS, JavaScript

/** The only HTML page: everything else is built by the JavaScript. */
String pageHtml() {
	return '<!doctype html><html><head><meta charset="utf-8">' +
			'<meta name="viewport" content="width=device-width, initial-scale=1">' +
			'<title>JMS Message Admin</title>' +
			'<link rel="stylesheet" href="?resource=css">' +
			'<script src="?resource=js"></script>' +
			'</head><body><noscript>This page needs JavaScript.</noscript></body></html>'
}

/** Served with ?resource=css */
String cssCode() {
	return '''
 body{font-family:Segoe UI,Helvetica,Arial,sans-serif;font-size:13px;margin:16px;color:#1d2733;background:#f5f7fa}
 h1{font-size:20px;margin:0 0 12px} h2{font-size:16px;margin:20px 0 8px} h2.first{margin-top:0}
 a{color:#0f62fe;text-decoration:none} a:hover{text-decoration:underline}
 /* inline-block + min-width:100%: a card fills the page width, but grows with a wide table
    (the page then scrolls horizontally instead of the table) */
 .card{display:inline-block;min-width:100%;box-sizing:border-box;background:#fff;border:1px solid #dde3ea;border-radius:6px;padding:12px 16px;margin-bottom:14px}
 table{border-collapse:collapse;width:100%;background:#fff}
 th,td{border:1px solid #dde3ea;padding:4px 8px;text-align:left;vertical-align:top}
 th{background:#eef2f7;white-space:nowrap}
 tr:hover td{background:#f8fbff}
 .scroll{border:1px solid #dde3ea}
 .kv th{width:180px}
 .props{width:auto;min-width:50%} .props th{background:#f7f9fc}
 .muted{color:#6a7785} .nowrap{white-space:nowrap} .strong{font-weight:bold} .big{font-size:16px}
 .badge{display:inline-block;padding:1px 6px;border-radius:9px;font-size:11px;background:#e5ecf6}
 .in{background:#dcf2e3} .out{background:#fde9d8} .err{background:#fbdada;color:#9b1c1c}
 .msg{padding:8px 12px;border-radius:4px;margin-bottom:12px;display:flex;gap:12px;align-items:flex-start}
 .msg span{flex:1}
 .ok{background:#dcf2e3} .bad{background:#fbdada}
 label{margin-right:16px} input[type=text]{width:420px;max-width:100%} input[type=number]{width:90px}
 button{cursor:pointer;padding:3px 10px;border:1px solid #8a99a8;border-radius:4px;background:#fff}
 button:disabled{cursor:default;opacity:.6}
 button.primary{background:#0f62fe;color:#fff;border-color:#0f62fe}
 button.danger{color:#b00020;border-color:#b00020}
 button.mini{padding:0 6px;font-size:11px}
 pre{white-space:pre-wrap;word-break:break-all;margin:0;max-height:500px;overflow:auto;background:#fbfcfe;padding:8px;border:1px solid #e3e8ee}
 .toolbar{margin:8px 0;display:flex;gap:8px;flex-wrap:wrap;align-items:center}
 /* no own scroll area: the table scrolls with the page, the header rows stay at the top of the window */
 table.ft thead{position:sticky;top:0;z-index:1}
 th.sortable{cursor:pointer;user-select:none}
 th.sortable:after{content:' \\2195';color:#9aa6b2}
 th.sortable[data-dir=asc]:after{content:' \\25B2';color:#1d2733}
 th.sortable[data-dir=desc]:after{content:' \\25BC';color:#1d2733}
 tr.filters th{background:#f7f9fc;font-weight:normal}
 tr.filters input,tr.filters select{width:100%;box-sizing:border-box;font-size:12px;padding:2px 4px}
 /* columns that only take the width of their content (checkbox, #, actions) */
 th.shrink{width:1%} tr.filters input.num{width:3.5em}
 a.flt{border-bottom:1px dashed #0f62fe} a.flt:hover{text-decoration:none;background:#e5efff}
'''
}

/**
 * Served with ?resource=js
 * Note: this is a Groovy string, so the JavaScript must not contain backslashes.
 */
String jsCode() {
	return '''
var App={user:'',queues:[],counts:{},queue:null,cf:'',selector:'',maxcount:1,countonly:false,messages:[]};
var BASE=location.pathname;
var COLS=['MEAMessageID','JMSTimestamp','INTERFACE','MSG_TRK_EXTSYS','SENDER'];
var LINK_COLS=['MEAMessageID','INTERFACE','MSG_TRK_EXTSYS','SENDER'];

/* ================= helpers ================= */
function el(id){return document.getElementById(id);}
function esc(s){
  if(s===null||s===undefined)return '';
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}
/* JSON request to this script: resource=json&op=... ; post=true sends the parameters as a form POST */
function api(op,params,post){
  var p=new URLSearchParams();
  Object.keys(params||{}).forEach(function(k){
    var v=params[k];
    if(Array.isArray(v)){v.forEach(function(x){p.append(k,x);});}
    else if(v!==undefined&&v!==null){p.append(k,v);}
  });
  var url=BASE+'?resource=json&op='+encodeURIComponent(op);
  var opt={credentials:'same-origin',headers:{'Accept':'application/json'}};
  if(post){opt.method='POST';opt.body=p;}else{var qs=p.toString();if(qs)url+='&'+qs;}
  return fetch(url,opt).then(function(r){
    return r.text().then(function(t){
      var j;
      try{j=JSON.parse(t);}catch(e){throw new Error('HTTP '+r.status+': unexpected response from the server (Maximo session expired?)');}
      if(j&&j.error)throw new Error(j.error);
      if(!r.ok)throw new Error('HTTP '+r.status);
      return j;
    });
  });
}
function notify(text,ok){
  var b=el('banner');if(!b)return;
  if(!text){b.innerHTML='';return;}
  b.innerHTML='<div class="msg '+(ok?'ok':'bad')+'"><span>'+esc(text)+'</span><button type="button" class="mini" onclick="notify()">close</button></div>';
}
function fail(e){notify(e&&e.message?e.message:String(e),false);}

/* ================= navigation (hash, so the page is never reloaded) ================= */
function queueHash(q,cf){return '#queue='+encodeURIComponent(q)+'&cf='+encodeURIComponent(cf||'');}
function route(){
  var p=new URLSearchParams(location.hash.slice(1));
  var q=p.get('queue');
  notify();
  if(q){showQueue(q,p.get('cf')||'');}else{showList();}
}
function init(){
  document.body.innerHTML='<h1>JMS Message Admin <span id="user" class="muted" style="font-size:12px;font-weight:normal"></span></h1>'+
    '<div id="banner"></div><div id="view"><div class="card muted">Loading...</div></div>';
  window.addEventListener('hashchange',route);
  api('queues').then(function(j){
    App.user=j.user;App.queues=j.queues||[];
    el('user').textContent='user: '+App.user;
    route();
  }).catch(function(e){el('view').innerHTML='';fail(e);});
}
document.addEventListener('DOMContentLoaded',init);

/* ================= client-side filter + sort (tables inside an element with data-table) =================
   Rows with class "detail" (properties, message body) belong to the data row above them. */
function tid(x){return typeof x==='string'?x:x.closest('[data-table]').getAttribute('data-table');}
function dataRows(t){var r=t.tBodies[0].rows,a=[];for(var i=0;i<r.length;i++){if(!r[i].classList.contains('detail'))a.push(r[i]);}return a;}
function detailsOf(r){var a=[],n=r.nextElementSibling;while(n&&n.classList.contains('detail')){a.push(n);n=n.nextElementSibling;}return a;}
function applyFilters(x){
  var id=tid(x),t=el(id);if(!t)return;
  var gi=el(id+'-global'),g=gi?gi.value.trim().toLowerCase():'';
  var f=t.querySelectorAll('thead tr.filters input[data-col], thead tr.filters select[data-col]');
  var rows=dataRows(t),shown=0;
  for(var i=0;i<rows.length;i++){
    var r=rows[i],d=detailsOf(r),all=r.textContent;
    for(var a=0;a<d.length;a++){all+=' '+d[a].textContent;}
    var ok=!g||all.toLowerCase().indexOf(g)>=0;
    for(var j=0;ok&&j<f.length;j++){
      var v=f[j].value.trim().toLowerCase();if(!v)continue;
      var cell=r.cells[parseInt(f[j].getAttribute('data-col'),10)];
      var txt=(cell.getAttribute('data-filter')||cell.textContent).trim().toLowerCase();
      ok=f[j].tagName==='SELECT'?txt===v:txt.indexOf(v)>=0;
    }
    r.style.display=ok?'':'none';
    for(var y=0;y<d.length;y++){d[y].style.display=ok?'':'none';}
    if(!ok){var cb=r.querySelector('input[name=msgid]');if(cb)cb.checked=false;}
    if(ok)shown++;
  }
  var c=el(id+'-count');
  if(c)c.textContent=shown+' of '+rows.length+' '+(c.getAttribute('data-noun')||'rows');
}
function clearFilters(x){
  var id=tid(x),gi=el(id+'-global');if(gi)gi.value='';
  var f=el(id).querySelectorAll('thead tr.filters input[data-col], thead tr.filters select[data-col]');
  for(var i=0;i<f.length;i++){f[i].value='';}
  applyFilters(id);
}
function sortTable(th){
  var t=th.closest('table'),c=th.cellIndex,b=t.tBodies[0];
  var asc=th.getAttribute('data-dir')!=='asc';
  var hs=t.querySelectorAll('thead th.sortable');
  for(var i=0;i<hs.length;i++){hs[i].removeAttribute('data-dir');}
  th.setAttribute('data-dir',asc?'asc':'desc');
  var rows=dataRows(t),pairs=[];
  for(var p=0;p<rows.length;p++){pairs.push([rows[p],detailsOf(rows[p])]);}
  function key(r){var x=r.cells[c];return x?(x.getAttribute('data-sort')||x.textContent).trim():'';}
  pairs.sort(function(a,z){return key(a[0]).localeCompare(key(z[0]),undefined,{numeric:true,sensitivity:'base'})*(asc?1:-1);});
  for(var k=0;k<pairs.length;k++){b.appendChild(pairs[k][0]);for(var q=0;q<pairs[k][1].length;q++){b.appendChild(pairs[k][1][q]);}}
}
function searchBox(id,placeholder,width){
  return '<input type="text" id="'+id+'-global" placeholder="'+placeholder+'" oninput="applyFilters(this)" style="width:'+width+'px">'+
    '<button type="button" onclick="clearFilters(this)">Clear filters</button>';
}
function textFilter(col,cls,ph){
  return '<th><input type="text"'+(cls?' class="'+cls+'"':'')+' data-col="'+col+'" placeholder="'+(ph||'contains...')+'" oninput="applyFilters(this)"></th>';
}
function selectFilter(col,options){
  var h='<th><select data-col="'+col+'" onchange="applyFilters(this)"><option value="">all</option>';
  options.forEach(function(o){h+='<option value="'+esc(o)+'">'+esc(o)+'</option>';});
  return h+'</select></th>';
}
function sortTh(label,cls){return '<th class="sortable'+(cls?' '+cls:'')+'" onclick="sortTable(this)">'+esc(label)+'</th>';}

/* ================= queue list ================= */
function showList(){
  App.queue=null;
  var qs=App.queues,h=[];
  h.push('<div class="card" data-table="qtable"><div class="toolbar">');
  h.push('<b id="qtable-count" data-noun="queues">'+qs.length+' of '+qs.length+' queues</b>');
  h.push(searchBox('qtable','Search all columns...',260));
  h.push('<button type="button" class="primary" onclick="countAll(this)">Count messages in all queues</button>');
  h.push('<span id="countall-info" class="muted"></span></div>');
  h.push('<div class="scroll"><table id="qtable" class="ft"><thead><tr>');
  ['Queue','Messages','Direction','Type','Error queue','Connection factory','Email'].forEach(function(x){h.push(sortTh(x));});
  h.push('</tr><tr class="filters">');
  h.push(textFilter(0));
  h.push(selectFilter(1,['has messages','empty','error']));
  h.push(selectFilter(2,['inbound','outbound']));
  h.push(selectFilter(3,['sequential','continuous']));
  h.push(selectFilter(4,['yes','no']));
  h.push(textFilter(5));
  h.push(textFilter(6));
  h.push('</tr></thead><tbody>');
  qs.forEach(function(q){
    h.push('<tr data-q="'+esc(q.queueName)+'" data-cf="'+esc(q.confactName)+'">');
    h.push('<td><a href="'+esc(queueHash(q.queueName,q.confactName))+'">'+esc(q.queueName)+'</a></td>');
    h.push('<td class="count nowrap"><span class="cnt"></span> <button type="button" class="mini" onclick="countQueue(this)">Count messages</button></td>');
    h.push('<td data-filter="'+(q.inbound?'inbound':'outbound')+'"><span class="badge '+(q.inbound?'in':'out')+'">'+(q.inbound?'inbound':'outbound')+'</span></td>');
    h.push('<td>'+(q.sequential?'sequential':'continuous')+'</td>');
    h.push('<td data-filter="'+(q.errorQueue?'yes':'no')+'">'+(q.errorQueue?'<span class="badge err">yes</span>':'no')+'</td>');
    h.push('<td>'+esc(q.confactName)+'</td><td>'+esc(q.emailaddr)+'</td></tr>');
  });
  h.push('</tbody></table></div></div>');
  el('view').innerHTML=h.join('');
  /* show counts that are already known */
  dataRows(el('qtable')).forEach(function(tr){setCount(tr.querySelector('td.count'),App.counts[tr.getAttribute('data-q')]);});
}
function setCount(td,c){
  var span=td.querySelector('.cnt');span.innerHTML='';
  if(typeof c==='number'){
    span.className='cnt '+(c>0?'strong':'muted');span.textContent=c;
    td.setAttribute('data-sort',c);td.setAttribute('data-filter',c>0?'has messages':'empty');
  }else if(c&&c.error){
    span.className='cnt';span.innerHTML='<span class="badge err" title="'+esc(c.error)+'">error</span>';
    td.setAttribute('data-sort','-1');td.setAttribute('data-filter','error');
  }else{
    span.className='cnt';td.setAttribute('data-sort','');td.setAttribute('data-filter','');
  }
}
/* counts one queue row; resolves to the number, or null on error */
function countRow(tr){
  var td=tr.querySelector('td.count'),btn=td.querySelector('button'),span=td.querySelector('.cnt'),q=tr.getAttribute('data-q');
  btn.disabled=true;span.className='cnt muted';span.textContent='...';
  return api('count',{queuename:q,confactname:tr.getAttribute('data-cf')})
    .then(function(j){App.counts[q]=j.count;return j.count;})
    .catch(function(e){App.counts[q]={error:e.message};return null;})
    .then(function(n){setCount(td,App.counts[q]);btn.disabled=false;return n;});
}
function countQueue(btn){countRow(btn.closest('tr')).then(function(){applyFilters('qtable');});}
function countAll(btn){
  var rows=dataRows(el('qtable')),i=0,done=0,total=0,errors=0,info=el('countall-info');
  btn.disabled=true;info.textContent='Counting...';
  function next(){
    if(i>=rows.length)return Promise.resolve();
    var tr=rows[i++];
    return countRow(tr).then(function(n){
      if(typeof n==='number'){total+=n;}else{errors++;}
      done++;info.textContent='Counted '+done+' of '+rows.length+' queues...';
      return next();
    });
  }
  var workers=[];for(var w=0;w<4;w++){workers.push(next());}
  Promise.all(workers).then(function(){
    btn.disabled=false;
    info.textContent=total+' messages in all queues'+(errors?' ('+errors+' could not be counted)':'');
    applyFilters('qtable');
  });
}

/* ================= queue page ================= */
function showQueue(q,cf){
  if(App.queue!==q){App.selector='';App.maxcount=1;App.countonly=false;App.messages=[];}
  App.queue=q;
  App.cf=cf;
  if(!App.cf){App.queues.forEach(function(x){if(x.queueName===q)App.cf=x.confactName;});}
  var h=[];
  h.push('<p><a href="#">&larr; All queues</a></p>');
  h.push('<div class="card"><h2 class="first">'+esc(q)+'</h2><table class="kv" id="qcfg">');
  h.push('<tr><th>Messages in queue</th><td><span id="qtotal" class="big muted">...</span> <button type="button" class="mini" onclick="refreshTotal()">Refresh</button></td></tr>');
  h.push('</table></div>');
  h.push('<div class="card"><form id="qform" onsubmit="return runQuery()"><div class="toolbar">');
  h.push('<label><input type="checkbox" id="q-countonly"'+(App.countonly?' checked':'')+'> Count only</label>');
  h.push('<label>Selector <input type="text" id="q-selector" value="'+esc(App.selector)+'" placeholder="e.g. INTERFACE = &#39;MXASSETInterface&#39;"></label>');
  h.push('<label>Max count <input type="number" id="q-maxcount" min="-1" value="'+App.maxcount+'"></label>');
  h.push('<button type="submit" class="primary">Query</button>');
  h.push('</div><div class="muted">Max count -1 = all messages.</div></form></div>');
  h.push('<div id="results"></div>');
  el('view').innerHTML=h.join('');
  api('queue',{queuename:q}).then(function(j){
    var t=el('qcfg');if(!t||App.queue!==q)return;
    var rows='';
    Object.keys(j.config||{}).forEach(function(k){rows+='<tr><th>'+esc(k)+'</th><td>'+esc(j.config[k])+'</td></tr>';});
    t.insertAdjacentHTML('beforeend',rows);
  }).catch(fail);
  refreshTotal();
}
function refreshTotal(){
  var q=App.queue,s=el('qtotal');if(!s)return;
  s.className='big muted';s.textContent='...';
  api('count',{queuename:q,confactname:App.cf}).then(function(j){
    App.counts[q]=j.count;
    if(App.queue!==q||!el('qtotal'))return;
    s.className='big strong';s.textContent=j.count;
  }).catch(function(e){
    App.counts[q]={error:e.message};
    if(App.queue!==q||!el('qtotal'))return;
    s.className='';s.innerHTML='<span class="badge err">cannot count: '+esc(e.message)+'</span>';
  });
}
/* query form submit: read the form, then load the data */
function runQuery(){
  App.selector=el('q-selector').value;
  App.maxcount=parseInt(el('q-maxcount').value,10);
  if(isNaN(App.maxcount))App.maxcount=1;
  App.countonly=el('q-countonly').checked;
  notify();
  doQuery();
  return false;
}
/* runs the last query again (used after deleting) */
function doQuery(){
  var q=App.queue,r=el('results');if(!r)return Promise.resolve();
  r.innerHTML='<div class="card muted">Loading...</div>';
  var p={queuename:q,confactname:App.cf,selector:App.selector};
  if(App.countonly){
    return api('count',p).then(function(j){
      if(App.queue!==q)return;
      var sel=App.selector.trim();
      r.innerHTML='<div class="card"><h2 class="first">Count</h2><p class="big"><b>'+j.count+'</b> message(s) in <b>'+esc(q)+'</b>'+
        (sel?' matching <code>'+esc(sel)+'</code>':'')+'</p></div>';
    }).catch(function(e){r.innerHTML='';fail(e);});
  }
  p.maxcount=App.maxcount;
  return api('messages',p).then(function(j){
    if(App.queue!==q)return;
    renderMessages(j);
  }).catch(function(e){r.innerHTML='';fail(e);});
}

/* ================= message table ================= */
function selLiteral(v){return typeof v==='number'?String(v):"'"+String(v).split("'").join("''")+"'";}
/* cell whose value is a link: click adds "<prop> = <value>" to the Selector and runs the query */
function linkCell(prop,shown,val,cls){
  var c=cls?' class="'+cls+'"':'';
  if(shown===null||shown===undefined||shown===''||val===null||val===undefined||val===''){return '<td'+c+'>'+esc(shown)+'</td>';}
  var cond=prop+' = '+selLiteral(val);
  return '<td'+c+' data-sort="'+esc(shown)+'" data-filter="'+esc(shown)+'"><a href="#" class="flt" data-cond="'+esc(cond)+
    '" title="Query messages with '+esc(cond)+'" onclick="return filterLike(this)">'+esc(shown)+'</a></td>';
}
function filterLike(a){
  var cond=a.getAttribute('data-cond'),sel=el('q-selector'),cur=sel.value.trim();
  if(cur.indexOf(cond)<0){sel.value=cur?'('+cur+') AND '+cond:cond;}
  el('q-countonly').checked=false;
  runQuery();
  return false;
}
function renderMessages(j){
  var msgs=j.messages||[],h=[],colspan=4+COLS.length;
  App.messages=msgs;
  h.push('<div class="card" data-table="mtable"><h2 class="first">Messages</h2>');
  h.push('<div class="muted">Showing '+msgs.length+' message(s)'+(j.more?' &ndash; more messages match, raise <i>Max count</i> to see them':'')+'.</div>');
  if(!msgs.length){el('results').innerHTML=h.join('')+'</div>';return;}
  h.push('<div class="toolbar">');
  h.push('<button type="button" onclick="downloadDisplayed()">Download displayed messages (zip)</button>');
  h.push('<button type="button" class="danger" onclick="deleteSelected()">Delete selected messages</button>');
  h.push('<button type="button" class="danger" onclick="deleteDisplayed()">Delete all displayed messages</button>');
  h.push('</div><div class="toolbar">');
  h.push('<b id="mtable-count" data-noun="messages">'+msgs.length+' of '+msgs.length+' messages</b>');
  h.push(searchBox('mtable','Search columns, properties and message content...',300));
  h.push('</div>');
  h.push('<div class="scroll"><table id="mtable" class="ft"><thead><tr>');
  h.push('<th class="shrink"><input type="checkbox" title="Select all visible" onclick="setAll(this)"></th>');
  h.push(sortTh('#','shrink')+'<th class="shrink">Actions</th>'+sortTh('JMSMessageID'));
  COLS.forEach(function(c){h.push(sortTh(c));});
  h.push('</tr><tr class="filters"><th></th>'+textFilter(1,'num','#')+'<th></th>');
  for(var k=0;k<=COLS.length;k++){h.push(textFilter(3+k));}
  h.push('</tr></thead><tbody>');
  msgs.forEach(function(m,i){
    var props=m.props||{};
    h.push('<tr data-idx="'+i+'" data-id="'+esc(m.id)+'"><td><input type="checkbox" name="msgid" value="'+esc(m.id)+'"></td>');
    h.push('<td>'+(i+1)+'</td><td class="nowrap">');
    h.push('<button type="button" data-d="body" data-l="message" onclick="toggleDetail(this)">Show message</button> ');
    h.push('<button type="button" data-d="props" data-l="properties" onclick="toggleDetail(this)">Show properties</button> ');
    h.push('<button type="button" onclick="downloadRow(this)">Download</button> ');
    h.push('<button type="button" class="danger" onclick="deleteRow(this)">Delete</button></td>');
    h.push(linkCell('JMSMessageID',m.id,m.id,'nowrap'));
    COLS.forEach(function(c){
      var v=props[c];
      if(c==='JMSTimestamp'){h.push(linkCell(c,v,m.ts>0?m.ts:null,'nowrap'));}  /* selector needs the milliseconds */
      else if(LINK_COLS.indexOf(c)>=0){h.push(linkCell(c,v,v,null));}
      else{h.push('<td>'+esc(v)+'</td>');}
    });
    h.push('</tr>');
    var pr='';
    Object.keys(props).forEach(function(k){pr+='<tr><th>'+esc(k)+'</th><td>'+esc(props[k])+'</td></tr>';});
    h.push('<tr class="detail d-props" hidden><td colspan="'+colspan+'"><table class="kv props">'+pr+'</table></td></tr>');
    h.push('<tr class="detail d-body" hidden><td colspan="'+colspan+'"><pre>'+esc(m.body)+'</pre></td></tr>');
  });
  h.push('</tbody></table></div></div>');
  el('results').innerHTML=h.join('');
}
function toggleDetail(btn){
  var tr=btn.closest('tr'),d=detailsOf(tr),kind='d-'+btn.getAttribute('data-d');
  for(var i=0;i<d.length;i++){
    if(d[i].classList.contains(kind)){
      d[i].hidden=!d[i].hidden;
      btn.textContent=(d[i].hidden?'Show ':'Hide ')+btn.getAttribute('data-l');
    }
  }
}
function setAll(cb){
  var rows=dataRows(cb.closest('table'));
  for(var i=0;i<rows.length;i++){var c=rows[i].querySelector('input[name=msgid]');if(c)c.checked=cb.checked&&rows[i].style.display!=='none';}
}
function visibleRows(){return dataRows(el('mtable')).filter(function(r){return r.style.display!=='none';});}

/* ================= delete (POST, the page stays) ================= */
function deleteRow(btn){doDelete([btn.closest('tr').getAttribute('data-id')],'Delete this message from the queue?');}
function deleteSelected(){
  var ids=[],c=el('mtable').querySelectorAll('input[name=msgid]:checked');
  for(var i=0;i<c.length;i++){ids.push(c[i].value);}
  if(!ids.length){alert('No messages selected.');return;}
  doDelete(ids,'Delete the '+ids.length+' selected messages from the queue?');
}
function deleteDisplayed(){
  var ids=visibleRows().map(function(r){return r.getAttribute('data-id');});
  if(!ids.length){alert('No messages displayed.');return;}
  doDelete(ids,'Delete all '+ids.length+' displayed messages from the queue?');
}
function doDelete(ids,question){
  if(!confirm(question))return;
  api('delete',{queuename:App.queue,id:ids},true).then(function(d){
    var nf=d.notFound||[],fl=d.failed||[];
    var t='Deleted '+d.deleted+' of '+ids.length+' message(s).';
    if(nf.length)t+=' Not found (already processed or deleted): '+nf.join(', ');
    if(fl.length)t+=' Failed: '+fl.join('; ');
    notify(t,!nf.length&&!fl.length);
    refreshTotal();
    doQuery();
  }).catch(fail);
}

/* ================= downloads (built in the browser from the loaded messages) ================= */
function saveBlob(blob,name){
  var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=name;
  document.body.appendChild(a);a.click();
  setTimeout(function(){URL.revokeObjectURL(a.href);a.parentNode.removeChild(a);},1000);
}
function msgOf(tr){return App.messages[parseInt(tr.getAttribute('data-idx'),10)];}
function downloadRow(btn){
  var m=msgOf(btn.closest('tr'));
  saveBlob(new Blob([m.body||''],{type:'application/xml'}),m.file||'message.xml');
}
var CRC_T=null;
function crc32(u){
  if(!CRC_T){CRC_T=new Uint32Array(256);for(var n=0;n<256;n++){var c=n;for(var k=0;k<8;k++){c=(c&1)?(0xEDB88320^(c>>>1)):(c>>>1);}CRC_T[n]=c>>>0;}}
  var x=0xFFFFFFFF;for(var i=0;i<u.length;i++){x=CRC_T[(x^u[i])&255]^(x>>>8);}return (x^0xFFFFFFFF)>>>0;
}
/* minimal zip writer (no compression); files=[{name:'a.xml',data:Uint8Array}] */
function makeZip(files){
  var enc=new TextEncoder(),parts=[],central=[],off=0;
  function u16(v){return [v&255,(v>>>8)&255];}
  function u32(v){return [v&255,(v>>>8)&255,(v>>>16)&255,(v>>>24)&255];}
  for(var i=0;i<files.length;i++){
    var f=files[i],nm=enc.encode(f.name),crc=crc32(f.data),sz=f.data.length;
    var common=[].concat(u16(0x0800),u16(0),u16(0),u16(0x21),u32(crc),u32(sz),u32(sz),u16(nm.length),u16(0));
    var lh=new Uint8Array([0x50,0x4b,3,4].concat(u16(20),common));
    parts.push(lh,nm,f.data);
    central.push(new Uint8Array([0x50,0x4b,1,2].concat(u16(20),u16(20),common,u16(0),u16(0),u16(0),u32(0),u32(off))),nm);
    off+=lh.length+nm.length+sz;
  }
  var cs=0;for(var j=0;j<central.length;j++){cs+=central[j].length;}
  var end=new Uint8Array([0x50,0x4b,5,6].concat(u16(0),u16(0),u16(files.length),u16(files.length),u32(cs),u32(off),u16(0)));
  return new Blob(parts.concat(central,[end]),{type:'application/zip'});
}
function downloadDisplayed(){
  var rows=visibleRows(),enc=new TextEncoder(),files=[],used={};
  rows.forEach(function(tr){
    var m=msgOf(tr),base=m.file||'message.xml',name=base,n=1;
    var stem=base.slice(-4).toLowerCase()==='.xml'?base.slice(0,-4):base;
    while(used[name]){n++;name=stem+'_'+n+'.xml';}
    used[name]=true;
    files.push({name:name,data:enc.encode(m.body||'')});
  });
  if(!files.length){alert('No messages displayed.');return;}
  saveBlob(makeZip(files),App.queue.split('/').join('.').replace(/[^A-Za-z0-9._-]/g,'_')+'.zip');
}
'''
}

// ---------------------------------------------------------------- main

Map<String, List<String>> params = readParams()
String resource = p1(params, "resource", "").trim().toLowerCase()

if (resource == "css") {
	responseHeaders.put("content-type", "text/css")
	responseBody = cssCode()
} else if (resource == "js") {
	responseHeaders.put("content-type", "application/javascript")
	responseBody = jsCode()
} else if (resource == "json") {
	responseHeaders.put("content-type", "application/json")
	def result
	try {
		result = handleJson(params)
	} catch (Throwable t) {
		service.log_error("JMSMESSAGEADMIN: " + p1(params, "op", "") + " failed: " + t)
		result = [error: t.toString()]
	}
	responseBody = JsonOutput.toJson(result)
} else {
	responseHeaders.put("content-type", "text/html")
	responseBody = pageHtml()
}
